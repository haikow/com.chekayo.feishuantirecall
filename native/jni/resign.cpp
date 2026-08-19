// 飞书离职统计 —— native SQL 层
// 在飞书进程内, hook libsqlcipher.so 的 sqlite3_key_v2 抓到 contact.db 的 sqlite3* 句柄
// (每个加密库开库后必调 key_v2; 与防撤回 hook 的 sqlite3_step 不同函数, 互不冲突),
// 之后由 Java 触发 dumpResigned() 就地查询 chatters WHERE is_resigned=1, 写 JSON 文件。
// 符号解析不走 dlopen (linker namespace 隔离): 读 /proc/self/maps 找 base + 解析 ELF .dynsym。

#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include <elf.h>
#include <unistd.h>
#include <android/log.h>
#include "And64InlineHook.hpp"

#define TAG "larkresign-native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ── 复用防撤回的符号解析 (namespace 无关) ─────────────────────────────
static uintptr_t find_base(const char *name) {
    FILE *f = fopen("/proc/self/maps", "r");
    if (!f) return 0;
    char line[1024]; uintptr_t base = 0;
    while (fgets(line, sizeof(line), f)) {
        if (!strstr(line, name)) continue;
        uintptr_t start = 0, end = 0; char perms[8] = {0}; unsigned long off = 1;
        if (sscanf(line, "%lx-%lx %7s %lx", &start, &end, perms, &off) < 4) continue;
        if (off != 0 || !start) continue;
        const unsigned char *m = (const unsigned char *) start;
        if (m[0]==0x7f && m[1]=='E' && m[2]=='L' && m[3]=='F') { base = start; break; }
    }
    fclose(f); return base;
}
static size_t dynsym_count(uintptr_t gnu_hash, uintptr_t sysv_hash) {
    if (sysv_hash) { uint32_t *h = (uint32_t *) sysv_hash; return h[1]; }
    if (gnu_hash) {
        uint32_t *h = (uint32_t *) gnu_hash;
        uint32_t nbuckets = h[0], symoffset = h[1], bloom_size = h[2];
        uint32_t *buckets = (uint32_t *) ((uint8_t *)(h+4) + (size_t)bloom_size*sizeof(uint64_t));
        uint32_t *chain = buckets + nbuckets; uint32_t last = 0;
        for (uint32_t i=0;i<nbuckets;i++) if (buckets[i]>last) last=buckets[i];
        if (last < symoffset) return symoffset;
        while (!(chain[last-symoffset] & 1)) last++;
        return last + 1;
    }
    return 0;
}
static void *resolve(uintptr_t base, const char *sym) {
    Elf64_Ehdr *eh = (Elf64_Ehdr *) base;
    Elf64_Phdr *ph = (Elf64_Phdr *) (base + eh->e_phoff);
    uintptr_t dynp = 0;
    for (int i=0;i<eh->e_phnum;i++) if (ph[i].p_type==PT_DYNAMIC){ dynp=base+ph[i].p_vaddr; break; }
    if (!dynp) return NULL;
    uintptr_t straddr=0, symaddr=0, gnuh=0, sysvh=0; size_t syment=sizeof(Elf64_Sym), strsz=0;
    for (Elf64_Dyn *d=(Elf64_Dyn *)dynp; d->d_tag!=DT_NULL; d++) {
        uintptr_t v=d->d_un.d_ptr;
        switch (d->d_tag){ case DT_STRTAB:straddr=v;break; case DT_SYMTAB:symaddr=v;break;
            case DT_SYMENT:syment=d->d_un.d_val;break; case DT_STRSZ:strsz=d->d_un.d_val;break;
            case DT_GNU_HASH:gnuh=v;break; case DT_HASH:sysvh=v;break; }
    }
    if (!straddr || !symaddr) return NULL;
    if (straddr<base) straddr+=base; if (symaddr<base) symaddr+=base;
    if (gnuh&&gnuh<base) gnuh+=base; if (sysvh&&sysvh<base) sysvh+=base;
    size_t nsym = dynsym_count(gnuh, sysvh);
    if (!nsym) { if (straddr>symaddr && syment) nsym=(straddr-symaddr)/syment; else return NULL; }
    Elf64_Sym *st=(Elf64_Sym *)symaddr; const char *str=(const char *)straddr;
    for (size_t i=0;i<nsym;i++){
        if (st[i].st_name==0 || st[i].st_value==0) continue;
        if (strsz && st[i].st_name>=strsz) continue;
        if (strcmp(str+st[i].st_name, sym)==0) return (void *)(base+st[i].st_value);
    }
    return NULL;
}

