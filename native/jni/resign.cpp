// 飞书离职统计 —— native SQL 层
// 在飞书进程内, hook SQLite 的 sqlite3_prepare_v2 抓到 contact.db 的 sqlite3* 句柄,
// 之后由 Java 触发 dump 就地查询 chatters / chatter_profiles_v3, 写 JSON / JSONL 文件。
// 符号解析不走 dlopen (linker namespace 隔离): 复用 elf_resolver.hpp 解析 /proc/self/maps + .dynsym。

#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <android/log.h>
#include <atomic>
#include <mutex>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <utility>
#include <vector>
#include "And64InlineHook.hpp"
#include "elf_resolver.hpp"
#include "recall_filter.h"

#define TAG "larkresign-native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ── sqlcipher / sqlite3 函数指针 ─────────────────────────────────────
typedef int         (*prepare_t)(void*, const char*, int, void**, const char**);
typedef int         (*step_t)(void*);
typedef const unsigned char* (*coltext_t)(void*, int);
typedef const void* (*colblob_t)(void*, int);
typedef int         (*colbytes_t)(void*, int);
typedef int         (*finalize_t)(void*);
typedef const char* (*dbfilename_t)(void*, const char*);

// ── sqlite provider: 8.x 的 contact.db 可能走 libwcdb2.so 的 sqlite(消息库已确认迁移),
//    7.x/旧库走 libsqlcipher.so。两库各 hook 一份 prepare_v2, 按库配符号(句柄不通用)。
struct RProv {
    const char *so;
    bool gave_up, installed;
    void *prep_ep;              // sqlite3_prepare_v2 入口(检测反篡改还原用)
    unsigned char hookbytes[16];
    prepare_t   orig_prepare;   // sqlite3_prepare_v2 原始 (trampoline)
    step_t      step;
    coltext_t   coltext;
    colblob_t   colblob;
    colbytes_t  colbytes;
    finalize_t  finalize;
    dbfilename_t dbfile;
};
static RProv g_rp[2] = {
    { "libwcdb2.so" },       // 8.x(优先; 7.x 无此库则恒未装, 无害)
    { "libsqlcipher.so" },   // 7.x + 8.x 旧组件库
};
static int my_prepare_wcdb(void*, const char*, int, void**, const char**);
static int my_prepare_cipher(void*, const char*, int, void**, const char**);

static std::mutex g_hook_lock;
static bool g_installed = false;
static bool g_init_requested = false;
static int g_prep_rehook = 0;

// 所有 dump 状态由 g_resign_lock 保护; 文件串行写锁为 g_*_io_lock (锁序恒为 io_lock -> g_resign_lock)。
static std::mutex g_resign_lock;
static std::mutex g_dump_io_lock;
static std::mutex g_prof_io_lock;
static std::mutex g_roster_io_lock;

static std::atomic<bool> g_has_contact_db{false};
static std::atomic<bool> g_any_pending{false};

static void* g_contact_db = nullptr;   // 最近抓到的 contact.db 句柄(诊断用)

static bool g_dump_pending = false;    // Java 请求了一次 dump
static std::string g_dump_path;
static std::string g_dump_target_uid;
static int g_dump_result = -999;       // -999=pending, >=0=累计行数, 负=错误
static uint64_t g_dump_gen = 0;
static std::unordered_set<void*> g_dumped_this_arm;

// ── V3 富资料批量 dump: 查 chatter_profiles_v3 JOIN chatters, 把每人 blob 原样写 JSONL(hex) ──
// (protobuf 解析太绕, 放 Java 层做; native 只负责就地把 blob 掏出来落文件)
static bool g_prof_pending = false;
static std::string g_prof_path;
static std::string g_prof_target_uid;
static int g_prof_result = -999;       // -999=pending, >=0=累计行数, 负=错误
static int g_prof_rows = 0;            // 本 arm 周期累计行数
static int g_prof_written = 0;         // 本 arm 周期已落盘句柄数(0="w", >0="a")
static uint64_t g_prof_gen = 0;
static std::unordered_set<void*> g_prof_dumped_this_arm;

