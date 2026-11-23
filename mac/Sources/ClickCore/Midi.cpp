// MIDI for the Mac app: a clock other music apps can follow, and incoming MIDI
// (pedals, controllers, another app's clock).
//
// LEARN — what MIDI clock is: 24 tiny "tick" messages per quarter note, plus
// Start and Stop. Logic, Ableton, GarageBand and drum machines can follow it,
// so they play at the metronome's tempo. Every message we send carries a
// timestamp ("play this at exactly time T"), and macOS delivers it then. So we
// can work a few ms ahead and still be exact.
//
// LEARN — why a separate thread: the audio thread must never wait on anything,
// and sending MIDI can. So a small thread wakes up every millisecond, asks the
// engine when the newest beat will be heard, and books the ticks around it.
#include "ClickEngine.h"
#include "include/ClickCore.h"

#include <CoreMIDI/CoreMIDI.h>
#include <mach/mach_time.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <mutex>
#include <thread>
#include <vector>

namespace {

MIDIClientRef client = 0;
MIDIEndpointRef source = 0;   // "Metronome Killer": other apps listen to this
MIDIPortRef inPort = 0;
std::mutex sourcesLock;
std::vector<MIDIEndpointRef> connected;

std::atomic<bool> sendClock{false};
std::atomic<double> ticksPerBeat{24.0};

mk_midi_callback inputCb = nullptr;
void* inputCtx = nullptr;

// Incoming clock (to follow another app's tempo): the last 25 tick times.
std::mutex inClockLock;
int64_t inTicks[25];
int inTickCount = 0;
int inTickNext = 0;
std::atomic<int64_t> lastInTickNs{0};

mach_timebase_info_data_t timebase() {
    static mach_timebase_info_data_t tb = [] { mach_timebase_info_data_t t; mach_timebase_info(&t); return t; }();
    return tb;
}
MIDITimeStamp nsToHost(int64_t ns) {
    const auto tb = timebase();
    return static_cast<MIDITimeStamp>(static_cast<__int128>(ns) * tb.denom / tb.numer);
}
int64_t hostToNsMidi(MIDITimeStamp h) {
    const auto tb = timebase();
    return static_cast<int64_t>(static_cast<__int128>(h) * tb.numer / tb.denom);
}

/** Sends one system real-time message (clock F8, start FA, stop FC) at time [ns]. */
void sendRealtime(uint8_t status, int64_t ns) {
    if (!source) return;
    // LEARN: "Universal MIDI Packets" store a MIDI 1.0 message in one 32-bit
    // word: type 1 (system) in the top 4 bits, then the status byte.
    MIDIEventList list;
    MIDIEventPacket* p = MIDIEventListInit(&list, kMIDIProtocol_1_0);
    const uint32_t word = 0x10000000u | (static_cast<uint32_t>(status) << 16);
    p = MIDIEventListAdd(&list, sizeof(list), p, ns > 0 ? nsToHost(ns) : 0, 1, &word);
    if (p) MIDIReceivedEventList(source, &list);
}

void connectSources() {
    std::lock_guard<std::mutex> g(sourcesLock);
    for (MIDIEndpointRef e : connected) MIDIPortDisconnectSource(inPort, e);
    connected.clear();
    const ItemCount n = MIDIGetNumberOfSources();
    for (ItemCount i = 0; i < n; ++i) {
        MIDIEndpointRef e = MIDIGetSource(i);
        if (e == source) continue;   // don't listen to ourselves
        if (MIDIPortConnectSource(inPort, e, nullptr) == noErr) connected.push_back(e);
    }
}

void onIncoming(uint8_t status, uint8_t d1, uint8_t d2, int64_t ns) {
    if (status == 0xF8) {
        // Clock ticks come 24 per quarter note, far too often to pass on to Swift.
        // We just note when they came, and work the tempo out from that.
        std::lock_guard<std::mutex> g(inClockLock);
        inTicks[inTickNext] = ns;
        inTickNext = (inTickNext + 1) % 25;
        if (inTickCount < 25) ++inTickCount;
        lastInTickNs.store(ns);
        return;
    }
    if (status == 0xFE) return;   // "active sensing": a keep-alive some keyboards send
    if (inputCb) inputCb(inputCtx, status, d1, d2, ns);
}

/** The clock thread (see the top of this file). Runs for the app's whole life, mostly asleep. */
void clockLoop() {
    bool started = false;
    bool wasPlaying = false;
    int64_t startCount = 0, lastBeat = -1;
    double nextTick = 0;
    for (;;) {
        ClickEngine* e = mk_engine();
        const bool on = sendClock.load();
        const bool playing = on && e->clicking();
        if (!playing) {
            if (started) sendRealtime(0xFC, 0);   // Stop, right away
            started = false;
            wasPlaying = false;
            std::this_thread::sleep_for(std::chrono::milliseconds(on ? 2 : 50));
            continue;
        }
        int64_t count = 0, beatNs = 0;
        const bool have = e->newestBeat(&count, &beatNs);
        if (!wasPlaying) { wasPlaying = true; startCount = have ? count : 0; }
        if (have && count > startCount) {
            const double spacing = 60e9 / e->beatRate() / ticksPerBeat.load();
            if (!started) {
                // The first beat after Start: Start goes out just before it, then the first tick on it.
                sendRealtime(0xFA, beatNs - 1000000);
                nextTick = static_cast<double>(beatNs);
                started = true;
                lastBeat = count;
            } else if (count != lastBeat) {
                // A new beat: line the ticks up with it again, so tempo changes
                // and timing never drift apart.
                lastBeat = count;
                const double k = std::round((nextTick - static_cast<double>(beatNs)) / spacing);
                nextTick = static_cast<double>(beatNs) + (k > 0 ? k : 0) * spacing;
            }
            const int64_t now = ClickEngine::nowNs();
            const double horizon = static_cast<double>(now) + 10e6;   // book up to 10 ms ahead
            int guard = 0;
            while (nextTick < horizon && guard++ < 64) {
                sendRealtime(0xF8, std::max<int64_t>(static_cast<int64_t>(nextTick), now));
                nextTick += spacing;
            }
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
}

}  // namespace

bool mk_midi_open(void) {
    if (client) return true;
    if (MIDIClientCreateWithBlock(CFSTR("Metronome Killer"), &client, ^(const MIDINotification* n) {
            // A device was plugged in or out: listen to whatever is there now.
            if (n->messageID == kMIDIMsgSetupChanged) connectSources();
        }) != noErr) {
        client = 0;
        return false;
    }
    MIDISourceCreateWithProtocol(client, CFSTR("Metronome Killer"), kMIDIProtocol_1_0, &source);
    MIDIInputPortCreateWithProtocol(client, CFSTR("Metronome Killer in"), kMIDIProtocol_1_0, &inPort,
        ^(const MIDIEventList* list, void*) {
            const MIDIEventPacket* p = &list->packet[0];
            for (UInt32 i = 0; i < list->numPackets; ++i) {
                const int64_t ns = p->timeStamp ? hostToNsMidi(p->timeStamp) : ClickEngine::nowNs();
                for (UInt32 w = 0; w < p->wordCount;) {
                    const uint32_t word = p->words[w];
                    const uint32_t type = word >> 28;
                    if (type == 1 || type == 2) {   // system, or MIDI 1.0 channel message
                        onIncoming(static_cast<uint8_t>((word >> 16) & 0xFF), static_cast<uint8_t>((word >> 8) & 0x7F),
                                   static_cast<uint8_t>(word & 0x7F), ns);
                    }
                    // Message types 3 and 4 take two words, 5 takes four, the rest one.
                    w += (type == 3 || type == 4) ? 2 : (type == 5 ? 4 : 1);
                }
                p = MIDIEventPacketNext(p);
            }
        });
    connectSources();
    std::thread(clockLoop).detach();
    return true;
}

void mk_midi_send_clock(bool on, double perBeat) {
    ticksPerBeat.store(perBeat > 0 ? perBeat : 24.0);
    sendClock.store(on);
}

void mk_midi_set_input(mk_midi_callback cb, void* ctx) {
    inputCtx = ctx;
    inputCb = cb;
}

int mk_midi_source_count(void) {
    std::lock_guard<std::mutex> g(sourcesLock);
    return static_cast<int>(connected.size());
}

double mk_midi_incoming_bpm(void) {
    // No tick for half a second = the other app stopped sending.
    if (ClickEngine::nowNs() - lastInTickNs.load() > 500000000) return 0.0;
    std::lock_guard<std::mutex> g(inClockLock);
    if (inTickCount < 25) return 0.0;
    // Oldest to newest over 24 ticks = one quarter note.
    const int64_t newest = inTicks[(inTickNext + 24) % 25];
    const int64_t oldest = inTicks[inTickNext % 25];
    const double quarterNs = static_cast<double>(newest - oldest);
    return quarterNs > 0 ? 60e9 / quarterNs : 0.0;
}