// ── sqlcipher / sqlite3 函数指针 ─────────────────────────────────────
typedef int         (*prepare_t)(void*, const char*, int, void**, const char**);
typedef int         (*step_t)(void*);
typedef const unsigned char* (*coltext_t)(void*, int);
typedef const void* (*colblob_t)(void*, int);
typedef int         (*colbytes_t)(void*, int);
typedef int         (*finalize_t)(void*);
typedef void*       (*dbhandle_t)(void*);
typedef const char* (*dbfilename_t)(void*, const char*);
typedef int         (*keyv2_t)(void*, const char*, const void*, int);

static prepare_t   orig_prepare = nullptr;  // sqlite3_prepare_v2 原始 (trampoline)
static step_t      p_step    = nullptr;
static coltext_t   p_coltext = nullptr;
static colblob_t   p_colblob = nullptr;
static colbytes_t  p_colbytes= nullptr;
static finalize_t  p_finalize= nullptr;
static dbfilename_t p_dbfile = nullptr;

static void* g_contact_db = nullptr;   // 最近抓到的 contact.db 句柄(诊断用)
static bool  g_installed  = false;
static volatile bool g_dump_pending = false;   // Java 请求了一次 dump
static char  g_dump_path[512] = {0};
static volatile int  g_dump_result = -999;     // -999=pending, >=0=累计行数, 负=错误

// ── V3 富资料批量 dump: 查 chatter_profiles_v3 JOIN chatters, 把每人 blob 原样写 JSONL(hex) ──
// (protobuf 解析太绕, 放 Java 层做; native 只负责就地把 blob 掏出来落文件)
static volatile bool g_prof_pending = false;
static char  g_prof_path[512] = {0};
static volatile int  g_prof_result = -999;     // -999=pending, >=0=累计行数, 负=错误
static int   g_prof_rows = 0;                  // 本 arm 周期累计行数(多账号跨句柄相加)

// ── 全量花名册 dump: 查【全部】chatters(不限 is_resigned), LEFT JOIN v3 富资料。
//    区别于 doProfileDump(只写有 blob 的人): 本 dump 覆盖全员, 没资料的人也落 name/tenant_id,
//    补齐「组织架构巡游」时无 profile 的同事姓名。输出 JSONL(同 doProfileDump 行格式,
//    hex 可空), 由 Java ProfileBulk.mergeRoster 解析并入 profiles.json。
static volatile bool g_roster_pending = false;
static char  g_roster_path[512] = {0};
static volatile int  g_roster_result = -999;   // -999=pending, >=0=累计行数, 负=错误
static int   g_roster_rows = 0;                // 本 arm 周期累计行数(多账号跨句柄相加)

// ── 跨句柄/跨账号 union: 按 id 去重累积所有离职行(多账号=多个 contact.db) ──
#define MAXR 6000
static char* g_uid[MAXR];      // 已见 id (strdup)
static char* g_urow[MAXR];     // 该行完整 JSON 对象文本 (strdup)
static int   g_un = 0;
static bool uid_seen(const char* id){ for(int i=0;i<g_un;i++) if(strcmp(g_uid[i],id)==0) return true; return false; }

static void jesc(char* dst, size_t cap, const char* s){   // JSON 字符串转义(不含引号)
    size_t o=0;
    for (const char* p=s; p && *p && o+8<cap; ++p){
        unsigned char c=*p;
        if (c=='"'||c=='\\'){ dst[o++]='\\'; dst[o++]=c; }
        else if (c=='\n'){ dst[o++]='\\'; dst[o++]='n'; }
        else if (c=='\r'){ dst[o++]='\\'; dst[o++]='r'; }
        else if (c=='\t'){ dst[o++]='\\'; dst[o++]='t'; }
        else if (c<0x20){ o+=snprintf(dst+o,cap-o,"\\u%04x",c); }
        else dst[o++]=c;
    }
    dst[o]=0;
}

// 把 union 全量写到 g_dump_path
static void writeUnion() {
    FILE* out = fopen(g_dump_path, "w");
    if (!out) { g_dump_result = -3; return; }
    fputc('[', out);
    for (int i=0;i<g_un;i++){ if(i) fputc(',',out); fputs("\n ",out); fputs(g_urow[i],out); }
    fputs("\n]\n", out);
    fclose(out);
}