// ── 全量花名册 dump: 查【全部】chatters(不限 is_resigned), LEFT JOIN v3 富资料。
//    区别于 doProfileDump(只写有 blob 的人): 本 dump 覆盖全员, 没资料的人也落 name/tenant_id,
//    补齐「组织架构巡游」时无 profile 的同事姓名。输出 JSONL(同 doProfileDump 行格式,
//    hex 可空), 由 Java ProfileBulk.mergeRoster 解析并入 profiles.json。
static bool g_roster_pending = false;
static std::string g_roster_path;
static std::string g_roster_target_uid;
static int g_roster_result = -999;     // -999=pending, >=0=累计行数, 负=错误
static int g_roster_rows = 0;          // 本 arm 周期累计行数
static int g_roster_written = 0;       // 本 arm 周期已落盘句柄数(0="w", >0="a")
static uint64_t g_roster_gen = 0;
static std::unordered_set<void*> g_roster_dumped_this_arm;

// ── 跨句柄 union: 按 id O(1) 去重累积当前账号所有离职行(无 6000 上限, 切账号时清空) ──
static std::unordered_map<std::string, std::string> g_union_rows;
static std::string g_union_uid;

static long resign_now_ms() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long) ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

// 从输出路径解析目标账号 uid: 提取 /accounts/<uid>/ 段; 若不存在或为 "unknown" 返回空串(兜底放行)。
static std::string extract_account_uid(const std::string &path) {
    const std::string marker = "/accounts/";
    size_t p = path.find(marker);
    if (p == std::string::npos) return "";
    p += marker.size();
    size_t e = path.find('/', p);
    std::string uid = (e == std::string::npos) ? path.substr(p) : path.substr(p, e - p);
    if (uid.empty() || uid == "unknown") return "";
    return uid;
}

// 多账号隔离: 若目标路径含明确账号且 contact.db 路径(经 recall::owner 映射)也属于明确账号,
// 则仅当两者一致时允许写入; 任一方未知(单账号/旧路径/未绑定哈希目录)时兜底放行。
static bool uid_matches_target(const std::string &db_uid, const std::string &target_uid) {
    if (target_uid.empty() || db_uid.empty()) return true;
    return db_uid == target_uid;
}

static void jesc(char* dst, size_t cap, const char* s) {   // JSON 字符串转义(不含引号)
    size_t o = 0;
    for (const char* p = s; p && *p && o + 8 < cap; ++p) {
        unsigned char c = (unsigned char) *p;
        if (c == '"' || c == '\\') { dst[o++] = '\\'; dst[o++] = (char) c; }
        else if (c == '\n') { dst[o++] = '\\'; dst[o++] = 'n'; }
        else if (c == '\r') { dst[o++] = '\\'; dst[o++] = 'r'; }
        else if (c == '\t') { dst[o++] = '\\'; dst[o++] = 't'; }
        else if (c < 0x20) { o += (size_t) snprintf(dst + o, cap - o, "\\u%04x", c); }
        else dst[o++] = (char) c;
    }
    dst[o] = 0;
}

// 原子写入 union 全量 JSON 到目标路径
static bool writeUnionFile(const std::string &path, const std::vector<std::string> &rows) {
    std::string tmp = path + ".tmp";
    FILE* out = fopen(tmp.c_str(), "w");
    if (!out) return false;
    fputc('[', out);
    for (size_t i = 0; i < rows.size(); ++i) {
        if (i) fputc(',', out);
        fputs("\n ", out);
        fputs(rows[i].c_str(), out);
    }
    fputs("\n]\n", out);
    bool ok = !ferror(out);
    if (fclose(out) != 0) ok = false;
    if (!ok) {
        remove(tmp.c_str());
        return false;
    }
    if (rename(tmp.c_str(), path.c_str()) != 0) {
        remove(tmp.c_str());
        return false;
    }
    return true;
}

