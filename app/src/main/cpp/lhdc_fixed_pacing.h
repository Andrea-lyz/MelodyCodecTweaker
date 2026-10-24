#pragma once

#include <atomic>
#include <stdint.h>
#include <string.h>

namespace melody::pacing {

// Fixed-width ELF64 records keep the parser testable on the Windows development host.
struct FileHeader {
    uint8_t ident[16];
    uint16_t type, machine;
    uint32_t version;
    uint64_t entry, phoff, shoff;
    uint32_t flags;
    uint16_t ehsize, phentsize, phnum, shentsize, shnum, shstrndx;
};
struct Section {
    uint32_t name, type;
    uint64_t flags, address, offset, size;
    uint32_t link, info;
    uint64_t alignment, entry_size;
};
static_assert(sizeof(FileHeader) == 64 && sizeof(Section) == 64);

// The OEM packetizer scales PCM consumption by elapsed time / its original 20 ms period.
// Keep that arithmetic and the bitrate untouched. Defer the first 10 ms wakeup so the
// packetizer's initial 20 ms request still has a full stock startup batch available.
class Session {
public:
    uint64_t interval(uint64_t original_ms, bool quality, bool governor_enabled) {
        const bool fast = original_ms == 20 && quality && !governor_enabled;
        defer_first_.store(fast, std::memory_order_release);
        return fast ? 10 : original_ms;
    }

