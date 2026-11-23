// The Mac click engine. See ClickEngine.h for the overview.
//
// Generated once from app/src/main/cpp/ClickEngine.cpp (the sounds, the beat
// loop and the practice tools are the same code), then the Oboe parts were
// replaced with CoreAudio. If you change the beat loop on Android, change it here too.
#include "ClickEngine.h"
#include "include/ClickCore.h"

#include <CoreAudio/CoreAudio.h>
#include <mach/mach_time.h>
#include <algorithm>
#include <cmath>
#include <cstring>
#include <limits>

namespace {
using Voice = std::vector<float>;

// Renders [seconds] of audio from a per-sample generator, then NORMALISES it so
// its loudest sample hits exactly [gain]. That way every sound is as loud as it
// can be without distorting, and accent/normal/sub differ only by [gain].
//
// LEARN: perceived "snappiness" comes from the first ~2 ms (the transient).
// A long fade-in softens it, so the ramp is only 0.15 ms: just enough to avoid
// a pop, short enough to keep the click crisp.
template <typename F>
Voice render(int sr, double seconds, float gain, F gen) {
    const int n = static_cast<int>(sr * seconds);
    Voice out(n);
    float peak = 0.0f;
    for (int i = 0; i < n; ++i) {
        const double t = static_cast<double>(i) / sr;
        const double attack = std::min(1.0, t / 0.00015);
        out[i] = static_cast<float>(gen(t) * attack);
        peak = std::max(peak, std::fabs(out[i]));
    }
    if (peak > 0.0f) for (auto& x : out) x *= gain / peak;
    return out;
}

double env(double t, double decayMs) { return std::exp(-t / (decayMs / 1000.0)); }
double sine(double f, double t) { return std::sin(2.0 * M_PI * f * t); }

// Deterministic white noise so every hit sounds identical.
struct Noise {
    uint32_t s = 0x12345678u;
    double next() {
        s = s * 1664525u + 1013904223u;
        return (s >> 8) / 8388608.0 - 1.0;
    }
};

Voice hat(int sr, double decayMs, float gain) {
    Noise n;
    double prev = 0.0;
    return render(sr, 0.12, gain, [&](double t) {
        const double x = n.next();
        const double hp = x - prev;  // crude high-pass: keeps it bright, not hissy-dull
        prev = x;
        return hp * 0.5 * env(t, decayMs);
    });
}

Voice kick(int sr, float gain) {
    double phase = 0.0;
    const double dt = 1.0 / sr;
    return render(sr, 0.25, gain, [&](double t) {
        const double f = 50.0 + 110.0 * std::exp(-t / 0.03);  // pitch sweep 160 -> 50 Hz
        phase += 2.0 * M_PI * f * dt;
        return std::sin(phase) * env(t, 90.0);
    });
}

Voice rim(int sr, double freq, float gain) {
    Noise n;
    return render(sr, 0.08, gain, [&](double t) {
        return 0.6 * sine(freq, t) * env(t, 14.0) + 0.5 * n.next() * env(t, 4.0);
    });
}

Voice cowbell(int sr, double f1, double f2, float gain) {
    return render(sr, 0.2, gain, [&](double t) {
        // two detuned partials with a few odd harmonics, like the classic analog bell
        auto sq = [&](double f) { return sine(f, t) + sine(3 * f, t) / 3.0 + sine(5 * f, t) / 5.0; };
        return 0.4 * (sq(f1) + sq(f2)) * env(t, 55.0);
    });
}

// Sample slots: must match the order of SAMPLE_FILES in Kotlin (SampleLoader.kt).
enum {
    kStickAccent, kStickNormal, kCowbellAccent, kCowbellNormal, kBellAccent, kBellNormal,
    kHatAccent, kHatNormal, kHatSoft, kKick, kRimAccent, kRimNormal, kSampleCount
};

// Converts a recorded hit to the output sample rate and normalises its peak to [gain].
//
// LEARN — resampling: the recordings are 44.1 kHz but most phones play at 48 kHz.
// Playing 44.1k samples at 48k would make them 9% shorter and higher-pitched,
// so we compute each output sample by reading "between" two input samples
// (linear interpolation). It's the simplest resampler; for short percussive
// hits it sounds the same as fancy ones.
Voice resample(const std::vector<float>& in, int inRate, int outRate, float gain) {
    if (in.empty() || inRate <= 0) return {};
    const double step = static_cast<double>(inRate) / outRate;
    const int n = static_cast<int>((in.size() - 1) / step);
    Voice out(n);
    float peak = 0.0f;
    for (int i = 0; i < n; ++i) {
        const double pos = i * step;
        const size_t k = static_cast<size_t>(pos);
        const double frac = pos - k;
        out[i] = static_cast<float>(in[k] * (1.0 - frac) + in[k + 1] * frac);
        peak = std::max(peak, std::fabs(out[i]));
    }
    if (peak > 0.0f) for (auto& x : out) x *= gain / peak;
    return out;
}

// Each sound: {accent, normal, subdivision}. Order must match Kotlin ClickSound.
std::vector<std::array<Voice, 3>> buildAll(int sr, const std::vector<std::vector<float>>& raw, const std::vector<int>& rates) {
    std::vector<std::array<Voice, 3>> v;
    // A 1.5 ms burst of noise on top of a tone gives it a sharp "tick" onset,
    // which makes the click feel immediate even through phone speakers.
    auto tick = [](double t) {
        static Noise n;
        return t < 0.0015 ? n.next() * (1.0 - t / 0.0015) : 0.0;
    };
    auto tone = [&](double f, double decayMs, float g, double len = 0.09) {
        return render(sr, len, g, [=](double t) { return sine(f, t) * env(t, decayMs) + 0.45 * tick(t); });
    };
    auto wood = [&](double f, double decayMs, float g) {
        return render(sr, 0.1, g, [=](double t) {
            return (0.8 * sine(f, t) + 0.3 * sine(f * 2.76, t)) * env(t, decayMs) + 0.35 * tick(t);
        });
    };
    auto beep = [&](double f, double holdMs, float g) {
        return render(sr, holdMs / 1000.0 + 0.01, g, [=](double t) {
            const double rel = t > holdMs / 1000.0 ? std::max(0.0, 1.0 - (t - holdMs / 1000.0) / 0.01) : 1.0;
            return sine(f, t) * rel;
        });
    };
    // Levels: accent 1.0, normal 0.8, subdivision 0.45 (after normalising).
    v.push_back({tone(1760, 22, 1.0f), tone(1320, 22, 0.8f), tone(1320, 14, 0.45f)});      // Click
    v.push_back({wood(1050, 26, 1.0f), wood(800, 26, 0.8f), wood(800, 18, 0.45f)});        // Woodblock
    v.push_back({beep(1320, 50, 1.0f), beep(880, 50, 0.8f), beep(880, 30, 0.45f)});        // Beep
    v.push_back({tone(2500, 32, 1.0f), tone(2000, 32, 0.8f), tone(2000, 20, 0.45f)});      // Clave
    v.push_back({cowbell(sr, 587, 845, 1.0f), cowbell(sr, 540, 800, 0.8f), cowbell(sr, 540, 800, 0.45f)});  // Cowbell
    v.push_back({hat(sr, 70, 1.0f), hat(sr, 40, 0.8f), hat(sr, 22, 0.45f)});               // Hi-hat
    v.push_back({rim(sr, 1900, 1.0f), rim(sr, 1700, 0.8f), rim(sr, 1700, 0.45f)});         // Rimshot
    v.push_back({kick(sr, 1.0f), hat(sr, 40, 0.8f), hat(sr, 22, 0.45f)});                  // Kick and hat

    // Recorded sounds. If a sample is missing (assets failed to load), fall back
    // to the plain click so nothing goes silent.
    auto smp = [&](int slot, float g) {
        Voice r = slot < static_cast<int>(raw.size()) ? resample(raw[slot], rates[slot], sr, g) : Voice{};
        return r.empty() ? tone(1320, 22, g) : r;
    };
    v.push_back({smp(kStickAccent, 1.0f), smp(kStickNormal, 0.8f), smp(kStickNormal, 0.45f)});        // Side stick
    v.push_back({smp(kCowbellAccent, 1.0f), smp(kCowbellNormal, 0.8f), smp(kCowbellNormal, 0.45f)});  // Cowbell (recorded)
    v.push_back({smp(kBellAccent, 1.0f), smp(kBellNormal, 0.8f), smp(kBellNormal, 0.45f)});           // Ride bell
    v.push_back({smp(kHatAccent, 1.0f), smp(kHatNormal, 0.8f), smp(kHatSoft, 0.45f)});                // Hi-hat (recorded)
    v.push_back({smp(kKick, 1.0f), smp(kHatNormal, 0.8f), smp(kHatSoft, 0.45f)});                     // Kick and hat (recorded)
    v.push_back({smp(kRimAccent, 1.0f), smp(kRimNormal, 0.8f), smp(kRimNormal, 0.45f)});              // Rimshot (recorded)
    return v;
}
}  // namespace