// 在【DB 自己的线程】上查询该 contact.db 的离职行, 去重并入 union, 再全量写文件。
// 注: chatters 表无真实离职时间列(update_time=本地行刷新时刻; expire_time=缓存TTL,未来日期;
//     work_status 空)。真实离职日期只在服务器端, 未缓存本地 -> 客户端无法据此排序。
static void doDump(RProv* pv, void* db, const std::string &path, uint64_t gen) {
    const char* sql = "SELECT id, name, en_us_name, alias, another_name, tenant_id, update_time, is_frozen "
                      "FROM chatters WHERE is_resigned=1 ORDER BY update_time DESC";
    void* stmt = nullptr;
    int rc = pv->orig_prepare(db, sql, -1, &stmt, nullptr);
    if (rc != 0 || !stmt) {
        LOGE("doDump prepare rc=%d", rc);
        std::lock_guard<std::mutex> guard(g_resign_lock);
        if (g_dump_gen == gen && g_dump_result == -999) g_dump_result = -2;
        return;
    }

    auto col = [&](int i) -> const char* {
        const unsigned char* c = pv->coltext(stmt, i);
        return c ? (const char*) c : "";
    };
    std::vector<std::pair<std::string, std::string>> local_rows;
    while (pv->step(stmt) == 100 /*SQLITE_ROW*/) {
        const char* id = col(0);
        if (!id || !*id) continue;
        char e_id[64], e_name[256], e_en[256], e_alias[256], e_ann[256], e_tid[64], e_ut[64], e_fz[16];
        jesc(e_id, sizeof(e_id), col(0));
        jesc(e_name, sizeof(e_name), col(1));
        jesc(e_en, sizeof(e_en), col(2));
        jesc(e_alias, sizeof(e_alias), col(3));
        jesc(e_ann, sizeof(e_ann), col(4));
        jesc(e_tid, sizeof(e_tid), col(5));
        jesc(e_ut, sizeof(e_ut), col(6));
        jesc(e_fz, sizeof(e_fz), col(7));
        char row[1400];
        snprintf(row, sizeof(row),
            "{\"id\":\"%s\",\"name\":\"%s\",\"en_us_name\":\"%s\",\"alias\":\"%s\",\"another_name\":\"%s\","
            "\"tenant_id\":\"%s\",\"update_time\":\"%s\",\"is_frozen\":\"%s\"}",
            e_id, e_name, e_en, e_alias, e_ann, e_tid, e_ut, e_fz);
        local_rows.emplace_back(std::string(e_id), std::string(row));
    }
    pv->finalize(stmt);

    std::lock_guard<std::mutex> io_guard(g_dump_io_lock);
    std::vector<std::string> snapshot;
    int added = 0;
    {
        std::lock_guard<std::mutex> guard(g_resign_lock);
        if (g_dump_gen != gen) return;
        for (const auto &kv : local_rows) {
            auto res = g_union_rows.emplace(kv.first, kv.second);
            if (res.second) added++;
            else res.first->second = kv.second;
        }
        snapshot.reserve(g_union_rows.size());
        for (const auto &kv : g_union_rows) snapshot.push_back(kv.second);
    }

    bool wrote = writeUnionFile(path, snapshot);
    int total = (int) snapshot.size();
    {
        std::lock_guard<std::mutex> guard(g_resign_lock);
        if (g_dump_gen == gen) {
            g_dump_result = wrote ? total : -3;
        }
    }
    LOGI("doDump handle=%p +%d rows, union=%d wrote=%d", db, added, total, wrote ? 1 : 0);
}

