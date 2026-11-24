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
static std::unordered_set<std::string> g_dumped_this_arm;

// ── V3 富资料批量 dump: 查 chatter_profiles_v3 JOIN chatters, 把每人 blob 原样写 JSONL(hex) ──
// (protobuf 解析太绕, 放 Java 层做; native 只负责就地把 blob 掏出来原子落文件)
static bool g_prof_pending = false;
static std::string g_prof_path;
static std::string g_prof_target_uid;
static int g_prof_result = -999;       // -999=pending, >=0=行数, 负=错误
static uint64_t g_prof_gen = 0;
static std::unordered_set<std::string> g_prof_dumped_this_arm;

// ── 全量花名册 dump: 查【全部】chatters(不限 is_resigned), LEFT JOIN v3 富资料。
//    区别于 doProfileDump(只写有 blob 的人): 本 dump 覆盖全员, 没资料的人也落 name/tenant_id/type,
//    补齐「组织架构巡游」时无 profile 的同事姓名与真实成员类型。输出 JSONL 原子落盘,
//    由 Java ProfileBulk.mergeRoster 解析并入 profiles.json。
static bool g_roster_pending = false;
static std::string g_roster_path;
static std::string g_roster_target_uid;
static int g_roster_result = -999;     // -999=pending, >=0=行数, 负=错误
static uint64_t g_roster_gen = 0;
static std::unordered_set<std::string> g_roster_dumped_this_arm;

// ── 跨句柄 union: 按 id O(1) 去重累积当前账号所有离职行(无 6000 上限, 切账号时清空) ──
static std::unordered_map<std::string, std::string> g_union_rows;
static std::string g_union_uid;

static void update_any_pending_locked() {
    g_any_pending.store(g_dump_pending || g_prof_pending || g_roster_pending, std::memory_order_release);
}

static long resign_now_ms() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long) ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

static bool is_valid_numeric_id(const char* s) {
    if (!s || !*s) return false;
    size_t n = 0;
    for (const char* p = s; *p; ++p) {
        if (*p < '0' || *p > '9') return false;
        if (++n > 24) return false;
    }
    return n > 0;
}

// 从输出路径解析目标账号目录名: 提取 /accounts/<uid>/ 段; 若不存在或为 "unknown" 返回空串(兜底放行)。
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

// 从 contact.db 路径提取 /sdk_storage/<dir>/ 段(兼容飞书 8.x 哈希目录与 7.x 数字 UID 目录)。
static std::string extract_storage_dir(const char* fn) {
    if (!fn) return "";
    std::string path(fn);
    const std::string marker = "/sdk_storage/";
    size_t p = path.find(marker);
    if (p == std::string::npos) return "";
    p += marker.size();
    size_t e = path.find('/', p);
    if (e == std::string::npos || e == p) return "";
    return path.substr(p, e - p);
}

// 多账号隔离: 若目标路径含明确账号标识, 则匹配 recall::owner(数字 UID) 或 sdk_storage 目录名(8.x 哈希);
// 任一方未知(单账号/旧路径)时兜底放行。
static bool uid_matches_target(const std::string &db_uid, const std::string &db_storage, const std::string &target_uid) {
    if (target_uid.empty()) return true;
    if (!db_storage.empty() && db_storage == target_uid) return true;
    if (!db_uid.empty() && db_uid == target_uid) return true;
    if (db_uid.empty() && db_storage.empty()) return true;
    return false;
}

struct ChattersCols {
    std::string type_col;      // "type" 或 "chatter_type", 不存在则为空
    bool has_another_name = false;
    bool has_alias = false;
    bool has_en_us_name = false;
};

// 动态探测 chatters 表列(兼容飞书 7.x / 8.x 不同 schema, 绝不因缺列导致 SELECT 失败)。
static ChattersCols probe_chatters_cols(RProv* pv, void* db) {
    ChattersCols cols;
    void* stmt = nullptr;
    if (pv->orig_prepare(db, "PRAGMA table_info(chatters)", -1, &stmt, nullptr) != 0 || !stmt) {
        return cols;
    }
    while (pv->step(stmt) == 100 /*SQLITE_ROW*/) {
        const unsigned char* nm = pv->coltext(stmt, 1);
        if (!nm) continue;
        const char* s = (const char*) nm;
        if (strcmp(s, "type") == 0) cols.type_col = "type";
        else if (strcmp(s, "chatter_type") == 0 && cols.type_col.empty()) cols.type_col = "chatter_type";
        else if (strcmp(s, "another_name") == 0) cols.has_another_name = true;
        else if (strcmp(s, "alias") == 0) cols.has_alias = true;
        else if (strcmp(s, "en_us_name") == 0) cols.has_en_us_name = true;
    }
    pv->finalize(stmt);
    static std::atomic<bool> logged{false};
    if (!logged.exchange(true, std::memory_order_relaxed)) {
        LOGI("chatters schema probed: type_col=%s another=%d alias=%d en_us=%d",
             cols.type_col.empty() ? "(none)" : cols.type_col.c_str(),
             cols.has_another_name ? 1 : 0, cols.has_alias ? 1 : 0, cols.has_en_us_name ? 1 : 0);
    }
    return cols;
}