ClickEngine::ClickEngine() {
    for (int i = 0; i < kMaxBeats; ++i) beats_[i].store(i == 0 ? kAccent : kNormal);
}

ClickEngine::~ClickEngine() { stop(); }

void ClickEngine::setBeats(const int32_t* states, int count) {
    if (count < 1) count = 1;
    if (count > kMaxBeats) count = kMaxBeats;
    for (int i = 0; i < count; ++i) beats_[i].store(states[i]);
    beatCount_.store(count);
}

void ClickEngine::buildVoices(int sr) { voices_ = buildAll(sr, rawSamples_, rawRates_); }

int ClickEngine::soundCount() { return 14; }

void ClickEngine::setSample(int slot, const float* data, int count, int rate) {
    if (slot < 0 || slot >= kSampleCount || count <= 1) return;
    // Same lock as start/stop, so voices are never rebuilt while we write here.
    std::lock_guard<std::mutex> g(lock_);
    if (static_cast<int>(rawSamples_.size()) < kSampleCount) {
        rawSamples_.resize(kSampleCount);
        rawRates_.resize(kSampleCount, 0);
    }
    rawSamples_[slot].assign(data, data + count);
    rawRates_[slot] = rate;
}

void ClickEngine::resetTimeline() {
    barInRound_ = -1 - leadBars_.load();
    round_ = 0;
    frame_ = 0;
    framesToNext_ = 0.0;
    beatIdx_ = 0;
    subPos_ = 0;
    voice_ = nullptr;
    voicePos_ = 0;
    for (auto& e : ring_) {
        e.frame.store(-1);
        e.packed.store(-1);
    }
    ringCount_.store(0);
}

