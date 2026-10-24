// Isolated Android probe: maps a supplied library privately and exercises the production
// installer. It never attaches to, writes to, or loads code in the real Bluetooth process.
#include "../../main/cpp/native_lhdc_patch.cpp"

#include <assert.h>

int main(int argc, char** argv) {
    if (argc != 2) return 2;
    int fd = open(argv[1], O_RDONLY | O_CLOEXEC);
    if (fd < 0) return 3;
    struct stat st {};
    assert(fstat(fd, &st) == 0 && st.st_size > 0);
    const size_t size = static_cast<size_t>(st.st_size);
    auto* image = static_cast<uint8_t*>(mmap(
            nullptr, size, PROT_READ, MAP_PRIVATE, fd, 0));
    close(fd);
    assert(image != MAP_FAILED);
    const auto binding = melody::pacing::find_binding(image, size);
    assert(binding.candidates == 1);
    const auto* eh = reinterpret_cast<const Elf64_Ehdr*>(image);
    const auto* ph = reinterpret_cast<const Elf64_Phdr*>(image + eh->e_phoff);
    // This probe uses a flat private map. Reject rather than guess a non-flat ELF image.
    for (size_t i = 0; i < eh->e_phnum; ++i) {
        if (ph[i].p_type == PT_LOAD) assert(ph[i].p_offset == ph[i].p_vaddr);
    }
    auto** table = reinterpret_cast<void**>(image + binding.table);
    const size_t page_size = static_cast<size_t>(sysconf(_SC_PAGESIZE));
    auto protect = [&](uint64_t start, uint64_t length, int prot) {
        const uint64_t begin = start & ~(page_size - 1ULL);
        const uint64_t end = (start + length + page_size - 1ULL) & ~(page_size - 1ULL);
        assert(end <= (size + page_size - 1ULL) / page_size * page_size);
        if (mprotect(image + begin, end - begin, prot) != 0) {
            fprintf(stderr, "mprotect start=0x%llx length=0x%llx prot=%d errno=%d\n",
                    static_cast<unsigned long long>(begin),
                    static_cast<unsigned long long>(end - begin), prot, errno);
            _exit(4);
        }
    };
    const auto* sections = reinterpret_cast<const Elf64_Shdr*>(image + eh->e_shoff);
    // Resolve the getter's low-latency flag so its original function safely returns 20 ms.
    const uint64_t function = binding.callbacks[4];
    const uint8_t* code = image + function;
    const size_t bti = melody::pacing::word(code) == 0xd503245fU ? 4 : 0;
    const uint32_t a = melody::pacing::word(code + bti);
    const uint32_t load = melody::pacing::word(code + bti + 8);
    int64_t pages = int64_t(((a >> 5) & 0x7ffffU) << 2) | ((a >> 29) & 3U);
    if (pages & (1 << 20)) pages -= 1 << 21;
    const uint64_t flag = ((function + bti) & ~0xfffULL) + pages * 4096
            + ((load >> 10) & 0xfffU);
    assert(flag < size && binding.last_frame_us + 8 <= size);
    for (size_t i = 0; i < eh->e_shnum; ++i) {
        const auto s = sections[i];
        if (s.sh_flags & SHF_EXECINSTR) protect(s.sh_addr, s.sh_size, PROT_READ | PROT_EXEC);
        if (s.sh_type == SHT_NOBITS && (s.sh_flags & SHF_WRITE)) {
            protect(s.sh_addr, s.sh_size, PROT_READ | PROT_WRITE);
        }
    }
    // Split executable and data VMAs before writing data, just as the dynamic linker does;
    // making an already modified file mapping executable would require SELinux execmod.
    protect(binding.table, 64, PROT_READ | PROT_WRITE);
    for (size_t i = 0; i < 8; ++i) table[i] = image + binding.callbacks[i];
    protect(binding.table, 64, PROT_READ);
    image[flag] = 0;
    memset(image + binding.last_frame_us, 0, 8);
    const int result = install_fixed_quality_pacing();
    printf("install=%d table=0x%llx last_frame_us=0x%llx protection=%d\n", result,
            static_cast<unsigned long long>(binding.table),
            static_cast<unsigned long long>(binding.last_frame_us),
            mapping_protection(reinterpret_cast<uintptr_t>(table)));
    assert(result == 2);
    assert(mapping_protection(reinterpret_cast<uintptr_t>(table)) == PROT_READ);
    assert(table[4] == reinterpret_cast<void*>(&melody_lhdc_encoder_interval));
    assert(table[6] == reinterpret_cast<void*>(&melody_lhdc_send_frames));
    auto interval = reinterpret_cast<EncoderIntervalFn>(table[4]);
    auto send = reinterpret_cast<SendFramesFn>(table[6]);
    g_policy.store(kPolicyQuality);
    g_governor_experimental_enabled.store(false);
    assert(interval() == 10);
    send(1'010'000); // first tick is deferred; vendor send_frames is never executed
    assert(*reinterpret_cast<uint64_t*>(image + binding.last_frame_us) == 0);
    g_policy.store(kPolicyAdaptive);
    assert(interval() == 20);
    g_policy.store(kPolicyQuality);
    g_governor_experimental_enabled.store(true);
    assert(interval() == 20);
    assert(install_fixed_quality_pacing() == 1);
    assert(mapping_protection(reinterpret_cast<uintptr_t>(table)) == PROT_READ);
    puts("PASS production install, relocated owners, RO restoration, 10/20ms selection, first batch");
    munmap(image, size);
    return 0;
}