// V3 富资料批量 dump: chatter_profiles_v3 JOIN chatters -> JSONL(每行一个 {chatter_id,tenant_id,name,is_resigned,hex})
// 本 arm 首个句柄 "w" 清空, 其余同账号句柄 "a" 追加。blob 以 hex 落, Java 解 protobuf。
static void doProfileDump(RProv* pv, void* db, const std::string &path, uint64_t gen) {
    const char* sql =
        "SELECT v.chatter_id, c.tenant_id, c.name, c.is_resigned, v.profile "
        "FROM chatter_profiles_v3 v JOIN chatters c ON c.id=v.chatter_id "
        "WHERE v.profile IS NOT NULL AND length(v.profile)>0";
    void* stmt = nullptr;
    int rc = pv->orig_prepare(db, sql, -1, &stmt, nullptr);
    if (rc != 0 || !stmt) {
        LOGE("doProfileDump prepare rc=%d", rc);
        std::lock_guard<std::mutex> guard(g_resign_lock);
        if (g_prof_gen == gen && g_prof_result == -999) g_prof_result = -2;
        return;
    }

    std::lock_guard<std::mutex> io_guard(g_prof_io_lock);
    const char* mode = "w";
    {
        std::lock_guard<std::mutex> guard(g_resign_lock);
        if (g_prof_gen != gen) { pv->finalize(stmt); return; }
        mode = (g_prof_written == 0) ? "w" : "a";
    }

    FILE* out = fopen(path.c_str(), mode);
    if (!out) {
        pv->finalize(stmt);
        std::lock_guard<std::mutex> guard(g_resign_lock);
        if (g_prof_gen == gen && g_prof_result == -999) g_prof_result = -3;
        return;
    }

    static const char HEX[] = "0123456789abcdef";
    std::string hb;
    int added = 0;
    while (pv->step(stmt) == 100 /*SQLITE_ROW*/) {
        // 先读 4 个文本列, 再读 blob 列(不同列, 不触发同列类型转换失效)
        const unsigned char* cid = pv->coltext(stmt, 0);
        const unsigned char* tid = pv->coltext(stmt, 1);
        const unsigned char* nm  = pv->coltext(stmt, 2);
        const unsigned char* rs  = pv->coltext(stmt, 3);
        const unsigned char* blob = (const unsigned char*) pv->colblob(stmt, 4);
        int bn = pv->colbytes(stmt, 4);
        if (!blob || bn <= 0) continue;
        char e_cid[64], e_tid[64], e_nm[256], e_rs[16];
        jesc(e_cid, sizeof(e_cid), cid ? (const char*) cid : "");
        jesc(e_tid, sizeof(e_tid), tid ? (const char*) tid : "");
        jesc(e_nm,  sizeof(e_nm),  nm  ? (const char*) nm  : "");
        jesc(e_rs,  sizeof(e_rs),  rs  ? (const char*) rs  : "");
        fprintf(out, "{\"chatter_id\":\"%s\",\"tenant_id\":\"%s\",\"name\":\"%s\",\"is_resigned\":\"%s\",\"hex\":\"",
                e_cid, e_tid, e_nm, e_rs);
        hb.resize((size_t) bn * 2);
        for (int i = 0; i < bn; i++) {
            hb[(size_t) i * 2]     = HEX[blob[i] >> 4];
            hb[(size_t) i * 2 + 1] = HEX[blob[i] & 0xf];
        }
        fwrite(hb.data(), 1, hb.size(), out);
        fputs("\"}\n", out);
        added++;
    }
    pv->finalize(stmt);
    fclose(out);

    int total = 0;
    {
        std::lock_guard<std::mutex> guard(g_resign_lock);
        if (g_prof_gen == gen) {
            g_prof_written++;
            g_prof_rows += added;
            g_prof_result = g_prof_rows;
            total = g_prof_rows;
        }
    }
    LOGI("doProfileDump handle=%p +%d rows (mode=%s) total=%d", db, added, mode, total);
}