// LEARN — the Mac's clock: mach_absolute_time() counts CPU "ticks"; timebase
// converts ticks to nanoseconds. CoreAudio stamps every buffer with the same
// clock (mHostTime), so the UI and the audio agree on what "now" is.
int64_t ClickEngine::nowNs() {
    static mach_timebase_info_data_t tb = [] { mach_timebase_info_data_t t; mach_timebase_info(&t); return t; }();
    return static_cast<int64_t>(static_cast<__int128>(mach_absolute_time()) * tb.numer / tb.denom);
}

static int64_t hostToNs(uint64_t host) {
    static mach_timebase_info_data_t tb = [] { mach_timebase_info_data_t t; mach_timebase_info(&t); return t; }();
    return static_cast<int64_t>(static_cast<__int128>(host) * tb.numer / tb.denom);
}

static AudioDeviceID defaultOutputDevice() {
    AudioDeviceID dev = kAudioObjectUnknown;
    UInt32 size = sizeof(dev);
    AudioObjectPropertyAddress a{kAudioHardwarePropertyDefaultOutputDevice, kAudioObjectPropertyScopeGlobal, kAudioObjectPropertyElementMain};
    AudioObjectGetPropertyData(kAudioObjectSystemObject, &a, 0, nullptr, &size, &dev);
    return dev;
}

static bool deviceAlive(AudioDeviceID dev) {
    UInt32 v = 0, size = sizeof(v);
    AudioObjectPropertyAddress a{kAudioDevicePropertyDeviceIsAlive, kAudioObjectPropertyScopeGlobal, kAudioObjectPropertyElementMain};
    return AudioObjectGetPropertyData(dev, &a, 0, nullptr, &size, &v) == noErr && v != 0;
}

static int outputChannels(AudioDeviceID dev) {
    AudioObjectPropertyAddress a{kAudioDevicePropertyStreamConfiguration, kAudioObjectPropertyScopeOutput, kAudioObjectPropertyElementMain};
    UInt32 size = 0;
    if (AudioObjectGetPropertyDataSize(dev, &a, 0, nullptr, &size) != noErr || size == 0) return 0;
    std::vector<uint8_t> mem(size);
    auto* list = reinterpret_cast<AudioBufferList*>(mem.data());
    if (AudioObjectGetPropertyData(dev, &a, 0, nullptr, &size, list) != noErr) return 0;
    int n = 0;
    for (UInt32 i = 0; i < list->mNumberBuffers; ++i) n += static_cast<int>(list->mBuffers[i].mNumberChannels);
    return n;
}

AudioDeviceID ClickEngine::currentDevice() {
    const AudioDeviceID want = outputDevice_.load();
    if (want != 0 && deviceAlive(want) && outputChannels(want) > 0) return want;
    return defaultOutputDevice();
}

static UInt32 deviceUInt(AudioDeviceID dev, AudioObjectPropertySelector sel) {
    UInt32 v = 0, size = sizeof(v);
    AudioObjectPropertyAddress a{sel, kAudioObjectPropertyScopeOutput, kAudioObjectPropertyElementMain};
    if (AudioObjectGetPropertyData(dev, &a, 0, nullptr, &size, &v) != noErr) return 0;
    return v;
}

