// ─────────────────────────────────────────────────────────────────────────────
// The C doorway into the C++ click engine, for Swift.
//
// LEARN: Swift can call plain C functions directly (no bridge code needed, unlike
// Android's JNI). It can't easily call C++ classes, so we wrap the engine in a
// handful of C functions. `extern "C"` stops C++ from renaming ("mangling") them.
// Beat states, sounds and the beat packing match the Android engine exactly.
// ─────────────────────────────────────────────────────────────────────────────
#pragma once
#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/** Opens the audio output if needed. clicks = false keeps it silent ("warm") for instant starts and previews. */
bool mk_start(bool clicks);
void mk_stop(void);
bool mk_is_running(void);

void mk_set_tempo(double bpm);
void mk_set_subdivision(int perBeat);
/** states: 0 accent, 1 normal, 2 mute, 3 strong (max 16 beats). */
void mk_set_beats(const int32_t* states, int count);
void mk_set_sound(int sound);
void mk_set_last_beat_fraction(double fraction);
void mk_set_volume(float volume);
void mk_preview(int sound);
/** One recorded hit (mono, -1..1, at rate Hz) for sample slot [slot]. Call before the first mk_start. */
void mk_set_sample(int slot, const float* data, int count, int rate);
int mk_sound_count(void);

// Practice tools (same meaning as on Android)
void mk_set_gap(int on, int off, int grow);
void mk_set_lead_bars(int bars);
void mk_set_random_mute(int percent);
void mk_set_tempo_at_next_bar(double bpm);
void mk_set_subdivision_at_next_bar(int perBeat);
void mk_set_poly(int count, int sound, bool mainOnlyOne);

/** Now, in ns, on the same clock the engine uses for beat times. */
int64_t mk_now_ns(void);
/** Latest beat heard by nowNs: (sequence << 8) | (state << 4) | index, or -1. */
int64_t mk_audible_beat(int64_t nowNs, int64_t offsetNs);
/** tapNs minus the nearest beat's output time, or INT64_MIN if unknown. */
int64_t mk_nearest_beat_offset(int64_t tapNs);
/** How long a click takes from the engine to the speaker, in ms (buffer + device latency). 0 if not open. */
double mk_output_latency_ms(void);
/** Benchmark: ns to render one buffer of [frames] (busy pattern, offline). */
double mk_bench_render(int frames, int iterations);

// ── Audio output (Settings → Audio) ──
/** Output devices (speakers, headphones, interfaces). Fills ids, returns how many. */
int mk_output_devices(uint32_t* ids, int max);
void mk_device_name(uint32_t device, char* buf, int len);
/** A device's permanent ID (its number can change after a restart, this can't). */
void mk_device_uid(uint32_t device, char* buf, int len);
int mk_device_channels(uint32_t device);
uint32_t mk_default_output(void);
/** device 0 = follow the Mac's default output. firstChannel 0 = channels 1-2, 2 = 3-4... */
void mk_set_output(uint32_t device, int firstChannel);

#ifdef __cplusplus
}
#endif
