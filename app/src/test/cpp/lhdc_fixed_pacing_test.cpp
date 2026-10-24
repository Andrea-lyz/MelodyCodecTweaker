#include "../../main/cpp/lhdc_fixed_pacing.h"

#include <assert.h>
#include <fstream>
#include <iostream>
#include <iterator>
#include <vector>

using melody::pacing::Binding;
using melody::pacing::FileHeader;
using melody::pacing::Section;
using melody::pacing::Session;
using melody::pacing::find_binding;

static void test_sessions() {
    Session s;
    assert(s.interval(20, true, false) == 10);
    assert(!s.forward()); // no premature 20 ms PCM read at the first 10 ms wakeup
    for (int i = 0; i < 99; ++i) assert(s.forward());
    assert(s.interval(20, false, false) == 20); // adaptive / connection priority
    assert(s.forward());
    assert(s.interval(20, true, true) == 20); // governor retains stock cadence
    assert(s.forward());
    assert(s.interval(10, true, false) == 10); // OEM low latency needs no startup defer
    assert(s.forward());
    assert(s.interval(15, true, false) == 15); // unknown period is never overridden
    assert(s.forward());
    assert(s.interval(20, true, false) == 10); // next stream gets its own first batch
    assert(!s.forward());
    assert(s.forward());
    assert(s.interval(20, true, false) == 10);
    assert(s.interval(20, false, false) == 20); // a newer stock session cancels the defer
    assert(s.forward());
    assert(s.interval(20, true, false) == 10); // querying during a live stream is harmless
    assert(s.forward(false));
    assert(s.forward(false));
}

// Execute the verified OEM elapsed-time arithmetic at both timer cadences. The shorter
// cadence must consume the same PCM, including the unchanged first 20 ms batch.
static uint64_t pcm_consumed(int sample_rate, int interval_ms, bool jitter) {
    Session session;
    assert(session.interval(20, interval_ms == 10, false) == uint64_t(interval_ms));
    uint64_t last_us = 0, total = 0;
    const uint64_t bytes_per_period = uint64_t(sample_rate) * 6 / 50; // stereo 24 bit
    for (int tick = interval_ms; tick <= 1000; tick += interval_ms) {
        if (!session.forward()) continue;
        const uint64_t timestamp_us = 1'000'000 + tick * 1000
                + (jitter && tick != 20 && tick != 1000
                        ? ((tick / interval_ms) % 3) * 1000 : 0);
        const uint64_t elapsed_us = last_us == 0 ? 20'000 : timestamp_us - last_us;
        total += elapsed_us * bytes_per_period / 20'000;
        last_us = timestamp_us;
    }
    return total;
}

static Section section(const std::vector<uint8_t>& data, const char* wanted) {
    FileHeader eh;
    memcpy(&eh, data.data(), sizeof(eh));
    Section names;
    memcpy(&names, data.data() + eh.shoff + eh.shstrndx * sizeof(Section), sizeof(names));
    for (size_t i = 0; i < eh.shnum; ++i) {
        Section s;
        memcpy(&s, data.data() + eh.shoff + i * sizeof(s), sizeof(s));
        if (strcmp(reinterpret_cast<const char*>(data.data() + names.offset + s.name),
                wanted) == 0) return s;
    }
    assert(false);
    return {};
}

static void test_image(const std::vector<uint8_t>& data, const Binding& binding) {
    assert(binding.candidates == 1);
    auto changed = data;
    const auto text = section(data, ".text");
    const auto table = section(data, ".data.rel.ro");
    const auto interval_offset = text.offset + binding.callbacks[4] - text.address;
    // A changed 20/10 ms contract must not be mistaken for a supported compiler layout.
    changed[interval_offset + 8] ^= 0x10;
    assert(find_binding(changed.data(), changed.size()).candidates == 0);
    changed = data;
    const auto send_offset = text.offset + binding.callbacks[6] - text.address;
    const auto send_size = binding.callbacks[7] - binding.callbacks[6];
    bool mutated_elapsed = false;
    for (size_t i = 0; i + 4 <= send_size; i += 4) {
        if (melody::pacing::word(changed.data() + send_offset + i) == 0x5289c40bU) {
            changed[send_offset + i] ^= 0x10;
            mutated_elapsed = true;
        }
    }
    assert(mutated_elapsed);
    assert(find_binding(changed.data(), changed.size()).candidates == 0);
    changed = data;
    // Duplicate an otherwise valid interface. Ambiguity must be returned to the installer.
    assert(table.address != binding.table);
    memcpy(changed.data() + table.offset, binding.callbacks, sizeof(binding.callbacks));
    assert(find_binding(changed.data(), changed.size()).candidates == 2);
    assert(find_binding(data.data(), 63).candidates == 0);
    changed = data;
    FileHeader eh;
    memcpy(&eh, changed.data(), sizeof(eh));
    eh.shoff = UINT64_MAX;
    memcpy(changed.data(), &eh, sizeof(eh));
    assert(find_binding(changed.data(), changed.size()).candidates == 0);
}

int main(int argc, char** argv) {
    test_sessions();
    for (int sample_rate : {48'000, 96'000, 192'000}) {
        assert(pcm_consumed(sample_rate, 10, false) == uint64_t(sample_rate) * 6);
        assert(pcm_consumed(sample_rate, 20, false) == uint64_t(sample_rate) * 6);
        assert(pcm_consumed(sample_rate, 10, true) == pcm_consumed(sample_rate, 20, true));
    }
    int supported = 0;
    for (int i = 1; i < argc; ++i) {
        std::ifstream stream(argv[i], std::ios::binary);
        if (!stream) return 2;
        const std::vector<uint8_t> data((std::istreambuf_iterator<char>(stream)), {});
        const auto binding = find_binding(data.data(), data.size());
        std::cout << argv[i] << " candidates=" << binding.candidates;
        if (binding.candidates == 1) {
            std::cout << " table=0x" << std::hex << binding.table << std::dec;
            test_image(data, binding);
            ++supported;
        }
        std::cout << '\n';
    }
    if (argc > 1) assert(supported > 0);
    std::cout << "PASS sessions, startup PCM, elapsed scaling, drift/ambiguity/ELF rejection\n";
}