static std::string build_alt_name_expr(const ChattersCols &cols) {
    std::string expr = "COALESCE(";
    bool any = false;
    if (cols.has_another_name) { expr += "NULLIF(c.another_name,''),"; any = true; }
    if (cols.has_alias)        { expr += "NULLIF(c.alias,''),"; any = true; }
    if (cols.has_en_us_name)   { expr += "NULLIF(c.en_us_name,''),"; any = true; }
    if (!any) return "''";
    expr += "'')";
    return expr;
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
    ChattersCols cols = probe_chatters_cols(pv, db);
    std::string sql = "SELECT id, name, en_us_name, alias, another_name, tenant_id, update_time, is_frozen "
                      "FROM chatters WHERE is_resigned=1";
    if (!cols.type_col.empty()) {
        sql += " AND (" + cols.type_col + " IS NULL OR " + cols.type_col + "!=2)";
    }
    sql += " ORDER BY update_time DESC";
    void* stmt = nullptr;
    int rc = pv->orig_prepare(db, sql.c_str(), -1, &stmt, nullptr);
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
        if (!is_valid_numeric_id(id)) continue;
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
            if (wrote && !g_dump_target_uid.empty()) {
                g_dump_pending = false;
                update_any_pending_locked();
            }
        }
    }
    LOGI("doDump handle=%p +%d rows, union=%d wrote=%d", db, added, total, wrote ? 1 : 0);
}

