#include "ClickEngine.h"

#include <android/log.h>
#include <cmath>
#include <ctime>
#include <limits>

#define LOG_TAG "ClickEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
using Voice = std::vector<float>;

// Renders [seconds] of audio from a per-sample generator, with a 1 ms attack ramp
// so onsets never pop.
template <typename F>
Voice render(int sr, double seconds, float gain, F gen) {
    const int n = static_cast<int>(sr * seconds);
    Voice out(n);
    for (int i = 0; i < n; ++i) {
        const double t = static_cast<double>(i) / sr;
        const double attack = std::min(1.0, t / 0.001);
        out[i] = static_cast<float>(gen(t) * attack * gain);
    }
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

// Each sound: {accent, normal, subdivision}. Order must match Kotlin ClickSound.
std::vector<std::array<Voice, 3>> buildAll(int sr) {
    std::vector<std::array<Voice, 3>> v;
    auto tone = [&](double f, double decayMs, float g, double len = 0.08) {
        return render(sr, len, g, [=](double t) { return sine(f, t) * env(t, decayMs); });
    };
    auto wood = [&](double f, double decayMs, float g) {
        return render(sr, 0.1, g, [=](double t) {
            return (0.8 * sine(f, t) + 0.25 * sine(f * 2.76, t)) * env(t, decayMs);
        });
    };
    auto beep = [&](double f, double holdMs, float g) {
        return render(sr, holdMs / 1000.0 + 0.01, g, [=](double t) {
            const double rel = t > holdMs / 1000.0 ? std::max(0.0, 1.0 - (t - holdMs / 1000.0) / 0.01) : 1.0;
            return sine(f, t) * rel * 0.8;
        });
    };
    v.push_back({tone(1760, 12, 0.9f), tone(1320, 12, 0.7f), tone(1320, 8, 0.3f)});        // Click
    v.push_back({wood(1050, 20, 0.9f), wood(800, 20, 0.75f), wood(800, 14, 0.35f)});        // Woodblock
    v.push_back({beep(1320, 45, 0.8f), beep(880, 45, 0.6f), beep(880, 25, 0.25f)});         // Beep
    v.push_back({tone(2500, 28, 0.9f), tone(2000, 28, 0.75f), tone(2000, 18, 0.3f)});       // Clave
    v.push_back({cowbell(sr, 587, 845, 0.9f), cowbell(sr, 540, 800, 0.7f), cowbell(sr, 540, 800, 0.3f)});  // Cowbell
    v.push_back({hat(sr, 60, 1.0f), hat(sr, 30, 0.8f), hat(sr, 18, 0.4f)});                 // Hi-hat
    v.push_back({rim(sr, 1900, 1.0f), rim(sr, 1700, 0.8f), rim(sr, 1700, 0.35f)});          // Rimshot
    v.push_back({kick(sr, 1.0f), hat(sr, 30, 0.8f), hat(sr, 18, 0.35f)});                   // Kick and hat
    return v;
}
int64_t monotonicNowNs() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000000000LL + ts.tv_nsec;
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

void ClickEngine::buildVoices(int sr) { voices_ = buildAll(sr); }

int ClickEngine::soundCount() { return 8; }

void ClickEngine::resetTimeline() {
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

bool ClickEngine::openStream() {
    oboe::AudioStreamBuilder b;
    b.setDirection(oboe::Direction::Output)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)
        ->setFormat(oboe::AudioFormat::Float)
        ->setChannelCount(oboe::ChannelCount::Mono)
        ->setUsage(oboe::Usage::Media)
        ->setContentType(oboe::ContentType::Sonification)
        ->setDataCallback(this)
        ->setErrorCallback(this);

    std::shared_ptr<oboe::AudioStream> s;
    oboe::Result r = b.openStream(s);
    if (r != oboe::Result::OK) {
        LOGE("openStream failed: %s", oboe::convertToText(r));
        return false;
    }
    sampleRate_.store(s->getSampleRate());
    buildVoices(s->getSampleRate());
    resetTimeline();
    // Two bursts is the usual sweet spot between latency and glitch safety.
    s->setBufferSizeInFrames(s->getFramesPerBurst() * 2);
    stream_ = s;
    r = stream_->requestStart();
    if (r != oboe::Result::OK) {
        LOGE("requestStart failed: %s", oboe::convertToText(r));
        stream_->close();
        stream_.reset();
        return false;
    }
    LOGI("stream open: %d Hz, burst %d, api %d", s->getSampleRate(), s->getFramesPerBurst(),
         static_cast<int>(s->getAudioApi()));
    return true;
}

bool ClickEngine::start(bool clicks) {
    std::lock_guard<std::mutex> g(lock_);
    clicksEnabled_.store(clicks);
    if (running_.load()) return true;
    running_.store(true);
    if (!openStream()) {
        running_.store(false);
        return false;
    }
    return true;
}

void ClickEngine::stop() {
    std::lock_guard<std::mutex> g(lock_);
    running_.store(false);
    if (stream_) {
        stream_->stop();
        stream_->close();
        stream_.reset();
    }
}

void ClickEngine::onErrorAfterClose(oboe::AudioStream*, oboe::Result error) {
    // Typically ErrorDisconnected when headphones/Bluetooth connect or disconnect.
    LOGI("stream closed: %s", oboe::convertToText(error));
    // Oboe runs this on its own thread. If stop() holds the lock right now it is
    // already tearing the stream down, so don't wait for it (that could deadlock).
    std::unique_lock<std::mutex> g(lock_, std::try_to_lock);
    if (!g.owns_lock()) return;
    stream_.reset();
    if (running_.load()) openStream();
}

oboe::DataCallbackResult ClickEngine::onAudioReady(oboe::AudioStream* stream, void* audioData, int32_t numFrames) {
    auto* out = static_cast<float*>(audioData);
    const int sr = stream->getSampleRate();
    const int sub = subdivision_.load();
    const double bpm = bpm_.load();
    const double interval = sr * 60.0 / (bpm * sub);
    int sound = sound_.load();
    if (sound < 0 || sound >= static_cast<int>(voices_.size())) sound = 0;
    const float vol = volume_.load();
    const bool clicks = clicksEnabled_.load();

    const int pv = previewSound_.exchange(-1);
    if (pv >= 0 && pv < static_cast<int>(voices_.size())) {
        voice_ = &voices_[pv][0];
        voicePos_ = 0;
    }

    for (int i = 0; i < numFrames; ++i) {
        if (clicks && framesToNext_ <= 0.0) {
            const int count = beatCount_.load();
            if (beatIdx_ >= count) beatIdx_ = 0;
            if (subPos_ >= sub) subPos_ = 0;
            const int32_t state = beats_[beatIdx_].load();

            if (state == kMute) voice_ = nullptr;
            else if (subPos_ != 0) voice_ = &voices_[sound][2];
            else voice_ = &voices_[sound][state == kAccent ? 0 : 1];
            voicePos_ = 0;

            if (subPos_ == 0) {
                const int64_t n = ringCount_.load(std::memory_order_relaxed);
                Event& e = ring_[n % kRingSize];
                e.frame.store(frame_ + i, std::memory_order_relaxed);
                e.packed.store((seq_ << 8) | (static_cast<int64_t>(state) << 4) | beatIdx_, std::memory_order_relaxed);
                ringCount_.store(n + 1, std::memory_order_release);
                ++seq_;
            }

            // advance position for the next click
            if (++subPos_ >= sub) {
                subPos_ = 0;
                beatIdx_ = (beatIdx_ + 1) % count;
            }
            framesToNext_ += interval;
        }
        float v = 0.0f;
        if (voice_ && voicePos_ < voice_->size()) v = (*voice_)[voicePos_++] * vol;
        out[i] = v;
        // While silent (preview-only stream) hold the countdown at 0 so the first
        // real click lands immediately when clicks are switched on, instead of
        // a backlog of "missed" clicks firing at once.
        if (clicks) framesToNext_ -= 1.0;
    }
    frame_ += numFrames;
    return oboe::DataCallbackResult::Continue;
}

int64_t ClickEngine::frameToNs(int64_t frame) {
    std::shared_ptr<oboe::AudioStream> s;
    {
        std::lock_guard<std::mutex> g(lock_);
        s = stream_;
    }
    if (!s) return std::numeric_limits<int64_t>::min();
    const double sr = sampleRate_.load();
    auto ts = s->getTimestamp(CLOCK_MONOTONIC);
    if (ts) {
        return ts.value().timestamp + static_cast<int64_t>((frame - ts.value().position) * 1e9 / sr);
    }
    // No timestamp yet (stream just started): estimate from frames written and buffer size.
    const int64_t written = s->getFramesWritten();
    const int64_t buffered = s->getBufferSizeInFrames();
    return monotonicNowNs() + static_cast<int64_t>((frame - (written - buffered)) * 1e9 / sr);
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