// How many frames sit between "we hand the sound over" and "it leaves the speaker":
// the device's own latency, its safety offset, and the output stream's latency.
void ClickEngine::measureLatency() {
    const AudioDeviceID dev = currentDevice();
    if (dev == kAudioObjectUnknown) return;
    UInt32 frames = deviceUInt(dev, kAudioDevicePropertyLatency) + deviceUInt(dev, kAudioDevicePropertySafetyOffset);
    AudioStreamID streams[8];
    UInt32 size = sizeof(streams);
    AudioObjectPropertyAddress a{kAudioDevicePropertyStreams, kAudioObjectPropertyScopeOutput, kAudioObjectPropertyElementMain};
    if (AudioObjectGetPropertyData(dev, &a, 0, nullptr, &size, streams) == noErr && size >= sizeof(AudioStreamID)) {
        UInt32 lat = 0, ls = sizeof(lat);
        AudioObjectPropertyAddress la{kAudioStreamPropertyLatency, kAudioObjectPropertyScopeGlobal, kAudioObjectPropertyElementMain};
        if (AudioObjectGetPropertyData(streams[0], &la, 0, nullptr, &ls, &lat) == noErr) frames += lat;
    }
    latencyFrames_.store(static_cast<int>(frames));
    const UInt32 buf = deviceUInt(dev, kAudioDevicePropertyBufferFrameSize);
    if (buf > 0) bufferFrames_.store(static_cast<int>(buf));
}

// Called by macOS (on its own thread) when the default output changes, e.g.
// AirPods connect. Bluetooth adds 100+ ms, so the latency must be measured again
// or the beat lights would run ahead of what you hear.
static OSStatus outputChanged(AudioObjectID, UInt32, const AudioObjectPropertyAddress*, void* ref) {
    static_cast<ClickEngine*>(ref)->onOutputChanged();
    return noErr;
}

void ClickEngine::onOutputChanged() { measureLatency(); }

// Called by macOS when a device is plugged in or out. If you picked an audio
// interface and unplug it, the click moves to the Mac's default output; when it
// comes back, the click moves back to it.
static OSStatus devicesChanged(AudioObjectID, UInt32, const AudioObjectPropertyAddress*, void* ref) {
    static_cast<ClickEngine*>(ref)->onDevicesChanged();
    return noErr;
}

void ClickEngine::onDevicesChanged() {
    // Only reopen when the device we should be on is not the one we're on.
    if (outputDevice_.load() == 0 || !running_.load()) return;
    if (currentDevice() == usingDevice_.load()) return;
    setOutput(outputDevice_.load(), outputChannel_.load());
}

void ClickEngine::setOutput(uint32_t device, int firstChannel) {
    std::lock_guard<std::mutex> g(lock_);
    outputDevice_.store(device);
    outputChannel_.store(firstChannel < 0 ? 0 : firstChannel);
    if (!running_.load()) return;
    // Reopen on the new output. Playing carries on (from beat 1).
    closeUnit();
    if (!openUnit()) running_.store(false);
}

OSStatus ClickEngine::renderThunk(void* ref, AudioUnitRenderActionFlags*, const AudioTimeStamp* ts,
                                  UInt32, UInt32 frames, AudioBufferList* io) {
    auto* self = static_cast<ClickEngine*>(ref);
    auto* left = static_cast<float*>(io->mBuffers[0].mData);
    self->render(ts, left, static_cast<int>(frames));
    // The click is mono: copy it to the second channel (memcpy is safe in the audio thread).
    for (UInt32 b = 1; b < io->mNumberBuffers; ++b) memcpy(io->mBuffers[b].mData, left, frames * sizeof(float));
    return noErr;
}