// 共享 JSONL 原子写出(供 doProfileDump 与 doRosterDump 共用):
// 写临时文件 -> 校验 fclose -> 原子 rename 发布, 杜绝多线程交错拼串或 Java 读半截文件。
static int dump_profile_jsonl(RProv* pv, void* stmt, const std::string &path, uint64_t gen, bool require_blob) {
    std::string tmp = path + ".tmp." + std::to_string(gen);
    FILE* out = fopen(tmp.c_str(), "w");
    if (!out) return -3;

    static const char HEX[] = "0123456789abcdef";
    std::string hb;
    int added = 0;
    while (pv->step(stmt) == 100 /*SQLITE_ROW*/) {
        const unsigned char* cid   = pv->coltext(stmt, 0);
        const unsigned char* tid   = pv->coltext(stmt, 1);
        const unsigned char* nm    = pv->coltext(stmt, 2);
        const unsigned char* rs    = pv->coltext(stmt, 3);
        const unsigned char* ctype = pv->coltext(stmt, 4);
        const unsigned char* alt   = pv->coltext(stmt, 5);
        const unsigned char* blob  = (const unsigned char*) pv->colblob(stmt, 6);
        int bn = pv->colbytes(stmt, 6);
        if (!is_valid_numeric_id((const char*) cid)) continue;
        if (require_blob && (!blob || bn <= 0)) continue;

        char e_cid[64], e_tid[64], e_nm[256], e_rs[16], e_ct[16];
        jesc(e_cid, sizeof(e_cid), (const char*) cid);
        jesc(e_tid, sizeof(e_tid), tid ? (const char*) tid : "");
        const char* raw_nm = (nm && *nm) ? (const char*) nm : (alt ? (const char*) alt : "");
        jesc(e_nm,  sizeof(e_nm),  raw_nm);
        jesc(e_rs,  sizeof(e_rs),  rs ? (const char*) rs : "");
        jesc(e_ct,  sizeof(e_ct),  ctype ? (const char*) ctype : "");

        fprintf(out,
                "{\"chatter_id\":\"%s\",\"tenant_id\":\"%s\",\"name\":\"%s\",\"is_resigned\":\"%s\",\"chatter_type\":\"%s\",\"hex\":\"",
                e_cid, e_tid, e_nm, e_rs, e_ct);
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

    bool ok = !ferror(out);
    if (fclose(out) != 0) ok = false;
    if (!ok) {
        remove(tmp.c_str());
        return -3;
    }
    if (rename(tmp.c_str(), path.c_str()) != 0) {
        remove(tmp.c_str());
        return -3;
    }
    return added;
}

// V3 富资料批量 dump: chatter_profiles_v3 JOIN chatters -> 原子落 JSONL。
static void doProfileDump(RProv* pv, void* db, const std::string &path, uint64_t gen) {
    ChattersCols cols = probe_chatters_cols(pv, db);
    std::string typeExpr = cols.type_col.empty() ? "''" : ("c." + cols.type_col);
    std::string altExpr = build_alt_name_expr(cols);
    std::string sql =
        "SELECT v.chatter_id, c.tenant_id, c.name, c.is_resigned, " + typeExpr + ", " + altExpr + ", v.profile "
        "FROM chatter_profiles_v3 v JOIN chatters c ON c.id=v.chatter_id "
        "WHERE v.profile IS NOT NULL AND length(v.profile)>0";
    void* stmt = nullptr;
    int rc = pv->orig_prepare(db, sql.c_str(), -1, &stmt, nullptr);
    if (rc != 0 || !stmt) {
        LOGE("doProfileDump prepare rc=%d", rc);
        std::lock_guard<std::mutex> guard(g_resign_lock);
        if (g_prof_gen == gen && g_prof_result == -999) g_prof_result = -2;
        return;
    }

    std::lock_guard<std::mutex> io_guard(g_prof_io_lock);
    {
        std::lock_guard<std::mutex> guard(g_resign_lock);
        if (g_prof_gen != gen) { pv->finalize(stmt); return; }
    }

    int res = dump_profile_jsonl(pv, stmt, path, gen, true);
    pv->finalize(stmt);

    {
        std::lock_guard<std::mutex> guard(g_resign_lock);
        if (g_prof_gen == gen) {
            g_prof_result = res;
            if (res >= 0) {
                g_prof_pending = false;
                update_any_pending_locked();
            }
        }
    }
    LOGI("doProfileDump handle=%p res=%d", db, res);
}

// 全量花名册 dump: 全部 chatters LEFT JOIN v3 -> 原子落 JSONL(覆盖全员 + 真实 chatter_type)。
static void doRosterDump(RProv* pv, void* db, const std::string &path, uint64_t gen) {
    bool hasV3 = false;
    {
        void* chk = nullptr;
        if (pv->orig_prepare(db, "SELECT 1 FROM sqlite_master WHERE type='table' AND name='chatter_profiles_v3' LIMIT 1", -1, &chk, nullptr) == 0 && chk) {
            if (pv->step(chk) == 100 /*ROW*/) hasV3 = true;
            pv->finalize(chk);
        }
    }
    ChattersCols cols = probe_chatters_cols(pv, db);
    std::string typeExpr = cols.type_col.empty() ? "''" : ("c." + cols.type_col);
    std::string altExpr = build_alt_name_expr(cols);
    std::string sql = hasV3
        ? ("SELECT c.id, c.tenant_id, c.name, c.is_resigned, " + typeExpr + ", " + altExpr + ", v.profile "
           "FROM chatters c LEFT JOIN chatter_profiles_v3 v ON v.chatter_id=c.id")
        : ("SELECT c.id, c.tenant_id, c.name, c.is_resigned, " + typeExpr + ", " + altExpr + ", NULL AS profile FROM chatters c");
    void* stmt = nullptr;
    int rc = pv->orig_prepare(db, sql.c_str(), -1, &stmt, nullptr);
    if (rc != 0 || !stmt) {
        LOGE("doRosterDump prepare rc=%d", rc);
        std::lock_guard<std::mutex> guard(g_resign_lock);
        if (g_roster_gen == gen && g_roster_result == -999) g_roster_result = -2;
        return;
    }

    std::lock_guard<std::mutex> io_guard(g_roster_io_lock);
    {
        std::lock_guard<std::mutex> guard(g_resign_lock);
        if (g_roster_gen != gen) { pv->finalize(stmt); return; }
    }

    int res = dump_profile_jsonl(pv, stmt, path, gen, false);
    pv->finalize(stmt);

    {
        std::lock_guard<std::mutex> guard(g_resign_lock);
        if (g_roster_gen == gen) {
            g_roster_result = res;
            if (res >= 0) {
                g_roster_pending = false;
                update_any_pending_locked();
            }
        }
    }
    LOGI("doRosterDump handle=%p res=%d (v3=%d, type_col=%s)", db, res, hasV3 ? 1 : 0,
         cols.type_col.empty() ? "none" : cols.type_col.c_str());
}

// hook: sqlite3_prepare_v2 —— app 每次 prepare 回调都在 DB 自己的线程上。
// 按 contact.db 文件路径去重(避免 WCDB2 连接池多句柄重复 dump 同一库), 原子落盘交给 Java 合并。
static int prepare_common(RProv* pv, void* db, const char* sql, int n, void** ppStmt, const char** tail) {
    int rc = pv->orig_prepare(db, sql, n, ppStmt, tail);
    if (!db || !pv->dbfile) return rc;
    if (g_has_contact_db.load(std::memory_order_acquire)
            && !g_any_pending.load(std::memory_order_acquire)) {
        return rc;
    }
    const char* fn = pv->dbfile(db, "main");
    if (!fn || !strstr(fn, "contact.db")) return rc;
    std::string db_key(fn);
    std::string db_uid = recall::owner(fn);
    std::string db_storage = extract_storage_dir(fn);

    bool run_dump = false, run_prof = false, run_roster = false;
    std::string dump_path, prof_path, roster_path;
    uint64_t dump_gen = 0, prof_gen = 0, roster_gen = 0;
    {
        std::lock_guard<std::mutex> guard(g_resign_lock);
        g_contact_db = db;
        g_has_contact_db.store(true, std::memory_order_release);
        if (g_dump_pending && g_dumped_this_arm.find(db_key) == g_dumped_this_arm.end()
                && uid_matches_target(db_uid, db_storage, g_dump_target_uid)) {
            g_dumped_this_arm.insert(db_key);
            dump_path = g_dump_path;
            dump_gen = g_dump_gen;
            run_dump = !dump_path.empty();
        }
        if (g_prof_pending && pv->colblob && pv->colbytes
                && g_prof_dumped_this_arm.find(db_key) == g_prof_dumped_this_arm.end()
                && uid_matches_target(db_uid, db_storage, g_prof_target_uid)) {
            g_prof_dumped_this_arm.insert(db_key);
            prof_path = g_prof_path;
            prof_gen = g_prof_gen;
            run_prof = !prof_path.empty();
        }
        if (g_roster_pending && pv->colblob && pv->colbytes
                && g_roster_dumped_this_arm.find(db_key) == g_roster_dumped_this_arm.end()
                && uid_matches_target(db_uid, db_storage, g_roster_target_uid)) {
            g_roster_dumped_this_arm.insert(db_key);
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
    g_dumped_this_arm.clear();   // 本 arm 周期每个 contact.db 路径各 dump 一次
    g_dump_pending = true;
    update_any_pending_locked();
    return 0;
}

// 轮询结果: -999=还没完成; >=0=行数; 负=错误。-1=还没抓到 contact.db 句柄。
extern "C" JNIEXPORT jint JNICALL
Java_com_chekayo_larkresign_ResignTracker_nativePoll(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> guard(g_resign_lock);
    if (!g_contact_db && g_dump_result == -999) return -1;
    return g_dump_result;
}

// 请求一次 V3 富资料批量 dump: 记路径+置 pending, 下一次 contact.db prepare 就地把 blob 原子落 JSONL。
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
    g_prof_gen++;
    g_prof_dumped_this_arm.clear();   // 本 arm 周期每个 contact.db 路径仅 dump 一次
    g_prof_pending = true;
    update_any_pending_locked();
    return 0;
}

// 轮询 V3 dump 结果: -999=未完成; >=0=行数; -1=还没抓到 contact.db 句柄。
extern "C" JNIEXPORT jint JNICALL
Java_com_chekayo_larkresign_ResignTracker_nativeProfileResult(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> guard(g_resign_lock);
    if (!g_contact_db && g_prof_result == -999) return -1;
    return g_prof_result;
}

// 请求一次全量花名册 dump: 记路径+置 pending, 下一次 contact.db prepare 就地把全员原子落 JSONL。
// 不限 is_resigned(覆盖全员, 补齐无 profile 的同事姓名与 chatter_type)。
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
    g_roster_gen++;
    g_roster_dumped_this_arm.clear();   // 本 arm 周期每个 contact.db 路径仅 dump 一次
    g_roster_pending = true;
    update_any_pending_locked();
    return 0;
}

// 轮询全量花名册 dump 结果: -999=未完成; >=0=行数; -1=还没抓到 contact.db 句柄。
extern "C" JNIEXPORT jint JNICALL
Java_com_chekayo_larkresign_ResignTracker_nativeRosterResult(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> guard(g_resign_lock);
    if (!g_contact_db && g_roster_result == -999) return -1;
    return g_roster_result;
}
