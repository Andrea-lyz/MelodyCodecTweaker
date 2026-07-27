#include <errno.h>
#include <jni.h>
#include <stdint.h>
#include <sys/mman.h>
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