// 在【DB 自己的线程】上查询该 contact.db 的离职行, 去重并入 union, 再全量写文件。
// 注: chatters 表无真实离职时间列(update_time=本地行刷新时刻; expire_time=缓存TTL,未来日期;
//     work_status 空)。真实离职日期只在服务器端, 未缓存本地 -> 客户端无法据此排序。
static void doDump(void* db) {
    const char* sql = "SELECT id, name, en_us_name, alias, another_name, tenant_id, update_time, is_frozen "
                      "FROM chatters WHERE is_resigned=1 ORDER BY update_time DESC";
    void* stmt = nullptr;
    int rc = orig_prepare(db, sql, -1, &stmt, nullptr);
    if (rc != 0 || !stmt) { LOGE("doDump prepare rc=%d", rc); if(g_dump_result==-999) g_dump_result=-2; return; }

    auto col = [&](int i)->const char*{ const unsigned char* c=p_coltext(stmt,i); return c?(const char*)c:""; };
    int added = 0;
    while (p_step(stmt) == 100 /*SQLITE_ROW*/) {
        const char* id = col(0);
        if (!id || !*id || uid_seen(id) || g_un>=MAXR) continue;
        char e_id[64],e_name[256],e_en[256],e_alias[256],e_ann[256],e_tid[64],e_ut[64],e_fz[16];
        jesc(e_id,sizeof(e_id),col(0)); jesc(e_name,sizeof(e_name),col(1)); jesc(e_en,sizeof(e_en),col(2));
        jesc(e_alias,sizeof(e_alias),col(3)); jesc(e_ann,sizeof(e_ann),col(4)); jesc(e_tid,sizeof(e_tid),col(5));
        jesc(e_ut,sizeof(e_ut),col(6)); jesc(e_fz,sizeof(e_fz),col(7));
        char row[1400];
        snprintf(row,sizeof(row),
            "{\"id\":\"%s\",\"name\":\"%s\",\"en_us_name\":\"%s\",\"alias\":\"%s\",\"another_name\":\"%s\","
            "\"tenant_id\":\"%s\",\"update_time\":\"%s\",\"is_frozen\":\"%s\"}",
            e_id,e_name,e_en,e_alias,e_ann,e_tid,e_ut,e_fz);
        g_uid[g_un]=strdup(e_id); g_urow[g_un]=strdup(row); g_un++; added++;
    }
    p_finalize(stmt);
    writeUnion();
    g_dump_result = g_un;
    LOGI("doDump handle=%p +%d rows, union=%d", db, added, g_un);
}

// V3 富资料批量 dump: chatter_profiles_v3 JOIN chatters -> JSONL(每行一个 {chatter_id,tenant_id,name,is_resigned,hex})
// 本 arm 首个句柄 "w" 清空, 其余账号句柄 "a" 追加(多账号 union)。blob 以 hex 落, Java 解 protobuf。
static void* g_prof_dumped_this_arm[16]; static int g_prof_ndumped = 0;
static bool prof_dumped_this_arm(void* db){ for(int i=0;i<g_prof_ndumped;i++) if(g_prof_dumped_this_arm[i]==db) return true; return false; }