// 全量花名册 dump: 全部 chatters LEFT JOIN v3 -> JSONL(每行 {chatter_id,tenant_id,name,is_resigned,hex})
// 与 doProfileDump 同结构, 但 LEFT JOIN 覆盖全员(无 profile 的人 hex="" 仍落, 补齐无资料者的姓名)。
// 本 arm 首个句柄 "w" 清空, 其余同账号句柄 "a" 追加。
static void doRosterDump(RProv* pv, void* db, const std::string &path, uint64_t gen) {
    // chatter_profiles_v3 可能不存在(老库/未登录富资料) -> 探测后决定 SQL
    bool hasV3 = false;
    {
        void* chk = nullptr;
        if (pv->orig_prepare(db, "SELECT 1 FROM sqlite_master WHERE type='table' AND name='chatter_profiles_v3' LIMIT 1", -1, &chk, nullptr) == 0 && chk) {
            if (pv->step(chk) == 100 /*ROW*/) hasV3 = true;
            pv->finalize(chk);
        }
    }
    // LEFT JOIN: 全员都落, 有富资料的顺带把 blob hex 带上
    const char* sql = hasV3
        ? "SELECT c.id, c.tenant_id, c.name, c.is_resigned, v.profile "
          "FROM chatters c LEFT JOIN chatter_profiles_v3 v ON v.chatter_id=c.id"
        : "SELECT c.id, c.tenant_id, c.name, c.is_resigned, NULL AS profile FROM chatters c";
    void* stmt = nullptr;
    int rc = pv->orig_prepare(db, sql, -1, &stmt, nullptr);
    if (rc != 0 || !stmt) {
        LOGE("doRosterDump prepare rc=%d", rc);
        std::lock_guard<std::mutex> guard(g_resign_lock);
        if (g_roster_gen == gen && g_roster_result == -999) g_roster_result = -2;
        return;
    }

    std::lock_guard<std::mutex> io_guard(g_roster_io_lock);
    const char* mode = "w";
    {
        std::lock_guard<std::mutex> guard(g_resign_lock);
        if (g_roster_gen != gen) { pv->finalize(stmt); return; }
        mode = (g_roster_written == 0) ? "w" : "a";
    }

    FILE* out = fopen(path.c_str(), mode);
    if (!out) {
        pv->finalize(stmt);
        std::lock_guard<std::mutex> guard(g_resign_lock);
        if (g_roster_gen == gen && g_roster_result == -999) g_roster_result = -3;
        return;
    }

    static const char HEX[] = "0123456789abcdef";
    std::string hb;
    int added = 0;
    while (pv->step(stmt) == 100 /*SQLITE_ROW*/) {
        const unsigned char* cid = pv->coltext(stmt, 0);
        const unsigned char* tid = pv->coltext(stmt, 1);
        const unsigned char* nm  = pv->coltext(stmt, 2);
        const unsigned char* rs  = pv->coltext(stmt, 3);
        if (!cid || !*cid) continue;            // 空 id 跳过
        char e_cid[64], e_tid[64], e_nm[256], e_rs[16];
        jesc(e_cid, sizeof(e_cid), (const char*) cid);
        jesc(e_tid, sizeof(e_tid), tid ? (const char*) tid : "");
        jesc(e_nm,  sizeof(e_nm),  nm  ? (const char*) nm  : "");
        jesc(e_rs,  sizeof(e_rs),  rs  ? (const char*) rs  : "");
        const unsigned char* blob = (const unsigned char*) pv->colblob(stmt, 4);
        int bn = pv->colbytes(stmt, 4);
        fprintf(out, "{\"chatter_id\":\"%s\",\"tenant_id\":\"%s\",\"name\":\"%s\",\"is_resigned\":\"%s\",\"hex\":\"",
                e_cid, e_tid, e_nm, e_rs);
        if (blob && bn > 0) {
            hb.resize((size_t) bn * 2);
            for (int i = 0; i < bn; i++) {
                hb[(size_t) i * 2]     = HEX[blob[i] >> 4];
                hb[(size_t) i * 2 + 1] = HEX[blob[i] & 0xf];
            }
            fwrite(hb.data(), 1, hb.size(), out);
        }
        fputs("\"}\n", out);
        added++;
    }
    pv->finalize(stmt);
    fclose(out);

    int total = 0;
    {
        std::lock_guard<std::mutex> guard(g_resign_lock);
        if (g_roster_gen == gen) {
            g_roster_written++;
            g_roster_rows += added;
            g_roster_result = g_roster_rows;
            total = g_roster_rows;
        }
    }
    LOGI("doRosterDump handle=%p +%d rows (mode=%s, v3=%d) total=%d", db, added, mode, hasV3 ? 1 : 0, total);
}