bool ClickEngine::openUnit() {
    // LEARN: the "default output" unit always plays to whatever output the Mac
    // is set to (speakers, headphones, an interface) and follows it when you switch.
    // If you picked a particular output (Settings → Audio), we use the "HAL
    // output" unit instead, which plays to one named device.
    const AudioDeviceID dev = currentDevice();
    const bool chosen = outputDevice_.load() != 0 && dev == outputDevice_.load();
    usingDevice_.store(dev);
    AudioComponentDescription d{};
    d.componentType = kAudioUnitType_Output;
    d.componentSubType = chosen ? kAudioUnitSubType_HALOutput : kAudioUnitSubType_DefaultOutput;
    d.componentManufacturer = kAudioUnitManufacturer_Apple;
    AudioComponent comp = AudioComponentFindNext(nullptr, &d);
    if (!comp || AudioComponentInstanceNew(comp, &unit_) != noErr) { unit_ = nullptr; return false; }
    if (chosen) {
        AudioDeviceID id = dev;
        AudioUnitSetProperty(unit_, kAudioOutputUnitProperty_CurrentDevice, kAudioUnitScope_Global, 0, &id, sizeof(id));
        // LEARN — channel map: one number per output channel of the device,
        // saying which of OUR channels plays there (-1 = silence). So on an
        // interface with 4 outputs and the click on 3-4 the map is [-1, -1, 0, 1].
        const int n = outputChannels(dev);
        if (n > 0) {
            std::vector<SInt32> map(n, -1);
            int first = outputChannel_.load();
            if (first >= n) first = 0;
            map[first] = 0;
            if (first + 1 < n) map[first + 1] = 1;
            AudioUnitSetProperty(unit_, kAudioOutputUnitProperty_ChannelMap, kAudioUnitScope_Input, 0,
                                 map.data(), static_cast<UInt32>(map.size() * sizeof(SInt32)));
        }
    }

    // Low latency: ask the device for small buffers (128 frames ≈ 2.7 ms at 48 kHz).
    // macOS gives each app its own buffer size, so this doesn't affect other apps.
    if (dev != kAudioObjectUnknown) {
        UInt32 want = 128;
        AudioObjectPropertyAddress a{kAudioDevicePropertyBufferFrameSize, kAudioObjectPropertyScopeOutput, kAudioObjectPropertyElementMain};
        AudioObjectSetPropertyData(dev, &a, 0, nullptr, sizeof(want), &want);
    }

    // Play at the device's own rate so nothing gets resampled on the way out.
    Float64 rate = 48000;
    {
        AudioStreamBasicDescription hw{};
        UInt32 size = sizeof(hw);
        if (AudioUnitGetProperty(unit_, kAudioUnitProperty_StreamFormat, kAudioUnitScope_Output, 0, &hw, &size) == noErr && hw.mSampleRate > 0)
            rate = hw.mSampleRate;
    }
    AudioStreamBasicDescription f{};
    f.mSampleRate = rate;
    f.mFormatID = kAudioFormatLinearPCM;
    f.mFormatFlags = kAudioFormatFlagIsFloat | kAudioFormatFlagIsPacked | kAudioFormatFlagIsNonInterleaved;
    f.mChannelsPerFrame = 2;   // stereo, both channels the same (see renderThunk)
    f.mBitsPerChannel = 32;
    f.mBytesPerFrame = 4;
    f.mFramesPerPacket = 1;
    f.mBytesPerPacket = 4;
    AudioUnitSetProperty(unit_, kAudioUnitProperty_StreamFormat, kAudioUnitScope_Input, 0, &f, sizeof(f));

    AURenderCallbackStruct cb{&ClickEngine::renderThunk, this};
    AudioUnitSetProperty(unit_, kAudioUnitProperty_SetRenderCallback, kAudioUnitScope_Input, 0, &cb, sizeof(cb));

    sampleRate_.store(static_cast<int>(rate));
    buildVoices(static_cast<int>(rate));
    resetTimeline();
    clockFrame_.store(-1);
    measureLatency();
    if (!listening_) {
        AudioObjectPropertyAddress a{kAudioHardwarePropertyDefaultOutputDevice, kAudioObjectPropertyScopeGlobal, kAudioObjectPropertyElementMain};
        listening_ = AudioObjectAddPropertyListener(kAudioObjectSystemObject, &a, outputChanged, this) == noErr;
    }
    if (!devListening_) {
        AudioObjectPropertyAddress a{kAudioHardwarePropertyDevices, kAudioObjectPropertyScopeGlobal, kAudioObjectPropertyElementMain};
        devListening_ = AudioObjectAddPropertyListener(kAudioObjectSystemObject, &a, devicesChanged, this) == noErr;
    }

    if (AudioUnitInitialize(unit_) != noErr || AudioOutputUnitStart(unit_) != noErr) {
        closeUnit();
        return false;
    }
    return true;
}

void ClickEngine::closeUnit() {
    if (!unit_) return;
    AudioOutputUnitStop(unit_);
    AudioUnitUninitialize(unit_);
    AudioComponentInstanceDispose(unit_);
    unit_ = nullptr;
}

bool ClickEngine::start(bool clicks) {
    std::lock_guard<std::mutex> g(lock_);
    if (running_.load()) {
        // Output already open ("warm"): starting is instant, no device setup.
        if (clicks && !clicksEnabled_.load()) rewind_.store(true);
        clicksEnabled_.store(clicks);
        return true;
    }
    clicksEnabled_.store(clicks);
    running_.store(true);
    if (!openUnit()) {
        running_.store(false);
        return false;
    }
    return true;
}

void ClickEngine::stop() {
    std::lock_guard<std::mutex> g(lock_);
    running_.store(false);
    closeUnit();
}