static void doProfileDump(void* db) {
    const char* sql =
        "SELECT v.chatter_id, c.tenant_id, c.name, c.is_resigned, v.profile "
        "FROM chatter_profiles_v3 v JOIN chatters c ON c.id=v.chatter_id "
        "WHERE v.profile IS NOT NULL AND length(v.profile)>0";
    void* stmt = nullptr;
    int rc = orig_prepare(db, sql, -1, &stmt, nullptr);
    if (rc != 0 || !stmt) { LOGE("doProfileDump prepare rc=%d", rc); if(g_prof_result==-999) g_prof_result=-2; return; }
    const char* mode = (g_prof_ndumped == 0) ? "w" : "a";   // 本 arm 首个句柄清空, 其余追加
    FILE* out = fopen(g_prof_path, mode);
    if (!out) { p_finalize(stmt); if(g_prof_result==-999) g_prof_result=-3; return; }
    static const char HEX[] = "0123456789abcdef";
    int added = 0;
    while (p_step(stmt) == 100 /*SQLITE_ROW*/) {
        // 先读 4 个文本列, 再读 blob 列(不同列, 不触发同列类型转换失效)
        const unsigned char* cid = p_coltext(stmt, 0);
        const unsigned char* tid = p_coltext(stmt, 1);
        const unsigned char* nm  = p_coltext(stmt, 2);
        const unsigned char* rs  = p_coltext(stmt, 3);
        const unsigned char* blob = (const unsigned char*) p_colblob(stmt, 4);
        int bn = p_colbytes(stmt, 4);
        if (!blob || bn <= 0) continue;
        char e_cid[64], e_tid[64], e_nm[256], e_rs[16];
        jesc(e_cid, sizeof(e_cid), cid ? (const char*)cid : "");
        jesc(e_tid, sizeof(e_tid), tid ? (const char*)tid : "");
        jesc(e_nm,  sizeof(e_nm),  nm  ? (const char*)nm  : "");
        jesc(e_rs,  sizeof(e_rs),  rs  ? (const char*)rs  : "");
        fprintf(out, "{\"chatter_id\":\"%s\",\"tenant_id\":\"%s\",\"name\":\"%s\",\"is_resigned\":\"%s\",\"hex\":\"",
                e_cid, e_tid, e_nm, e_rs);
        char* hb = (char*) malloc((size_t)bn * 2 + 1);   // 缓冲 hex, 一次 fputs(避免逐字节 fprintf 慢)
        if (hb) {
            for (int i = 0; i < bn; i++) { hb[i*2] = HEX[blob[i] >> 4]; hb[i*2+1] = HEX[blob[i] & 0xf]; }
            hb[bn*2] = 0; fputs(hb, out); free(hb);
        }
        fputs("\"}\n", out);
        added++;
    }
    p_finalize(stmt);
    fclose(out);
    g_prof_rows += added;
    g_prof_result = g_prof_rows;
    LOGI("doProfileDump handle=%p +%d rows (mode=%s) total=%d", db, added, mode, g_prof_rows);
}

// 全量花名册 dump: 全部 chatters LEFT JOIN v3 -> JSONL(每行 {chatter_id,tenant_id,name,is_resigned,hex})
// 与 doProfileDump 同结构, 但 LEFT JOIN 覆盖全员(无 profile 的人 hex="" 仍落, 补齐无资料者的姓名)。
// 本 arm 首个句柄 "w" 清空, 其余账号句柄 "a" 追加(多账号 union)。
static void* g_roster_dumped_this_arm[16]; static int g_roster_ndumped = 0;
static bool roster_dumped_this_arm(void* db){ for(int i=0;i<g_roster_ndumped;i++) if(g_roster_dumped_this_arm[i]==db) return true; return false; }

