// ─────────────────────────────────────────────────────────────────────────────
// The real-time audio engine (Mac version).
//
// This is the same engine as the Android app (app/src/main/cpp): the click
// timing, sounds, accents and practice tools are copied line for line. Only the
// part that talks to the speakers differs: Android uses Google's Oboe, the Mac
// uses Apple's CoreAudio "output unit".
//
// LEARN — the golden rules of audio callbacks:
// macOS asks us for the next few milliseconds of sound (render) on a
// high-priority thread, hundreds of times a second. If we're late, you hear a
// glitch. So inside the callback we NEVER allocate memory, lock a mutex, or log.
// Settings from the UI arrive through std::atomic variables instead.
//
// LEARN — why counting samples beats timers:
// At 48 000 samples per second and 120 BPM, a beat is exactly 24 000 samples.
// We count samples between clicks, so the tempo is as precise as the audio
// hardware's clock. Timers can wake up late and drift.
// ─────────────────────────────────────────────────────────────────────────────
#pragma once

#include <AudioToolbox/AudioToolbox.h>
#include <array>
#include <atomic>
#include <cstdint>
#include <mutex>
#include <vector>

enum BeatState : int32_t { kAccent = 0, kNormal = 1, kMute = 2, kStrong = 3 };

constexpr int kMaxBeats = 16;
constexpr int kRingSize = 128;

class ClickEngine {
public:
    ClickEngine();
    ~ClickEngine();

    bool start(bool clicks);
    void stop();
    bool isRunning() const { return running_.load(); }
    void preview(int sound) { previewSound_.store(sound); }
    static int soundCount();
    void setSample(int slot, const float* data, int count, int rate);

    void setTempo(double bpm) { bpm_.store(bpm); }
    void setSubdivision(int perBeat) { subdivision_.store(perBeat < 1 ? 1 : (perBeat > 4 ? 4 : perBeat)); }
    void setBeats(const int32_t* states, int count);
    void setSound(int sound) { sound_.store(sound); }
    void setLastBeatFraction(double f) { lastBeatFraction_.store(f <= 0.0 || f > 1.0 ? 1.0 : f); }
    void setVolume(float v) { volume_.store(v); }

    void setGap(int on, int off, int grow) { gapOn_.store(on); gapOff_.store(off); gapGrow_.store(grow); }
    void setLeadBars(int bars) { leadBars_.store(bars < 0 ? 0 : bars); }
    void setRandomMute(int percent) { muteChance_.store(percent); }
    void setTempoAtNextBar(double bpm) { pendingBpm_.store(bpm); }
    void setSubdivisionAtNextBar(int perBeat) { pendingSub_.store(perBeat); }
    void setPoly(int count, int sound, bool mainOnlyOne) { polySound_.store(sound); polyMainOne_.store(mainOnlyOne); polyCount_.store(count); }

    int64_t audibleBeat(int64_t nowNs, int64_t offsetNs);
    int64_t nearestBeatOffset(int64_t tapNs);
    double outputLatencyMs() const;

    static int64_t nowNs();
    void onOutputChanged();

private:
    struct Event {
        std::atomic<int64_t> frame{-1};
        std::atomic<int64_t> packed{-1};
    };

    static OSStatus renderThunk(void* ref, AudioUnitRenderActionFlags* flags, const AudioTimeStamp* ts,
                                UInt32 bus, UInt32 frames, AudioBufferList* io);
    void render(const AudioTimeStamp* ts, float* out, int numFrames);
    bool openUnit();
    void closeUnit();
    void measureLatency();
    void buildVoices(int sampleRate);
    void resetTimeline();
    int64_t frameToNs(int64_t frame);

    std::mutex lock_;
    AudioUnit unit_ = nullptr;
    bool listening_ = false;
    std::atomic<bool> running_{false};
    std::atomic<int> sampleRate_{48000};
    std::atomic<int> bufferFrames_{128};
    std::atomic<int> latencyFrames_{0};   // device + safety offset + stream latency

    // Clock pairing written by the audio thread: "frame F plays at time T".
    // LEARN — a "seqlock": the writer bumps a counter to odd, writes, bumps it to
    // even. A reader that sees an odd or changed counter just reads again. This
    // lets the UI read two numbers that belong together without any lock.
    std::atomic<uint32_t> clockSeq_{0};
    std::atomic<int64_t> clockFrame_{-1};
    std::atomic<int64_t> clockNs_{0};

    // Parameters (written by UI thread, read by audio thread)
    std::atomic<double> bpm_{120.0};
    std::atomic<int> subdivision_{1};
    std::atomic<int> beatCount_{4};
    std::atomic<int32_t> beats_[kMaxBeats];
    std::atomic<int> sound_{0};
    std::atomic<float> volume_{1.0f};
    std::atomic<double> lastBeatFraction_{1.0};

    std::atomic<int> gapOn_{0};
    std::atomic<int> gapOff_{0};
    std::atomic<int> gapGrow_{0};
    std::atomic<int> leadBars_{0};
    std::atomic<int> muteChance_{0};
    std::atomic<double> pendingBpm_{-1.0};
    std::atomic<int> pendingSub_{-1};
    std::atomic<int> polyCount_{0};
    std::atomic<int> polySound_{1};
    std::atomic<bool> polyMainOne_{false};

    std::atomic<bool> clicksEnabled_{true};
    std::atomic<int> previewSound_{-1};
    std::atomic<bool> rewind_{false};

    std::vector<std::vector<float>> rawSamples_;
    std::vector<int> rawRates_;
    // Voices: [sound][accent, normal, sub] — synthesised ones first, then recorded ones
    std::vector<std::array<std::vector<float>, 3>> voices_;

    // Audio-thread timeline state
    int64_t frame_ = 0;
    double framesToNext_ = 0.0;
    int beatIdx_ = 0;
    int subPos_ = 0;
    int64_t seq_ = 0;
    int barInRound_ = -1;
    int round_ = 0;
    bool barSilent_ = false;
    bool beatMuted_ = false;
    uint32_t rng_ = 0x9E3779B9u;
    int polyIdx_ = 0;
    int polyN_ = 0;
    double polyToNext_ = 0.0;
    double polyInterval_ = 0.0;
    const std::vector<float>* voice2_ = nullptr;
    size_t voice2Pos_ = 0;
    const std::vector<float>* voice_ = nullptr;
    size_t voicePos_ = 0;
    float voiceGain_ = 1.0f;

    Event ring_[kRingSize];
    std::atomic<int64_t> ringCount_{0};
};