void ClickEngine::render(const AudioTimeStamp* ts, float* out, int numFrames) {
    // Remember when this buffer's first frame will be played (see the seqlock in the header).
    if (ts && (ts->mFlags & kAudioTimeStampHostTimeValid)) {
        clockSeq_.fetch_add(1, std::memory_order_acq_rel);
        clockFrame_.store(frame_, std::memory_order_relaxed);
        clockNs_.store(hostToNs(ts->mHostTime), std::memory_order_relaxed);
        clockSeq_.fetch_add(1, std::memory_order_acq_rel);
    }
    const int sr = sampleRate_.load();
    int sub = subdivision_.load();
    const double bpm = bpm_.load();
    double interval = sr * 60.0 / (bpm * sub);
    int sound = sound_.load();
    if (sound < 0 || sound >= static_cast<int>(voices_.size())) sound = 0;
    const float vol = volume_.load();
    const bool clicks = clicksEnabled_.load();

    // A fresh start on an already-open stream: begin again from beat 1, now.
    if (rewind_.exchange(false)) {
        beatIdx_ = 0;
        subPos_ = 0;
        framesToNext_ = 0.0;
        barInRound_ = -1 - leadBars_.load(); // count-in bars come first (negative = still counting in)
        round_ = 0;
    }

    const int pv = previewSound_.exchange(-1);
    if (pv >= 0 && pv < static_cast<int>(voices_.size())) {
        voice_ = &voices_[pv][0];
        voiceGain_ = 1.0f;
        voicePos_ = 0;
    }

    for (int i = 0; i < numFrames; ++i) {
        if (clicks && framesToNext_ <= 0.0) {
            const int count = beatCount_.load();
            if (beatIdx_ >= count) beatIdx_ = 0;
            if (subPos_ >= sub) subPos_ = 0;
            int32_t state = beats_[beatIdx_].load();

            if (beatIdx_ == 0 && subPos_ == 0) {
                // A new bar. Speed trainer: take the queued tempo now, on the "1".
                const double next = pendingBpm_.exchange(-1.0);
                if (next > 0.0) bpm_.store(next);
                // Subdivision ladder: take the queued subdivision on the "1" too.
                const int nextSub = pendingSub_.exchange(-1);
                if (nextSub >= 1 && nextSub <= 4) { subdivision_.store(nextSub); sub = nextSub; }
                interval = sr * 60.0 / (bpm_.load() * sub);
                // Polyrhythm: spread N hits evenly over this bar, starting together on the "1".
                polyN_ = polyCount_.load();
                if (polyN_ > 0) {
                    const double lastFrac = lastBeatFraction_.load();
                    const double barFrames = interval * sub * (count - 1 + (lastFrac < 0.999 ? lastFrac : 1.0));
                    polyInterval_ = barFrames / polyN_;
                    polyIdx_ = 0;
                    polyToNext_ = 0.0;
                }
                // Gap click: which bar of the round is this, and is it a silent one?
                const int on = gapOn_.load(), off = gapOff_.load();
                if (on > 0 && off > 0) {
                    int offNow = off + (gapGrow_.load() ? round_ : 0);
                    if (offNow > 8) offNow = 8;
                    if (++barInRound_ >= on + offNow) { barInRound_ = 0; ++round_; }
                    barSilent_ = barInRound_ >= on;
                } else {
                    barInRound_ = -1; round_ = 0; barSilent_ = false;
                }
            }
            if (subPos_ == 0) {
                // Random mutes: a quick xorshift random number (no allocation, safe here).
                beatMuted_ = false;
                const int chance = muteChance_.load();
                if (chance > 0 && beatIdx_ != 0) {
                    rng_ ^= rng_ << 13; rng_ ^= rng_ >> 17; rng_ ^= rng_ << 5;
                    beatMuted_ = static_cast<int>(rng_ % 100u) < chance;
                }
            }
            const bool silent = barSilent_ || beatMuted_;
            if (beatMuted_) state = kMute; // shows as a muted beat on screen

            const bool accented = state == kAccent || state == kStrong;
            // Polyrhythm "only the 1": the normal click keeps just the downbeat.
            const bool polyHush = polyN_ > 0 && polyMainOne_.load() && !(beatIdx_ == 0 && subPos_ == 0);
            if (state == kMute || silent || polyHush) voice_ = nullptr;
            else if (subPos_ != 0) voice_ = &voices_[sound][2];
            else voice_ = &voices_[sound][accented ? 0 : 1];
            // Strong = the accent sound pushed harder into the soft limiter.
            voiceGain_ = (state == kStrong && subPos_ == 0) ? 1.45f : 1.0f;
            voicePos_ = 0;

            if (subPos_ == 0) {
                const int64_t n = ringCount_.load(std::memory_order_relaxed);
                Event& e = ring_[n % kRingSize];
                e.frame.store(frame_ + i, std::memory_order_relaxed);
                e.packed.store((seq_ << 8) | (static_cast<int64_t>(state) << 4) | beatIdx_, std::memory_order_relaxed);
                ringCount_.store(n + 1, std::memory_order_release);
                ++seq_;
            }

            // Advance to the next click.
            const double lastFrac = lastBeatFraction_.load();
            if (beatIdx_ == count - 1 && lastFrac < 0.999) {
                // Decimal numerator (e.g. 3.5/4): the last beat is shortened to
                // `lastFrac` of a beat. It gets a single click (no subdivisions),
                // then the next bar starts early.
                framesToNext_ += interval * sub * lastFrac;
                subPos_ = 0;
                beatIdx_ = 0;
            } else {
                if (++subPos_ >= sub) {
                    subPos_ = 0;
                    beatIdx_ = (beatIdx_ + 1) % count;
                }
                framesToNext_ += interval;
            }
        }
        // Polyrhythm hits (after the main click, so both can start on the same frame)
        if (clicks && polyN_ > 0 && polyIdx_ < polyN_ && polyToNext_ <= 0.0) {
            int ps = polySound_.load();
            if (ps < 0 || ps >= static_cast<int>(voices_.size())) ps = 0;
            voice2_ = &voices_[ps][polyIdx_ == 0 ? 0 : 1];
            voice2Pos_ = 0;
            ++polyIdx_;
            polyToNext_ += polyInterval_;
        }
        float v = 0.0f;
        if (voice_ && voicePos_ < voice_->size()) v = (*voice_)[voicePos_++] * vol * voiceGain_;
        if (voice2_ && voice2Pos_ < voice2_->size()) v += (*voice2_)[voice2Pos_++] * vol * 0.85f;
        // Soft limiter: lifts quieter parts (sounds louder overall) and lets the
        // volume go above 100% without harsh digital clipping.
        out[i] = static_cast<float>(std::tanh(1.4 * v) / std::tanh(1.4));
        // While silent (preview-only stream) hold the countdown at 0 so the first
        // real click lands immediately when clicks are switched on, instead of
        // a backlog of "missed" clicks firing at once.
        if (clicks) { framesToNext_ -= 1.0; polyToNext_ -= 1.0; }
    }
    frame_ += numFrames;
}

