#include <errno.h>
#include <android/log.h>
#include <atomic>
#include <dlfcn.h>
#include <jni.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
#include <time.h>
#include <unistd.h>

namespace {

enum PatchResultCode : jint {
    kPatchOk = 0,
    kPatchAlreadyApplied = 1,
    kErrorInvalidArgument = -1001,
    kErrorUnsupportedArchitecture = -1002,
    kErrorUnexpectedInstruction = -1003,
    kErrorWritableProtectionBase = -2000,
    kErrorRestoreAfterNoWriteBase = -3000,
    kErrorVerifyFailed = -4001,
    kErrorRestoreFailedRolledBackBase = -5000,
    kErrorRestoreFailedDirtyBase = -6000,
    kErrorRollbackVerifyFailedBase = -7000,
};

#if defined(__aarch64__)
int current_errno() {
    return errno == 0 ? 1 : errno;
}

void flush_instruction_cache(uint32_t* instruction) {
    // The data-side atomic store is not sufficient for self-modifying ARM64 code. Clang emits
    // the platform cache-maintenance sequence behind this builtin.
    auto* begin = reinterpret_cast<char*>(instruction);
    __atomic_thread_fence(__ATOMIC_SEQ_CST);
    __builtin___clear_cache(begin, begin + sizeof(*instruction));
    __atomic_thread_fence(__ATOMIC_SEQ_CST);
}

bool restore_protection(void* page, size_t page_size, int original_protection) {
    return mprotect(page, page_size, original_protection) == 0;
}
#endif

}  // namespace