static void doRosterDump(void* db) {
    // chatter_profiles_v3 可能不存在(老库/未登录富资料) -> 探测后决定 SQL
    bool hasV3 = false;
    {
        void* chk = nullptr;
        if (orig_prepare(db, "SELECT 1 FROM sqlite_master WHERE type='table' AND name='chatter_profiles_v3' LIMIT 1", -1, &chk, nullptr) == 0 && chk) {
            if (p_step(chk) == 100 /*ROW*/) hasV3 = true;
            p_finalize(chk);
        }
    }
    // LEFT JOIN: 全员都落, 有富资料的顺带把 blob hex 带上
    const char* sql = hasV3
        ? "SELECT c.id, c.tenant_id, c.name, c.is_resigned, v.profile "
          "FROM chatters c LEFT JOIN chatter_profiles_v3 v ON v.chatter_id=c.id"
        : "SELECT c.id, c.tenant_id, c.name, c.is_resigned, NULL AS profile FROM chatters c";
    void* stmt = nullptr;
    int rc = orig_prepare(db, sql, -1, &stmt, nullptr);
    if (rc != 0 || !stmt) { LOGE("doRosterDump prepare rc=%d", rc); if(g_roster_result==-999) g_roster_result=-2; return; }
    const char* mode = (g_roster_ndumped == 0) ? "w" : "a";   // 本 arm 首个句柄清空, 其余追加
    FILE* out = fopen(g_roster_path, mode);
    if (!out) { p_finalize(stmt); if(g_roster_result==-999) g_roster_result=-3; return; }
    static const char HEX[] = "0123456789abcdef";
    int added = 0;
    while (p_step(stmt) == 100 /*SQLITE_ROW*/) {
        const unsigned char* cid = p_coltext(stmt, 0);
        const unsigned char* tid = p_coltext(stmt, 1);
        const unsigned char* nm  = p_coltext(stmt, 2);
        const unsigned char* rs  = p_coltext(stmt, 3);
        if (!cid || !*cid) continue;            // 空 id 跳过
        char e_cid[64], e_tid[64], e_nm[256], e_rs[16];
        jesc(e_cid, sizeof(e_cid), (const char*)cid);
        jesc(e_tid, sizeof(e_tid), tid ? (const char*)tid : "");
        jesc(e_nm,  sizeof(e_nm),  nm  ? (const char*)nm  : "");
        jesc(e_rs,  sizeof(e_rs),  rs  ? (const char*)rs  : "");
        const unsigned char* blob = (const unsigned char*) p_colblob(stmt, 4);
        int bn = p_colbytes(stmt, 4);
        fprintf(out, "{\"chatter_id\":\"%s\",\"tenant_id\":\"%s\",\"name\":\"%s\",\"is_resigned\":\"%s\",\"hex\":\"",
                e_cid, e_tid, e_nm, e_rs);
        if (blob && bn > 0) {
            char* hb = (char*) malloc((size_t)bn * 2 + 1);
            if (hb) {
                for (int i = 0; i < bn; i++) { hb[i*2] = HEX[blob[i] >> 4]; hb[i*2+1] = HEX[blob[i] & 0xf]; }
                hb[bn*2] = 0; fputs(hb, out); free(hb);
            }
        }
        fputs("\"}\n", out);
        added++;
    }
    p_finalize(stmt);
    fclose(out);
    g_roster_rows += added;
    g_roster_result = g_roster_rows;
    LOGI("doRosterDump handle=%p +%d rows (mode=%s, v3=%d) total=%d", db, added, mode, hasV3, g_roster_rows);
}

// hook: sqlite3_prepare_v2 —— app 每次 prepare 回调都在 DB 自己的线程上。
// 抓【任意】contact.db(多账号=多个句柄), pending 时就地 dump 并入 union(同线程, 不 MISUSE)。
// 每个 arm 周期内每个句柄只 dump 一次(避免高频重复); Java 再 add-only 合并进 resigned_all.json。
static void* g_dumped_this_arm[16]; static int g_ndumped = 0;
static bool dumped_this_arm(void* db){ for(int i=0;i<g_ndumped;i++) if(g_dumped_this_arm[i]==db) return true; return false; }

