#pragma once

// 符号解析不走 dlopen/dlsym (linker namespace 隔离):
// 读 /proc/self/maps 找基址 + 解析已加载 ELF 的 .dynsym 得到符号绝对地址。
#include <elf.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

namespace feishukit_elf {

// 在 /proc/self/maps 里找 so 的加载基址: 取【文件偏移=0 且以 ELF magic 开头】的映射
inline uintptr_t find_base(const char *name) {
    if (!name || !*name) return 0;
    FILE *f = fopen("/proc/self/maps", "r");
    if (!f) return 0;
    char line[1024];
    uintptr_t base = 0;
    while (fgets(line, sizeof(line), f)) {
        if (!strstr(line, name)) continue;
        uintptr_t start = 0, end = 0;
        char perms[8] = {0};
        unsigned long off = 1;
        if (sscanf(line, "%lx-%lx %7s %lx", &start, &end, perms, &off) < 4) continue;
        if (off != 0 || !start || perms[0] != 'r') continue;
        const unsigned char *m = (const unsigned char *) start;
        if (m[0] == 0x7f && m[1] == 'E' && m[2] == 'L' && m[3] == 'F') {
            base = start;
            break;
        }
    }
    fclose(f);
    return base;
}

// 用 GNU_HASH / DT_HASH 求动态符号数量 (与 dynsym/dynstr 内存布局无关)
inline size_t dynsym_count(uintptr_t gnu_hash, uintptr_t sysv_hash) {
    if (sysv_hash) {
        uint32_t *h = (uint32_t *) sysv_hash;
        return h[1];                                       // nchain == 符号总数
    }
    if (gnu_hash) {
        uint32_t *h = (uint32_t *) gnu_hash;
        uint32_t nbuckets = h[0], symoffset = h[1], bloom_size = h[2];
        uint32_t *buckets = (uint32_t *) ((uint8_t *) (h + 4) + (size_t) bloom_size * sizeof(uint64_t));
        uint32_t *chain = buckets + nbuckets;
        uint32_t last = 0;
        for (uint32_t i = 0; i < nbuckets; i++) {
            if (buckets[i] > last) last = buckets[i];
        }
        if (last < symoffset) return symoffset;
        while (!(chain[last - symoffset] & 1)) last++;
        return last + 1;
    }
    return 0;
}

// 解析已加载 ELF 的 .dynsym, 返回符号绝对地址
inline void *resolve(uintptr_t base, const char *sym) {
    if (!base || !sym || !*sym) return nullptr;
    Elf64_Ehdr *eh = (Elf64_Ehdr *) base;
    Elf64_Phdr *ph = (Elf64_Phdr *) (base + eh->e_phoff);
    uintptr_t dynp = 0;
    for (int i = 0; i < eh->e_phnum; i++) {
        if (ph[i].p_type == PT_DYNAMIC) {
            dynp = base + ph[i].p_vaddr;
            break;
        }
    }
    if (!dynp) return nullptr;

    uintptr_t straddr = 0, symaddr = 0, gnuh = 0, sysvh = 0;
    size_t syment = sizeof(Elf64_Sym), strsz = 0;
    for (Elf64_Dyn *d = (Elf64_Dyn *) dynp; d->d_tag != DT_NULL; d++) {
        uintptr_t v = d->d_un.d_ptr;
        switch (d->d_tag) {
            case DT_STRTAB:   straddr = v; break;
            case DT_SYMTAB:   symaddr = v; break;
            case DT_SYMENT:   syment  = d->d_un.d_val; break;
            case DT_STRSZ:    strsz   = d->d_un.d_val; break;
            case DT_GNU_HASH: gnuh    = v; break;
            case DT_HASH:     sysvh   = v; break;
        }
    }
    if (!straddr || !symaddr) return nullptr;
    if (straddr < base) straddr += base;
    if (symaddr < base) symaddr += base;
    if (gnuh  && gnuh  < base) gnuh  += base;
    if (sysvh && sysvh < base) sysvh += base;

    size_t nsym = dynsym_count(gnuh, sysvh);
    if (!nsym) {                                            // 兜底: 假设 dynsym 紧邻 dynstr
        if (straddr > symaddr && syment) nsym = (straddr - symaddr) / syment;
        else return nullptr;
    }
    Elf64_Sym *st = (Elf64_Sym *) symaddr;
    const char *str = (const char *) straddr;
    for (size_t i = 0; i < nsym; i++) {
        if (st[i].st_name == 0 || st[i].st_value == 0) continue;
        if (strsz && st[i].st_name >= strsz) continue;
        if (strcmp(str + st[i].st_name, sym) == 0) {
            return (void *) (base + st[i].st_value);
        }
    }
    return nullptr;
}

} // namespace feishukit_elf