// hook: sqlite3_prepare_v2 —— app 每次 prepare 回调都在 DB 自己的线程上。
// 抓匹配目标账号的 contact.db, pending 时就地 dump(同线程, 不 MISUSE)。
// 每个 arm 周期内每个句柄只 dump 一次(避免高频重复); Java 再 add-only 合并进 resigned_all.json。
static int prepare_common(RProv* pv, void* db, const char* sql, int n, void** ppStmt, const char** tail) {
    int rc = pv->orig_prepare(db, sql, n, ppStmt, tail);
    if (!db || !pv->dbfile) return rc;
    if (g_has_contact_db.load(std::memory_order_acquire)
            && !g_any_pending.load(std::memory_order_acquire)) {
        return rc;
    }
    const char* fn = pv->dbfile(db, "main");
    if (!fn || !strstr(fn, "contact.db")) return rc;
    std::string db_uid = recall::owner(fn);

    bool run_dump = false, run_prof = false, run_roster = false;
    std::string dump_path, prof_path, roster_path;
    uint64_t dump_gen = 0, prof_gen = 0, roster_gen = 0;
    {
        std::lock_guard<std::mutex> guard(g_resign_lock);
        g_contact_db = db;
        g_has_contact_db.store(true, std::memory_order_release);
        if (g_dump_pending && g_dumped_this_arm.find(db) == g_dumped_this_arm.end()
                && uid_matches_target(db_uid, g_dump_target_uid)) {
            g_dumped_this_arm.insert(db);
            dump_path = g_dump_path;
            dump_gen = g_dump_gen;
            run_dump = !dump_path.empty();
        }
        if (g_prof_pending && pv->colblob && pv->colbytes
                && g_prof_dumped_this_arm.find(db) == g_prof_dumped_this_arm.end()
                && uid_matches_target(db_uid, g_prof_target_uid)) {
            g_prof_dumped_this_arm.insert(db);
            prof_path = g_prof_path;
            prof_gen = g_prof_gen;
            run_prof = !prof_path.empty();
        }
        if (g_roster_pending && pv->colblob && pv->colbytes
                && g_roster_dumped_this_arm.find(db) == g_roster_dumped_this_arm.end()
                && uid_matches_target(db_uid, g_roster_target_uid)) {
            g_roster_dumped_this_arm.insert(db);
            roster_path = g_roster_path;
            roster_gen = g_roster_gen;
            run_roster = !roster_path.empty();
        }
    }
    if (run_dump)   doDump(pv, db, dump_path, dump_gen);
    if (run_prof)   doProfileDump(pv, db, prof_path, prof_gen);
    if (run_roster) doRosterDump(pv, db, roster_path, roster_gen);
    return rc;
}

static int my_prepare_wcdb(void* db, const char* sql, int n, void** ppStmt, const char** tail) {
    return prepare_common(&g_rp[0], db, sql, n, ppStmt, tail);
}
static int my_prepare_cipher(void* db, const char* sql, int n, void** ppStmt, const char** tail) {
    return prepare_common(&g_rp[1], db, sql, n, ppStmt, tail);
}