static int my_prepare(void* db, const char* sql, int n, void** ppStmt, const char** tail) {
    int rc = orig_prepare(db, sql, n, ppStmt, tail);
    if (db && p_dbfile) {
        const char* fn = p_dbfile(db, "main");
        if (fn && strstr(fn, "contact.db")) {
            g_contact_db = db;
            if (g_dump_pending && !dumped_this_arm(db)) {
                if (g_ndumped < 16) g_dumped_this_arm[g_ndumped++] = db;
                doDump(db);              // 就在这条 DB 线程上跑, 安全; 多账号各句柄各 dump 一次
            }
            if (g_prof_pending && p_colblob && p_colbytes && !prof_dumped_this_arm(db)) {
                doProfileDump(db);       // mode 依赖 g_prof_ndumped(先用后加), 故先 dump 再登记
                if (g_prof_ndumped < 16) g_prof_dumped_this_arm[g_prof_ndumped++] = db;
            }
            if (g_roster_pending && p_colblob && p_colbytes && !roster_dumped_this_arm(db)) {
                doRosterDump(db);        // 同上: mode 依赖 g_roster_ndumped, 先 dump 再登记
                if (g_roster_ndumped < 16) g_roster_dumped_this_arm[g_roster_ndumped++] = db;
            }
        }
    }
    return rc;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_chekayo_larkresign_ResignTracker_nativeInit(JNIEnv*, jclass) {
    if (g_installed) return JNI_TRUE;
    uintptr_t base = find_base("libsqlcipher.so");
    if (!base) return JNI_FALSE;
    void* prep = resolve(base, "sqlite3_prepare_v2");
    p_step     = (step_t)      resolve(base, "sqlite3_step");
    p_coltext  = (coltext_t)   resolve(base, "sqlite3_column_text");
    p_colblob  = (colblob_t)   resolve(base, "sqlite3_column_blob");
    p_colbytes = (colbytes_t)  resolve(base, "sqlite3_column_bytes");
    p_finalize = (finalize_t)  resolve(base, "sqlite3_finalize");
    p_dbfile   = (dbfilename_t)resolve(base, "sqlite3_db_filename");
    LOGI("base=%p prepare=%p step=%p coltext=%p colblob=%p colbytes=%p finalize=%p dbfile=%p",
         (void*)base, prep, p_step, p_coltext, p_colblob, p_colbytes, p_finalize, p_dbfile);
    if (!prep || !p_step || !p_coltext || !p_finalize || !p_dbfile) {
        LOGE("resolve failed"); g_installed = true; return JNI_TRUE;
    }
    A64HookFunction(prep, (void*)my_prepare, (void**)&orig_prepare);
    g_installed = true;
    LOGI("hook installed on sqlite3_prepare_v2 @%p", prep);
    return JNI_TRUE;
}

// 请求一次 dump: 记路径+置 pending, 由下一次 contact.db prepare 就地完成。返回 0。
extern "C" JNIEXPORT jint JNICALL
Java_com_chekayo_larkresign_ResignTracker_nativeArmDump(JNIEnv* env, jclass, jstring jpath) {
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    strncpy(g_dump_path, path, sizeof(g_dump_path) - 1);
    g_dump_path[sizeof(g_dump_path) - 1] = 0;
    env->ReleaseStringUTFChars(jpath, path);
    g_dump_result = -999;
    g_ndumped = 0;              // 本 arm 周期各 contact.db 句柄重新各 dump 一次
    g_dump_pending = true;
    return 0;
}

// 轮询结果: -999=还没完成; >=0=行数; 负=错误。-1=还没抓到 contact.db 句柄。
extern "C" JNIEXPORT jint JNICALL
Java_com_chekayo_larkresign_ResignTracker_nativePoll(JNIEnv*, jclass) {
    if (!g_contact_db && g_dump_result == -999) return -1;
    return g_dump_result;
}

// 请求一次 V3 富资料批量 dump: 记路径+置 pending, 下一次 contact.db prepare 就地把 blob 落 JSONL。
extern "C" JNIEXPORT jint JNICALL
Java_com_chekayo_larkresign_ResignTracker_nativeArmProfiles(JNIEnv* env, jclass, jstring jpath) {
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    strncpy(g_prof_path, path, sizeof(g_prof_path) - 1);
    g_prof_path[sizeof(g_prof_path) - 1] = 0;
    env->ReleaseStringUTFChars(jpath, path);
    g_prof_result = -999;
    g_prof_rows = 0;
    g_prof_ndumped = 0;        // 本 arm 周期各 contact.db 句柄重新各 dump 一次(首个清空文件)
    g_prof_pending = true;
    return 0;
}

// 轮询 V3 dump 结果: -999=未完成; >=0=行数; -1=还没抓到 contact.db 句柄。
extern "C" JNIEXPORT jint JNICALL
Java_com_chekayo_larkresign_ResignTracker_nativeProfileResult(JNIEnv*, jclass) {
    if (!g_contact_db && g_prof_result == -999) return -1;
    return g_prof_result;
}

// 请求一次全量花名册 dump: 记路径+置 pending, 下一次 contact.db prepare 就地把全员落 JSONL。
// 不限 is_resigned(覆盖全员, 补齐组织巡游时无 profile 的同事姓名)。
extern "C" JNIEXPORT jint JNICALL
Java_com_chekayo_larkresign_ResignTracker_nativeArmRoster(JNIEnv* env, jclass, jstring jpath) {
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    strncpy(g_roster_path, path, sizeof(g_roster_path) - 1);
    g_roster_path[sizeof(g_roster_path) - 1] = 0;
    env->ReleaseStringUTFChars(jpath, path);
    g_roster_result = -999;
    g_roster_rows = 0;
    g_roster_ndumped = 0;      // 本 arm 周期各 contact.db 句柄重新各 dump 一次(首个清空文件)
    g_roster_pending = true;
    return 0;
}

// 轮询全量花名册 dump 结果: -999=未完成; >=0=行数; -1=还没抓到 contact.db 句柄。
extern "C" JNIEXPORT jint JNICALL
Java_com_chekayo_larkresign_ResignTracker_nativeRosterResult(JNIEnv*, jclass) {
    if (!g_contact_db && g_roster_result == -999) return -1;
    return g_roster_result;
}