namespace {

constexpr const char* kGovernorTag = "MelodyLhdcGov";
#if defined(__aarch64__)
constexpr const char* kBluetoothLibrary = "libbluetooth_jni.so";
constexpr const char* kEncoderLibrary = "liblhdcv5BT_enc.so";
constexpr const char* kAdjustSymbol = "lhdcv5BT_adjust_bitrate";
constexpr const char* kSetTargetSymbol = "lhdcv5BT_set_target_bitrate_inx";
#endif

constexpr int kPolicyConnection = 6;
constexpr int kPolicyQuality = 8;
constexpr int kPolicyAdaptive = 9;

constexpr uint32_t kRate400 = 5;
constexpr uint32_t kRate500 = 6;
constexpr uint32_t kRate900 = 7;
constexpr uint32_t kRate1000 = 8;
constexpr uint32_t kDefaultQueueCapacity = 45;

using AdjustBitrateFn = int32_t (*)(void*, uint32_t);
using SetTargetBitrateFn = int32_t (*)(void*, uint32_t);

std::atomic<AdjustBitrateFn> g_real_adjust{nullptr};
std::atomic<SetTargetBitrateFn> g_set_target{nullptr};
std::atomic<int> g_policy{kPolicyAdaptive};
std::atomic<uint64_t> g_policy_set_ms{0};
std::atomic<uint32_t> g_policy_epoch{1};
std::atomic<uint64_t> g_choppy_sequence{0};
std::atomic<int> g_choppy_level{0};

// Encoder-thread-only state. The queue callback is serialized by the A2DP source encoder.
void* g_encoder_handle = nullptr;
uint32_t g_seen_policy_epoch = 0;
uint64_t g_seen_choppy_sequence = 0;
uint32_t g_current_rate = kRate1000;
uint32_t g_queue_capacity = kDefaultQueueCapacity;
uint64_t g_high_since_ms = 0;
uint64_t g_critical_since_ms = 0;
uint64_t g_low_since_ms = 0;
uint64_t g_last_transition_ms = 0;
uint64_t g_last_congestion_ms = 0;
uint64_t g_last_upgrade_ms = 0;
uint64_t g_upgrade_backoff_until_ms = 0;
uint64_t g_choppy_window_start_ms = 0;
uint32_t g_choppy_count = 0;

uint64_t monotonic_ms() {
    timespec value{};
    if (clock_gettime(CLOCK_MONOTONIC, &value) != 0) return 0;
    return static_cast<uint64_t>(value.tv_sec) * 1000ULL
            + static_cast<uint64_t>(value.tv_nsec / 1000000L);
}

int bitrate_for_rate(uint32_t rate) {
    switch (rate) {
        case kRate1000: return 1000;
        case kRate900: return 900;
        case kRate500: return 500;
        case kRate400: return 400;
        default: return 0;
    }
}

void governor_log_transition(
        const char* reason, uint32_t from, uint32_t to, uint32_t queue, int result) {
    __android_log_print(ANDROID_LOG_INFO, kGovernorTag,
            "evt=transition reason=%s from=%d to=%d queue=%u capacity=%u result=%d",
            reason, bitrate_for_rate(from), bitrate_for_rate(to), queue, g_queue_capacity, result);
}

void reset_encoder_state(void* handle, uint32_t epoch, uint64_t now) {
    g_encoder_handle = handle;
    g_seen_policy_epoch = epoch;
    g_seen_choppy_sequence = g_choppy_sequence.load(std::memory_order_acquire);
    // Unknown until set_target_bitrate_inx succeeds. Starting at 1000 here would suppress the
    // first real write and leave the encoder at the OEM ABR's previous (often very low) target.
    g_current_rate = 0;
    g_queue_capacity = kDefaultQueueCapacity;
    g_high_since_ms = 0;
    g_critical_since_ms = 0;
    g_low_since_ms = 0;
    g_last_transition_ms = 0;
    g_last_congestion_ms = now;
    g_last_upgrade_ms = 0;
    g_upgrade_backoff_until_ms = 0;
    g_choppy_window_start_ms = now;
    g_choppy_count = 0;
}

bool set_rate(uint32_t target, const char* reason, uint32_t queue, uint64_t now, bool upgrade) {
    if (target == g_current_rate) return true;
    SetTargetBitrateFn setter = g_set_target.load(std::memory_order_acquire);
    if (setter == nullptr || g_encoder_handle == nullptr) return false;
    const uint32_t previous = g_current_rate;
    const int32_t result = setter(g_encoder_handle, target);
    governor_log_transition(reason, previous, target, queue, result);
    if (result != 0) return false;
    g_current_rate = target;
    g_last_transition_ms = now;
    g_high_since_ms = 0;
    g_critical_since_ms = 0;
    g_low_since_ms = 0;
    if (upgrade) {
        g_last_upgrade_ms = now;
    } else {
        g_last_upgrade_ms = 0;
    }
    return true;
}

void note_congestion(uint64_t now) {
    g_last_congestion_ms = now;
    g_low_since_ms = 0;
    if (g_last_upgrade_ms != 0 && now - g_last_upgrade_ms <= 10'000ULL) {
        g_upgrade_backoff_until_ms = now + 300'000ULL;
    }
}

void apply_choppy_protection(uint32_t queue, uint64_t now) {
    const uint64_t sequence = g_choppy_sequence.load(std::memory_order_acquire);
    if (sequence == g_seen_choppy_sequence) return;
    g_seen_choppy_sequence = sequence;
    if (g_choppy_level.load(std::memory_order_acquire) <= 0) return;
    if (g_choppy_window_start_ms == 0 || now - g_choppy_window_start_ms > 5'000ULL) {
        g_choppy_window_start_ms = now;
        g_choppy_count = 0;
    }
    ++g_choppy_count;
    note_congestion(now);
    uint32_t target = g_current_rate;
    if (g_choppy_count >= 3) {
        target = kRate400;
    } else if (g_current_rate >= kRate900) {
        target = g_choppy_count == 1 ? kRate900 : kRate500;
    } else if (g_current_rate == kRate500 && g_choppy_count >= 2) {
        target = kRate400;
    }
    if (target != g_current_rate) {
        set_rate(target, "remote_choppy", queue, now, false);
    }
}

void quality_governor_sample(void* handle, uint32_t queue) {
    const uint64_t now = monotonic_ms();
    const uint32_t epoch = g_policy_epoch.load(std::memory_order_acquire);
    if (handle != g_encoder_handle || epoch != g_seen_policy_epoch) {
        reset_encoder_state(handle, epoch, now);
        set_rate(kRate1000, "quality_start", queue, now, true);
    }
    if (g_current_rate == 0) {
        set_rate(kRate1000, "quality_start_retry", queue, now, true);
    }
    if (queue > g_queue_capacity) g_queue_capacity = queue;
    apply_choppy_protection(queue, now);

    const uint64_t occupancy = static_cast<uint64_t>(queue) * 100ULL;
    const uint64_t capacity = static_cast<uint64_t>(g_queue_capacity);
    const bool full = queue >= g_queue_capacity;
    const bool critical = occupancy >= capacity * 85ULL;
    const bool high = occupancy >= capacity * 65ULL;
    const bool low = occupancy <= capacity * 25ULL;

    if (full) {
        note_congestion(now);
        const uint32_t target = g_current_rate >= kRate900 ? kRate500 : kRate400;
        set_rate(target, "queue_full", queue, now, false);
        return;
    }

    if (high) {
        note_congestion(now);
        if (g_high_since_ms == 0) g_high_since_ms = now;
    } else {
        g_high_since_ms = 0;
    }
    if (critical) {
        if (g_critical_since_ms == 0) g_critical_since_ms = now;
    } else {
        g_critical_since_ms = 0;
    }

    const bool transition_hold_elapsed = g_last_transition_ms == 0
            || now - g_last_transition_ms >= 700ULL;
    if (transition_hold_elapsed
            && high
            && g_last_upgrade_ms != 0
            && now - g_last_upgrade_ms <= 10'000ULL
            && g_current_rate >= kRate900) {
        set_rate(kRate500, "upgrade_failed", queue, now, false);
        return;
    }
    if (transition_hold_elapsed
            && g_critical_since_ms != 0
            && now - g_critical_since_ms >= 300ULL
            && g_current_rate >= kRate900) {
        set_rate(kRate500, "queue_critical", queue, now, false);
        return;
    }
    if (transition_hold_elapsed
            && g_high_since_ms != 0
            && now - g_high_since_ms >= 200ULL
            && g_current_rate == kRate1000) {
        set_rate(kRate900, "queue_rising", queue, now, false);
        return;
    }

    if (!low) {
        g_low_since_ms = 0;
        return;
    }
    if (g_low_since_ms == 0) g_low_since_ms = now;
    if (now < g_upgrade_backoff_until_ms) return;

    uint64_t required_stable_ms = 0;
    uint32_t target = g_current_rate;
    if (g_current_rate == kRate400) {
        required_stable_ms = 20'000ULL;
        target = kRate500;
    } else if (g_current_rate == kRate500) {
        required_stable_ms = 60'000ULL;
        target = kRate900;
    } else if (g_current_rate == kRate900) {
        required_stable_ms = 120'000ULL;
        target = kRate1000;
    }
    if (required_stable_ms == 0) return;
    const uint64_t stable_from = g_low_since_ms > g_last_congestion_ms
            ? g_low_since_ms : g_last_congestion_ms;
    if (now - stable_from >= required_stable_ms) {
        set_rate(target, "stable_upgrade", queue, now, true);
    }
}

extern "C" int32_t melody_lhdc_adjust_bitrate(void* handle, uint32_t queue) {
    int policy = g_policy.load(std::memory_order_acquire);
    if (policy == kPolicyQuality && handle != g_encoder_handle) {
        const uint64_t now = monotonic_ms();
        const uint64_t set_at = g_policy_set_ms.load(std::memory_order_acquire);
        // A remembered replay sends the policy immediately before codec setup. A much later new
        // handle belongs to a new connection and must not inherit another headset's quality mode.
        if (set_at == 0 || now - set_at > 30'000ULL) {
            policy = kPolicyAdaptive;
            g_policy.store(policy, std::memory_order_release);
            __android_log_print(ANDROID_LOG_INFO, kGovernorTag,
                    "evt=policy.expired reason=new_handle");
        }
    }
    if (policy == kPolicyQuality) {
        quality_governor_sample(handle, queue);
        return 0;
    }
    AdjustBitrateFn original = g_real_adjust.load(std::memory_order_acquire);
    return original != nullptr ? original(handle, queue) : 0;
}

#if defined(__aarch64__)
bool ends_with(const char* value, const char* suffix) {
    if (value == nullptr || suffix == nullptr) return false;
    const size_t value_length = strlen(value);
    const size_t suffix_length = strlen(suffix);
    return value_length >= suffix_length
            && strcmp(value + value_length - suffix_length, suffix) == 0;
}

int mapping_protection(uintptr_t address) {
    FILE* maps = fopen("/proc/self/maps", "re");
    if (maps == nullptr) return 0;
    char line[512];
    int protection = 0;
    while (fgets(line, sizeof(line), maps) != nullptr) {
        unsigned long long start = 0;
        unsigned long long end = 0;
        char perms[5] = {};
        if (sscanf(line, "%llx-%llx %4s", &start, &end, perms) != 3) continue;
        if (address < start || address >= end) continue;
        if (perms[0] == 'r') protection |= PROT_READ;
        if (perms[1] == 'w') protection |= PROT_WRITE;
        if (perms[2] == 'x') protection |= PROT_EXEC;
        break;
    }
    fclose(maps);
    return protection;
}

bool replace_writable_pointer(void** slot, void* expected, void* replacement) {
    if (slot == nullptr || replacement == nullptr) return false;
    void* current = __atomic_load_n(slot, __ATOMIC_ACQUIRE);
    if (current == replacement) return true;
    if (expected != nullptr && current != expected) return false;
    const uintptr_t address = reinterpret_cast<uintptr_t>(slot);
    const int original_protection = mapping_protection(address);
    if ((original_protection & (PROT_READ | PROT_WRITE)) != (PROT_READ | PROT_WRITE)) {
        return false;
    }
    return __atomic_compare_exchange_n(
            slot, &current, replacement, false, __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE);
}
#endif

int hook_existing_encoder_pointer() {
#if !defined(__aarch64__)
    return -2;
#else
    void* encoder = dlopen(kEncoderLibrary, RTLD_NOW | RTLD_NOLOAD);
    if (encoder == nullptr) return -3;
    void* adjust = dlsym(encoder, kAdjustSymbol);
    void* target = dlsym(encoder, kSetTargetSymbol);
    if (adjust == nullptr || target == nullptr) {
        dlclose(encoder);
        return -4;
    }

    // Fail closed unless the already-resolved adjust callback has one unique writable owner.
    // We never touch dlsym/PLT/GOT or any Bluetooth startup-time symbol resolution.
    void** candidate = nullptr;
    int candidate_count = 0;
    FILE* maps = fopen("/proc/self/maps", "re");
    char line[512];
    while (maps != nullptr && fgets(line, sizeof(line), maps) != nullptr) {
        unsigned long long start = 0;
        unsigned long long end = 0;
        char perms[5] = {};
        char path[384] = {};
        if (sscanf(line, "%llx-%llx %4s %*s %*s %*s %383s",
                &start, &end, perms, path) != 4) continue;
        if (perms[0] != 'r' || perms[1] != 'w' || !ends_with(path, kBluetoothLibrary)) {
            continue;
        }
        uintptr_t begin = (static_cast<uintptr_t>(start) + sizeof(void*) - 1U)
                & ~(sizeof(void*) - 1U);
        for (uintptr_t address = begin;
                address + sizeof(void*) <= static_cast<uintptr_t>(end);
                address += sizeof(void*)) {
            auto** slot = reinterpret_cast<void**>(address);
            if (__atomic_load_n(slot, __ATOMIC_ACQUIRE) == adjust) {
                candidate = slot;
                ++candidate_count;
            }
        }
    }
    if (maps != nullptr) fclose(maps);

    int result = -5;
    if (candidate_count == 1
            && replace_writable_pointer(candidate, adjust,
                    reinterpret_cast<void*>(&melody_lhdc_adjust_bitrate))) {
        g_real_adjust.store(reinterpret_cast<AdjustBitrateFn>(adjust),
                std::memory_order_release);
        g_set_target.store(reinterpret_cast<SetTargetBitrateFn>(target),
                std::memory_order_release);
        result = 1;
    } else if (candidate_count > 1) {
        result = -6;
    }
    dlclose(encoder);
    __android_log_print(ANDROID_LOG_INFO, kGovernorTag,
            "evt=encoder.scan candidates=%d result=%d adjust=%p target=%p",
            candidate_count, result, adjust, target);
    return result;
#endif
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_xyz_melodylsp_codec_system_NativeLhdcMemoryPatch_nativeInstallGovernor(
        JNIEnv*, jclass) {
    const int result = hook_existing_encoder_pointer();
    __android_log_print(ANDROID_LOG_INFO, kGovernorTag,
            "evt=hook.install mode=resolved_pointer result=%d", result);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_xyz_melodylsp_codec_system_NativeLhdcMemoryPatch_nativeSetGovernorPolicy(
        JNIEnv*, jclass, jint policy) {
    int normalized = policy;
    if (normalized != kPolicyConnection
            && normalized != kPolicyQuality
            && normalized != kPolicyAdaptive) {
        normalized = kPolicyAdaptive;
    }
    g_policy.store(normalized, std::memory_order_release);
    g_policy_set_ms.store(monotonic_ms(), std::memory_order_release);
    const uint32_t epoch = g_policy_epoch.fetch_add(1, std::memory_order_acq_rel) + 1;
    __android_log_print(ANDROID_LOG_INFO, kGovernorTag,
            "evt=policy value=%d epoch=%u", normalized, epoch);
}

extern "C" JNIEXPORT void JNICALL
Java_xyz_melodylsp_codec_system_NativeLhdcMemoryPatch_nativeReportChoppy(
        JNIEnv*, jclass, jint level) {
    g_choppy_level.store(level, std::memory_order_release);
    g_choppy_sequence.fetch_add(1, std::memory_order_acq_rel);
}

extern "C" JNIEXPORT jint JNICALL
Java_xyz_melodylsp_codec_system_NativeLhdcMemoryPatch_nativePatchInstruction(
        JNIEnv* /* env */,
        jclass /* clazz */,
        jlong address,
        jint expected_instruction,
        jint replacement_instruction,
        jint original_protection) {
#if !defined(__aarch64__)
    (void) address;
    (void) expected_instruction;
    (void) replacement_instruction;
    (void) original_protection;
    return kErrorUnsupportedArchitecture;
#else
    const auto raw_address = static_cast<uintptr_t>(address);
    if (raw_address == 0 || (raw_address & (alignof(uint32_t) - 1U)) != 0) {
        return kErrorInvalidArgument;
    }
    if ((original_protection & ~(PROT_READ | PROT_WRITE | PROT_EXEC)) != 0
            || (original_protection & PROT_READ) == 0
            || (original_protection & PROT_EXEC) == 0) {
        return kErrorInvalidArgument;
    }

    const long system_page_size = sysconf(_SC_PAGESIZE);
    if (system_page_size <= 0
            || (system_page_size & (system_page_size - 1L)) != 0) {
        return kErrorInvalidArgument;
    }
    const size_t page_size = static_cast<size_t>(system_page_size);
    auto* instruction = reinterpret_cast<uint32_t*>(raw_address);
    auto* page = reinterpret_cast<void*>(raw_address & ~(page_size - 1U));
    const uint32_t expected = static_cast<uint32_t>(expected_instruction);
    const uint32_t replacement = static_cast<uint32_t>(replacement_instruction);

    // Avoid changing page permissions for repeat calls and reject stale scan results before the
    // protection window opens.
    uint32_t current = __atomic_load_n(instruction, __ATOMIC_ACQUIRE);
    if (current == replacement) return kPatchAlreadyApplied;
    if (current != expected) return kErrorUnexpectedInstruction;

    // Keep executable mappings executable. If the kernel enforces W^X and rejects RWX, fail
    // closed: temporarily dropping PROT_EXEC could crash another Bluetooth thread executing the
    // same page.
    const int writable_protection = original_protection | PROT_WRITE;
    if (mprotect(page, page_size, writable_protection) != 0) {
        return kErrorWritableProtectionBase - current_errno();
    }

    // Re-check after mprotect in case another caller won the race. Every exit from this point
    // attempts to restore the exact permissions parsed from /proc/self/maps.
    current = __atomic_load_n(instruction, __ATOMIC_ACQUIRE);
    if (current == replacement || current != expected) {
        const jint result = current == replacement
                ? kPatchAlreadyApplied
                : kErrorUnexpectedInstruction;
        if (!restore_protection(page, page_size, original_protection)) {
            const int first_restore_errno = current_errno();
            if (!restore_protection(page, page_size, original_protection)) {
                return kErrorRestoreFailedDirtyBase - current_errno();
            }
            return kErrorRestoreAfterNoWriteBase - first_restore_errno;
        }
        return result;
    }

    // An aligned 32-bit ARM64 instruction store is single-copy atomic. Flush the I-cache before
    // any thread is allowed to execute the replacement from a stale cache line.
    __atomic_store_n(instruction, replacement, __ATOMIC_RELEASE);
    flush_instruction_cache(instruction);
    if (__atomic_load_n(instruction, __ATOMIC_ACQUIRE) != replacement) {
        __atomic_store_n(instruction, expected, __ATOMIC_RELEASE);
        flush_instruction_cache(instruction);
        const bool rollback_ok = __atomic_load_n(instruction, __ATOMIC_ACQUIRE) == expected;
        bool restored = restore_protection(page, page_size, original_protection);
        if (!restored) {
            restored = restore_protection(page, page_size, original_protection);
        }
        if (!rollback_ok) return kErrorRollbackVerifyFailedBase - (restored ? 0 : current_errno());
        return restored ? kErrorVerifyFailed : kErrorRestoreFailedDirtyBase - current_errno();
    }

    if (restore_protection(page, page_size, original_protection)) {
        return kPatchOk;
    }

    // Do not report success with a writable executable library page. Roll the instruction back
    // while it is still writable, flush again, and make one final protection-restoration attempt.
    const int first_restore_errno = current_errno();
    __atomic_store_n(instruction, expected, __ATOMIC_RELEASE);
    flush_instruction_cache(instruction);
    const bool rollback_ok = __atomic_load_n(instruction, __ATOMIC_ACQUIRE) == expected;
    if (restore_protection(page, page_size, original_protection)) {
        return rollback_ok
                ? kErrorRestoreFailedRolledBackBase - first_restore_errno
                : kErrorRollbackVerifyFailedBase - first_restore_errno;
    }
    const int second_restore_errno = current_errno();
    return rollback_ok
            ? kErrorRestoreFailedDirtyBase - second_restore_errno
            : kErrorRollbackVerifyFailedBase - second_restore_errno;
#endif
}