    bool forward(bool first_batch = true) {
        return !defer_first_.exchange(false, std::memory_order_acq_rel) || !first_batch;
    }

private:
    std::atomic<bool> defer_first_{false};
};

struct Binding {
    uint64_t table = 0;
    uint64_t callbacks[8] = {};
    uint64_t last_frame_us = 0;
    int candidates = 0;
};

inline bool contains(size_t size, uint64_t start, uint64_t length) {
    return start <= size && length <= size - start;
}

inline uint32_t word(const uint8_t* p) {
    uint32_t result;
    memcpy(&result, p, sizeof(result));
    return result;
}

// This deliberately recognizes a complete, bounded function contract, including the
// stock 20/10 ms choice and uint64_t return. Unknown compiler layouts stay on OEM pacing.
inline bool interval_contract(const uint8_t* code, size_t size) {
    size_t i = size >= 4 && word(code) == 0xd503245fU ? 4 : 0; // optional BTI c
    if (size - i < 28) return false;
    return (word(code + i) & 0x9f00001fU) == 0x90000008U // adrp x8
            && word(code + i + 4) == 0x52800289U        // mov w9, #20
            && (word(code + i + 8) & 0xffc003ffU) == 0x39400108U // ldrb w8,[x8,#imm]
            && word(code + i + 12) == 0x7100011fU       // cmp w8, #0
            && word(code + i + 16) == 0x52800148U       // mov w8, #10
            && word(code + i + 20) == 0x9a880120U       // csel x0,x9,x8,eq
            && word(code + i + 24) == 0xd65f03c0U;      // ret
}

inline bool references_string(
        const uint8_t* code, size_t size, uint64_t address, uint64_t target) {
    for (size_t i = 0; i + 8 <= size; i += 4) {
        const uint32_t a = word(code + i);
        const uint32_t b = word(code + i + 4);
        if ((a & 0x9f000000U) != 0x90000000U
                || (b & 0xffc00000U) != 0x91000000U
                || ((b >> 5) & 31U) != (a & 31U)) continue;
        int64_t pages = static_cast<int64_t>(((a >> 5) & 0x7ffffU) << 2)
                | ((a >> 29) & 3U);
        if ((pages & (1 << 20)) != 0) pages -= 1 << 21;
        const uint64_t resolved = ((address + i) & ~0xfffULL) + pages * 4096
                + ((b >> 10) & 0xfffU);
        if (resolved == target) return true;
    }
    return false;
}

inline bool elapsed_pcm_contract(
        const uint8_t* code, size_t size, uint64_t address, uint64_t* last_frame_us) {
    // Verify the stock first-tick fallback and subsequent elapsed-time scaling. Merely
    // finding a 20/10 ms getter does not prove a packetizer is safe at a shorter cadence.
    for (size_t i = 8; i + 60 <= size; i += 4) {
        const uint32_t adrp = word(code + i - 8), add = word(code + i - 4);
        if ((adrp & 0x9f00001fU) == 0x90000008U
                && (add & 0xffc003ffU) == 0x91000108U
                && word(code + i) == 0x5284e20aU      // mov w10, #10000
                && word(code + i + 8) == 0xf940150cU // ldr x12,[x8,#0x28]
                && word(code + i + 12) == 0x5289c40bU // mov w11, #20000
                && word(code + i + 20) == 0xf9001514U // str x20,[x8,#0x28]
                && word(code + i + 28) == 0x1a8a0169U // csel w9,w11,w10,eq
                && word(code + i + 32) == 0x4b0c028aU // sub w10,w20,w12
                && word(code + i + 44) == 0x1a8a012aU // csel w10,w9,w10,eq
                && word(code + i + 48) == 0x1b0b7d4aU // mul w10,w10,w11
                && word(code + i + 56) == 0x1ac90949U) { // udiv w9,w10,w9
            int64_t pages = static_cast<int64_t>(((adrp >> 5) & 0x7ffffU) << 2)
                    | ((adrp >> 29) & 3U);
            if ((pages & (1 << 20)) != 0) pages -= 1 << 21;
            *last_frame_us = ((address + i - 8) & ~0xfffULL) + pages * 4096
                    + ((add >> 10) & 0xfffU) + 0x28;
            return true;
        }
    }
    return false;
}

// On-disk RELR callbacks retain their link-time addresses. Inspect only .data.rel.ro;
// neither GOT entries nor writable vendor dlsym slots are candidates for this interface.
// The live installer subsequently checks all eight relocated pointers before writing.
inline Binding find_binding(const uint8_t* bytes, size_t size) {
    Binding result;
    if (!contains(size, 0, sizeof(FileHeader))) return result;
    FileHeader eh;
    memcpy(&eh, bytes, sizeof(eh));
    if (memcmp(eh.ident, "\177ELF", 4) != 0
            || eh.ident[4] != 2 || eh.ident[5] != 1 // ELF64, little endian
            || eh.machine != 183 || eh.shentsize != sizeof(Section) // AArch64
            || eh.shnum == 0 || eh.shstrndx >= eh.shnum
            || !contains(size, eh.shoff, uint64_t(eh.shnum) * sizeof(Section))) {
        return result;
    }
    auto section = [&](size_t index) {
        Section value;
        memcpy(&value, bytes + eh.shoff + index * sizeof(value), sizeof(value));
        return value;
    };
    const Section names = section(eh.shstrndx);
    if (!contains(size, names.offset, names.size)) return result;
    Section text{}, rodata{}, table{}, bss{};
    for (size_t i = 0; i < eh.shnum; ++i) {
        const Section s = section(i);
        if (s.name >= names.size) continue;
        const char* name = reinterpret_cast<const char*>(bytes + names.offset + s.name);
        if (memchr(name, 0, names.size - s.name) == nullptr) continue;
        if (strcmp(name, ".bss") == 0 && s.type == 8 && (s.flags & 3U) == 3U) bss = s;
        if (!contains(size, s.offset, s.size)) continue;
        if (strcmp(name, ".text") == 0 && (s.flags & 4U)) text = s;
        if (strcmp(name, ".rodata") == 0) rodata = s;
        if (strcmp(name, ".data.rel.ro") == 0) table = s;
    }
    if (text.size == 0 || rodata.size == 0 || table.size < 64) return result;
    constexpr char anchor[] = "a2dp_lhdcV5_encode_frames";
    uint64_t anchor_address = 0;
    int anchor_count = 0;
    for (size_t i = 0; i + sizeof(anchor) <= rodata.size; ++i) {
        if (memcmp(bytes + rodata.offset + i, anchor, sizeof(anchor)) == 0) {
            anchor_address = rodata.address + i;
            ++anchor_count;
        }
    }
    if (anchor_count != 1) return result;
    auto code = [&](uint64_t address, uint64_t length) -> const uint8_t* {
        if (address < text.address
                || !contains(text.size, address - text.address, length)) return nullptr;
        return bytes + text.offset + address - text.address;
    };
    for (size_t i = 0; i + 64 <= table.size; i += 8) {
        uint64_t callbacks[8];
        memcpy(callbacks, bytes + table.offset + i, sizeof(callbacks));
        const uint8_t* interval = code(callbacks[4], 32);
        if (interval == nullptr || !interval_contract(interval, 32)) continue;
        bool valid = true;
        for (size_t j = 0; j < 8; ++j) {
            if ((callbacks[j] & 3U) != 0 || code(callbacks[j], 4) == nullptr) valid = false;
            for (size_t k = 0; k < j; ++k) {
                if (callbacks[k] == callbacks[j]) valid = false;
            }
        }
        // The next callback bounds the send_frames body in the supported compiler layout.
        const uint64_t send_size = callbacks[7] - callbacks[6];
        if (!valid || callbacks[7] <= callbacks[6] || send_size > 16 * 1024) continue;
        const uint8_t* send = code(callbacks[6], send_size);
        uint64_t last_frame_us = 0;
        if (send == nullptr
                || !references_string(send, send_size, callbacks[6], anchor_address)
                || !elapsed_pcm_contract(send, send_size, callbacks[6], &last_frame_us)
                || last_frame_us < bss.address || (last_frame_us & 7U) != 0
                || !contains(bss.size, last_frame_us - bss.address, 8)) continue;
        ++result.candidates;
        result.table = table.address + i;
        result.last_frame_us = last_frame_us;
        memcpy(result.callbacks, callbacks, sizeof(callbacks));
    }
    return result;
}

} // namespace melody::pacing