int64_t ClickEngine::frameToNs(int64_t frame) {
    const double sr = sampleRate_.load();
    int64_t f0, t0;
    for (int tries = 0;; ++tries) {
        const uint32_t s1 = clockSeq_.load(std::memory_order_acquire);
        f0 = clockFrame_.load(std::memory_order_relaxed);
        t0 = clockNs_.load(std::memory_order_relaxed);
        const uint32_t s2 = clockSeq_.load(std::memory_order_acquire);
        if (s1 == s2 && (s1 & 1u) == 0) break;
        if (tries > 100) return std::numeric_limits<int64_t>::min();
    }
    if (f0 < 0) return std::numeric_limits<int64_t>::min();
    return t0 + static_cast<int64_t>((frame - f0 + latencyFrames_.load()) * 1e9 / sr);
}

bool ClickEngine::newestBeat(int64_t* count, int64_t* ns) {
    const int64_t n = ringCount_.load(std::memory_order_acquire);
    if (n == 0) return false;
    const int64_t f = ring_[(n - 1) % kRingSize].frame.load(std::memory_order_relaxed);
    if (f < 0) return false;
    const int64_t t = frameToNs(f);
    if (t == std::numeric_limits<int64_t>::min()) return false;
    *count = n;
    *ns = t;
    return true;
}

double ClickEngine::outputLatencyMs() const {
    if (!running_.load()) return 0.0;
    return (bufferFrames_.load() + latencyFrames_.load()) * 1000.0 / sampleRate_.load();
}

int64_t ClickEngine::audibleBeat(int64_t nowNs, int64_t offsetNs) {
    const int64_t count = ringCount_.load(std::memory_order_acquire);
    if (count == 0) return -1;
    // Walk back from newest to find the latest beat already heard.
    for (int64_t k = count - 1; k >= 0 && k > count - kRingSize; --k) {
        const Event& e = ring_[k % kRingSize];
        const int64_t f = e.frame.load(std::memory_order_relaxed);
        if (f < 0) break;
        const int64_t t = frameToNs(f);
        if (t == std::numeric_limits<int64_t>::min()) return -1;
        if (t + offsetNs <= nowNs) return e.packed.load(std::memory_order_relaxed);
    }
    return -1;
}

int64_t ClickEngine::nearestBeatOffset(int64_t tapNs) {
    const int64_t count = ringCount_.load(std::memory_order_acquire);
    int64_t best = std::numeric_limits<int64_t>::min();
    int64_t bestAbs = std::numeric_limits<int64_t>::max();
    for (int64_t k = count - 1; k >= 0 && k > count - kRingSize; --k) {
        const int64_t f = ring_[k % kRingSize].frame.load(std::memory_order_relaxed);
        if (f < 0) break;
        const int64_t t = frameToNs(f);
        if (t == std::numeric_limits<int64_t>::min()) break;
        const int64_t d = tapNs - t;
        const int64_t a = d < 0 ? -d : d;
        if (a < bestAbs) {
            bestAbs = a;
            best = d;
        }
    }
    return best;
}

