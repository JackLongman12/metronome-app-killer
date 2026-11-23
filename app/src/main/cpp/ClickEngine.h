// ─────────────────────────────────────────────────────────────────────────────
// The real-time audio engine.
//
// LEARN — the golden rules of audio callbacks:
// Android asks us for the next few milliseconds of sound (onAudioReady) on a
// high-priority thread, hundreds of times a second. If we're late, you hear a
// glitch. So inside the callback we NEVER: allocate memory, lock a mutex,
// log, or call into Java. Settings from the UI arrive through std::atomic
// variables, which can be read safely without locks.
//
// LEARN — why counting samples beats timers:
// At 48 000 samples per second and 120 BPM, a beat is exactly 24 000 samples.
// We count samples between clicks, so the tempo is as precise as the audio
// hardware's clock. Timers (Handler, delay) can wake up late and drift.
// ─────────────────────────────────────────────────────────────────────────────
#pragma once

#include <oboe/Oboe.h>
#include <array>
#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <vector>

// Beat states, must match Kotlin BeatState ordinals.
// kStrong (3 filled segments in the UI) is the accent sound played louder.
enum BeatState : int32_t { kAccent = 0, kNormal = 1, kMute = 2, kStrong = 3 };

constexpr int kMaxBeats = 16;
constexpr int kRingSize = 128;

/**
 * Sample-accurate click generator on a low-latency Oboe output stream.
 *
 * The audio callback counts frames between clicks, so tempo never drifts.
 * Every main beat is logged with the frame it starts on; the UI converts that
 * to wall-clock time with the stream's presentation timestamp, so visuals and
 * haptics line up with what actually comes out of the speaker/headphones.
 */
class ClickEngine : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
public:
    ClickEngine();
    ~ClickEngine() override;

    /** [clicks] = false opens the stream silent, for sound previews. */
    bool start(bool clicks = true);
    void stop();
    void setClicksEnabled(bool on) { clicksEnabled_.store(on); }
    /** Plays one accent hit of [sound] as soon as possible (stream must be running). */
    void preview(int sound) { previewSound_.store(sound); }
    static int soundCount();
    bool isRunning() const { return running_.load(); }

    void setTempo(double bpm) { bpm_.store(bpm); }
    void setSubdivision(int perBeat) { subdivision_.store(perBeat < 1 ? 1 : (perBeat > 4 ? 4 : perBeat)); }
    void setBeats(const int32_t* states, int count);
    void setSound(int sound) { sound_.store(sound); }
    /** Length of the bar's last beat as a fraction (3.5/4 → 0.5). 1.0 = a whole beat. */
    void setLastBeatFraction(double f) { lastBeatFraction_.store(f <= 0.0 || f > 1.0 ? 1.0 : f); }
    void setVolume(float v) { volume_.store(v); }

    // ── Practice tools ──
    /**
     * Gap click: [on] bars with the click, then [off] silent bars, repeating.
     * [grow] = 1 adds one more silent bar each round (up to 8). on/off 0 = off.
     */
    void setGap(int on, int off, int grow) { gapOn_.store(on); gapOff_.store(off); gapGrow_.store(grow); }
    /** Count-in: this many clicking bars come before the gap pattern starts (read at start). */
    void setLeadBars(int bars) { leadBars_.store(bars < 0 ? 0 : bars); }
    /** Randomly silence this percentage of beats (never beat 1). 0 = off. */
    void setRandomMute(int percent) { muteChance_.store(percent); }
    /** Switch to [bpm] exactly when the next bar starts (speed trainer). */
    void setTempoAtNextBar(double bpm) { pendingBpm_.store(bpm); }
    /** Switch subdivision (1–4 clicks per beat) exactly when the next bar starts (subdivision ladder). */
    void setSubdivisionAtNextBar(int perBeat) { pendingSub_.store(perBeat); }
    /**
     * Polyrhythm: a second click playing [count] evenly spaced hits per bar in
     * [sound], against the normal beats (e.g. 3 against 4). 0 = off.
     * [mainOnlyOne] = the normal click plays only the "1", so the cross-rhythm stands out.
     */
    void setPoly(int count, int sound, bool mainOnlyOne) { polySound_.store(sound); polyMainOne_.store(mainOnlyOne); polyCount_.store(count); }

    /**
     * Latest beat whose sound has reached the output by [nowNs] (CLOCK_MONOTONIC),
     * after shifting by [offsetNs] of extra output latency (e.g. Bluetooth).
     * Packed as (sequence << 8) | (state << 4) | beatIndex, or -1 if none yet.
     */
    int64_t audibleBeat(int64_t nowNs, int64_t offsetNs);

    /** Signed ns between [tapNs] and the nearest beat's presentation time. INT64_MIN if unknown. */
    int64_t nearestBeatOffset(int64_t tapNs);

    // oboe callbacks
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream, void* audioData, int32_t numFrames) override;
    void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) override;

private:
    struct Event {
        std::atomic<int64_t> frame{-1};
        std::atomic<int64_t> packed{-1};
    };

    bool openStream();
    void buildVoices(int sampleRate);
    void resetTimeline();
    /** Presentation time (ns, CLOCK_MONOTONIC) of [frame], or INT64_MIN if no timestamp yet. */
    int64_t frameToNs(int64_t frame);

    std::mutex lock_;
    std::shared_ptr<oboe::AudioStream> stream_;
    std::atomic<bool> running_{false};
    std::atomic<int> sampleRate_{48000};

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

    // Synthesised voices: [sound][accent, normal, sub]
    std::vector<std::array<std::vector<float>, 3>> voices_;

    // Audio-thread timeline state
    int64_t frame_ = 0;
    double framesToNext_ = 0.0;
    int beatIdx_ = 0;
    int subPos_ = 0;
    int64_t seq_ = 0;
    int barInRound_ = -1;   // gap click: bar number within the current round (-1 = before bar 1)
    int round_ = 0;
    bool barSilent_ = false;
    bool beatMuted_ = false;
    uint32_t rng_ = 0x9E3779B9u;
    // Polyrhythm voice (its own countdown, restarted on every bar's "1")
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