// 给一个 provider 解析符号并 hook 其 sqlite3_prepare_v2(调用方须持 g_hook_lock; 库没加载返回 false 下次再试)
static bool try_rprov_locked(RProv* p) {
    if (p->installed || p->gave_up) return p->installed;
    uintptr_t base = feishukit_elf::find_base(p->so);
    if (!base) return false;
    void* prep  = feishukit_elf::resolve(base, "sqlite3_prepare_v2");
    p->step     = (step_t)      feishukit_elf::resolve(base, "sqlite3_step");
    p->coltext  = (coltext_t)   feishukit_elf::resolve(base, "sqlite3_column_text");
    p->colblob  = (colblob_t)   feishukit_elf::resolve(base, "sqlite3_column_blob");
    p->colbytes = (colbytes_t)  feishukit_elf::resolve(base, "sqlite3_column_bytes");
    p->finalize = (finalize_t)  feishukit_elf::resolve(base, "sqlite3_finalize");
    p->dbfile   = (dbfilename_t)feishukit_elf::resolve(base, "sqlite3_db_filename");
    LOGI("%s base=%p prepare=%p step=%p coltext=%p dbfile=%p", p->so, (void*) base, prep,
         (void*) p->step, (void*) p->coltext, (void*) p->dbfile);
    if (!prep || !p->step || !p->coltext || !p->finalize || !p->dbfile) {
        LOGE("%s resolve failed, give up this provider", p->so);
        p->gave_up = true;
        return false;
    }
    A64HookFunction(prep, (void*) (p == &g_rp[0] ? my_prepare_wcdb : my_prepare_cipher),
                    (void**) &p->orig_prepare);
    p->prep_ep = prep;
    memcpy(p->hookbytes, prep, 16);
    p->installed = true;
    LOGI("hook installed on %s sqlite3_prepare_v2 @%p", p->so, prep);
    return true;
}