double ClickEngine::benchRender(int frames, int iterations) {
    // A busy setting: 240 BPM sixteenths with a polyrhythm on top, so clicks start often.
    std::lock_guard<std::mutex> g(lock_);
    if (running_.load()) return -1.0;
    sampleRate_.store(48000);
    buildVoices(48000);
    resetTimeline();
    clicksEnabled_.store(true);
    bpm_.store(240.0); subdivision_.store(4); polyCount_.store(3);
    std::vector<float> out(frames);
    const int64_t t0 = nowNs();
    for (int k = 0; k < iterations; ++k) render(nullptr, out.data(), frames);
    const int64_t t1 = nowNs();
    polyCount_.store(0); subdivision_.store(1); bpm_.store(120.0);
    return static_cast<double>(t1 - t0) / iterations;
}

// ── C functions for Swift ───────────────────────────────────────────────────
ClickEngine* mk_engine() {
    static ClickEngine e;
    return &e;
}
static ClickEngine* engine() { return mk_engine(); }

extern "C" {
bool mk_start(bool clicks) { return engine()->start(clicks); }
void mk_stop(void) { engine()->stop(); }
bool mk_is_running(void) { return engine()->isRunning(); }
void mk_set_tempo(double bpm) { engine()->setTempo(bpm); }
void mk_set_subdivision(int perBeat) { engine()->setSubdivision(perBeat); }
void mk_set_beats(const int32_t* states, int count) { engine()->setBeats(states, count); }
void mk_set_sound(int sound) { engine()->setSound(sound); }
void mk_set_last_beat_fraction(double fraction) { engine()->setLastBeatFraction(fraction); }
void mk_set_volume(float volume) { engine()->setVolume(volume); }
void mk_preview(int sound) { engine()->preview(sound); }
void mk_set_sample(int slot, const float* data, int count, int rate) { engine()->setSample(slot, data, count, rate); }
int mk_sound_count(void) { return ClickEngine::soundCount(); }
void mk_set_gap(int on, int off, int grow) { engine()->setGap(on, off, grow); }
void mk_set_lead_bars(int bars) { engine()->setLeadBars(bars); }
void mk_set_random_mute(int percent) { engine()->setRandomMute(percent); }
void mk_set_tempo_at_next_bar(double bpm) { engine()->setTempoAtNextBar(bpm); }
void mk_set_subdivision_at_next_bar(int perBeat) { engine()->setSubdivisionAtNextBar(perBeat); }
void mk_set_poly(int count, int sound, bool mainOnlyOne) { engine()->setPoly(count, sound, mainOnlyOne); }
int64_t mk_now_ns(void) { return ClickEngine::nowNs(); }
int64_t mk_audible_beat(int64_t nowNs, int64_t offsetNs) { return engine()->audibleBeat(nowNs, offsetNs); }
int64_t mk_nearest_beat_offset(int64_t tapNs) { return engine()->nearestBeatOffset(tapNs); }
double mk_output_latency_ms(void) { return engine()->outputLatencyMs(); }
double mk_bench_render(int frames, int iterations) { return engine()->benchRender(frames, iterations); }
}

// ── Output devices (Settings → Audio) ───────────────────────────────────────
void mk_set_output(uint32_t device, int firstChannel) { engine()->setOutput(device, firstChannel); }

int mk_output_devices(uint32_t* ids, int max) {
    AudioObjectPropertyAddress a{kAudioHardwarePropertyDevices, kAudioObjectPropertyScopeGlobal, kAudioObjectPropertyElementMain};
    UInt32 size = 0;
    if (AudioObjectGetPropertyDataSize(kAudioObjectSystemObject, &a, 0, nullptr, &size) != noErr) return 0;
    std::vector<AudioDeviceID> all(size / sizeof(AudioDeviceID));
    if (AudioObjectGetPropertyData(kAudioObjectSystemObject, &a, 0, nullptr, &size, all.data()) != noErr) return 0;
    int n = 0;
    for (AudioDeviceID d : all) {
        if (outputChannels(d) <= 0) continue;   // microphones and other input-only devices
        if (n < max) ids[n] = d;
        ++n;
    }
    return n < max ? n : max;
}

static void cfString(AudioDeviceID dev, AudioObjectPropertySelector sel, char* buf, int len) {
    if (len <= 0) return;
    buf[0] = 0;
    CFStringRef s = nullptr;
    UInt32 size = sizeof(s);
    AudioObjectPropertyAddress a{sel, kAudioObjectPropertyScopeGlobal, kAudioObjectPropertyElementMain};
    if (AudioObjectGetPropertyData(dev, &a, 0, nullptr, &size, &s) != noErr || !s) return;
    CFStringGetCString(s, buf, len, kCFStringEncodingUTF8);
    CFRelease(s);
}

void mk_device_name(uint32_t device, char* buf, int len) { cfString(device, kAudioObjectPropertyName, buf, len); }
void mk_device_uid(uint32_t device, char* buf, int len) { cfString(device, kAudioDevicePropertyDeviceUID, buf, len); }
int mk_device_channels(uint32_t device) { return outputChannels(device); }
uint32_t mk_default_output(void) { return defaultOutputDevice(); }