// 由 antirecall.cpp maintain_hook() 周期调用: 防反篡改还原 prepare_v2 hook + 补装迟加载 provider
__attribute__((visibility("hidden"))) void resign_maintain_hooks() {
    std::lock_guard<std::mutex> guard(g_hook_lock);
    if (!g_init_requested) return;
    for (RProv &p : g_rp) {
        if (p.installed && p.prep_ep && memcmp(p.prep_ep, p.hookbytes, 16) != 0) {
            A64HookFunction(p.prep_ep, (void*) (&p == &g_rp[0] ? my_prepare_wcdb : my_prepare_cipher),
                            p.orig_prepare ? nullptr : (void**) &p.orig_prepare);
            memcpy(p.hookbytes, p.prep_ep, 16);
            int c = ++g_prep_rehook;
            if (c <= 40 || c % 100 == 0) {
                LOGI("re-hooked sqlite3_prepare_v2@%s (anti-tamper restored it) #%d", p.so, c);
            }
        }
    }
    static long last_sweep_ms = 0;
    long now = resign_now_ms();
    if (last_sweep_ms == 0 || now - last_sweep_ms >= 2000) {
        last_sweep_ms = now;
        for (RProv &p : g_rp) {
            if (!p.installed && !p.gave_up) {
                if (try_rprov_locked(&p)) g_installed = true;
            }
        }
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_chekayo_larkresign_ResignTracker_nativeInit(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> guard(g_hook_lock);
    g_init_requested = true;
    if (g_installed) return JNI_TRUE;
    bool any = false;
    for (RProv &p : g_rp) if (try_rprov_locked(&p)) any = true;
    if (!any) return JNI_FALSE;   // 库还没加载, Java 继续轮询
    g_installed = true;
    return JNI_TRUE;
}

// 请求一次 dump: 记路径+置 pending, 由下一次 contact.db prepare 就地完成。返回 0。
extern "C" JNIEXPORT jint JNICALL
Java_com_chekayo_larkresign_ResignTracker_nativeArmDump(JNIEnv* env, jclass, jstring jpath) {
    if (!jpath) return -1;
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    if (!path) return -1;
    std::string p(path);
    env->ReleaseStringUTFChars(jpath, path);

    std::string uid = extract_account_uid(p);
    std::lock_guard<std::mutex> guard(g_resign_lock);
    if (uid != g_union_uid) {
        g_union_rows.clear();
        g_union_uid = uid;
    }
    g_dump_path = std::move(p);
    g_dump_target_uid = std::move(uid);
    g_dump_result = -999;
    g_dump_gen++;
    g_dumped_this_arm.clear();   // 本 arm 周期各 contact.db 句柄重新各 dump 一次
    g_dump_pending = true;
    g_any_pending.store(true, std::memory_order_release);
    return 0;
}

// 轮询结果: -999=还没完成; >=0=行数; 负=错误。-1=还没抓到 contact.db 句柄。
extern "C" JNIEXPORT jint JNICALL
Java_com_chekayo_larkresign_ResignTracker_nativePoll(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> guard(g_resign_lock);
    if (!g_contact_db && g_dump_result == -999) return -1;
    return g_dump_result;
}

// 请求一次 V3 富资料批量 dump: 记路径+置 pending, 下一次 contact.db prepare 就地把 blob 落 JSONL。
extern "C" JNIEXPORT jint JNICALL
Java_com_chekayo_larkresign_ResignTracker_nativeArmProfiles(JNIEnv* env, jclass, jstring jpath) {
    if (!jpath) return -1;
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    if (!path) return -1;
    std::string p(path);
    env->ReleaseStringUTFChars(jpath, path);

    std::string uid = extract_account_uid(p);
    std::lock_guard<std::mutex> guard(g_resign_lock);
    g_prof_path = std::move(p);
    g_prof_target_uid = std::move(uid);
    g_prof_result = -999;
    g_prof_rows = 0;
    g_prof_written = 0;
    g_prof_gen++;
    g_prof_dumped_this_arm.clear();   // 本 arm 周期各 contact.db 句柄重新各 dump 一次(首个清空文件)
    g_prof_pending = true;
    g_any_pending.store(true, std::memory_order_release);
    return 0;
}

// 轮询 V3 dump 结果: -999=未完成; >=0=行数; -1=还没抓到 contact.db 句柄。
extern "C" JNIEXPORT jint JNICALL
Java_com_chekayo_larkresign_ResignTracker_nativeProfileResult(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> guard(g_resign_lock);
    if (!g_contact_db && g_prof_result == -999) return -1;
    return g_prof_result;
}

// 请求一次全量花名册 dump: 记路径+置 pending, 下一次 contact.db prepare 就地把全员落 JSONL。
// 不限 is_resigned(覆盖全员, 补齐组织巡游时无 profile 的同事姓名)。
extern "C" JNIEXPORT jint JNICALL
Java_com_chekayo_larkresign_ResignTracker_nativeArmRoster(JNIEnv* env, jclass, jstring jpath) {
    if (!jpath) return -1;
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    if (!path) return -1;
    std::string p(path);
    env->ReleaseStringUTFChars(jpath, path);

    std::string uid = extract_account_uid(p);
    std::lock_guard<std::mutex> guard(g_resign_lock);
    g_roster_path = std::move(p);
    g_roster_target_uid = std::move(uid);
    g_roster_result = -999;
    g_roster_rows = 0;
    g_roster_written = 0;
    g_roster_gen++;
    g_roster_dumped_this_arm.clear();   // 本 arm 周期各 contact.db 句柄重新各 dump 一次(首个清空文件)
    g_roster_pending = true;
    g_any_pending.store(true, std::memory_order_release);
    return 0;
}

// 轮询全量花名册 dump 结果: -999=未完成; >=0=行数; -1=还没抓到 contact.db 句柄。
extern "C" JNIEXPORT jint JNICALL
Java_com_chekayo_larkresign_ResignTracker_nativeRosterResult(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> guard(g_resign_lock);
    if (!g_contact_db && g_roster_result == -999) return -1;
    return g_roster_result;
}
