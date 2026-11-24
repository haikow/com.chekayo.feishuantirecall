// 飞书防撤回 —— 原生 SQL 层 (libsqlcipher.so) inline hook
// 机制(已验证): 撤回 = REPLACE INTO `messages` 且 is_recalled=1, content 被清空。
// 对策: 自己的消息正常落库；他人的原文保留，撤回标记单独持久化，供原气泡绘制虚线。
//
// 符号解析不走 dlopen/dlsym: 模块经 System.load 进的是 app classloader-namespace,
// 与 app 自己加载的 libsqlcipher 不在同一 linker namespace, dlopen(RTLD_NOLOAD) 看不到。
// 改为直接读 /proc/self/maps 找 base, 解析其 ELF .dynsym 得到符号绝对地址 (namespace 无关)。
// tryInstall() 由 Java 线程经 JNI 反复调用 (在 Java 线程上 libc/PLT 正常; 自起 raw pthread 会崩)。
#include <jni.h>
#include <ctype.h>
#include <stdio.h>
#include <stdlib.h>
#include <stdarg.h>
#include <stdint.h>
#include <string.h>
#include <time.h>
#include <pthread.h>
#include <android/log.h>
#include "And64InlineHook.hpp"
#include "elf_resolver.hpp"
#include "recall_filter.h"

#define TAG "antirecall-native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// resign.cpp 内部维护入口(同 .so 链接, 隐藏可见性)
__attribute__((visibility("hidden"))) void resign_maintain_hooks();

// 诊断日志: 追加到飞书私有目录的统一日志文件(与 Java 侧 Diag.java 同一个文件),
// 供终端用户在设置面板一键复制发作者排查“防撤回不生效”。带 MM-dd HH:mm:ss 时间戳。
static volatile int g_diag_log = 0;   // 诊断日志开关(Java 经 nativeSetDiag 设置); 0=不写文件

// 业务文件目录与用户可见诊断日志目录分离；日志统一放 files 根目录。
static pthread_mutex_t g_dir_lock = PTHREAD_MUTEX_INITIALIZER;
static char g_data_dir[256] = "/data/data/com.ss.android.lark/files";
static char g_log_dir[256] = "/data/data/com.ss.android.lark/files";

static void copy_data_dir(char *out, size_t outsz) {
    pthread_mutex_lock(&g_dir_lock);
    strncpy(out, g_data_dir, outsz - 1);
    out[outsz - 1] = 0;
    pthread_mutex_unlock(&g_dir_lock);
}

static void copy_log_dir(char *out, size_t outsz) {
    pthread_mutex_lock(&g_dir_lock);
    strncpy(out, g_log_dir, outsz - 1);
    out[outsz - 1] = 0;
    pthread_mutex_unlock(&g_dir_lock);
}

static void flog(const char *fmt, ...) {
    if (!g_diag_log) return;
    char ldir[256];
    copy_log_dir(ldir, sizeof ldir);
    char _p[320]; snprintf(_p, sizeof _p, "%s/fucklark_log.txt", ldir);
    FILE *f = fopen(_p, "a");
    if (!f) return;
    time_t t = time(nullptr);
    struct tm tmv;
    localtime_r(&t, &tmv);
    char ts[24];
    strftime(ts, sizeof ts, "%m-%d %H:%M:%S", &tmv);
    fputs(ts, f);
    fputc(' ', f);
    va_list ap;
    va_start(ap, fmt);
    vfprintf(f, fmt, ap);
    va_end(ap);
    fputc('\n', f);
    fclose(f);
}

static long now_ms() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long) ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

typedef int         (*step_t)(void*);
typedef const char* (*sql_t)(void*);
typedef char*       (*expsql_t)(void*);
typedef void        (*free_t)(void*);
typedef int         (*bind64_t)(void*, int, long long);
typedef int         (*bindblob_t)(void*, int, const void*, int, void*);
typedef int         (*bindtext_t)(void*, int, const char*, int, void*);
typedef int         (*prepare_t)(void*, const char*, int, void**, const char**);
typedef int         (*finalize_t)(void*);
typedef long long   (*coli64_t)(void*, int);
typedef const void* (*colblob_t)(void*, int);
typedef int         (*colbytes_t)(void*, int);
typedef void*       (*dbhandle_t)(void*);
typedef const char* (*dbfilename_t)(void*, const char*);
typedef int         (*autocommit_t)(void*);

// ── sqlite provider ──────────────────────────────────────────────────────
// 飞书 8.x 起消息库改走 libwcdb2.so 自带的 sqlite(260 个导出符号), libsqlcipher.so 只服务
// 旧组件库; 7.x 则只有 libsqlcipher。两个库各 hook 一份 sqlite3_step —— 语句/句柄严格配
// 本库符号使用(两库的 sqlite3_stmt 内部布局互不兼容), 逻辑共用 step_common()。
struct SqlProv {
    const char *so;
    bool gave_up;           // 库已加载但符号不全, 放弃
    bool installed;         // step hook 已装
    void *step_ep;          // sqlite3_step 入口(检测反篡改还原用)
    unsigned char hookbytes[16];
    step_t    orig_step;
    sql_t     sql;
    expsql_t  exp;
    free_t    free_fn;
    bind64_t  bind64;
    bindblob_t bind_blob;
    bindtext_t bind_text;
    dbhandle_t db_handle;
    prepare_t prepare;
    finalize_t finalize;
    coli64_t   col_i64;
    colblob_t  col_blob;
    colbytes_t col_bytes;
    dbfilename_t db_filename;
    autocommit_t autocommit;
    const char* (*error)(void*);
    void* (*next_stmt)(void*, void*);
    int (*busy)(void*);
    int (*reset)(void*);
    void* (*db_mutex)(void*);
    void (*mutex_enter)(void*);
    void (*mutex_leave)(void*);
};
static recall::Store g_recall_store;
// Bounded diagnostics contain stages/errors only, never message content or IDs.
static void recall_trace(const char *stage) {
    static std::mutex mutex;
    static std::set<std::string> seen;
    std::lock_guard<std::mutex> guard(mutex);
    if (!g_diag_log) return;
    if (seen.size() < 32 && seen.insert(stage).second) {
        LOGI("RECALL-STATE: %s", stage);
        flog("防撤回锚点状态: %s", stage);
    }
}
static recall::Api recall_api(SqlProv *p) {
    return {p->orig_step, p->prepare, p->finalize, p->col_i64, p->db_filename, p->autocommit, p->error, recall_trace,
        p->next_stmt, p->busy, p->reset,
        p->db_mutex, p->mutex_enter, p->mutex_leave};
}
static SqlProv g_prov[2] = {
    { "libwcdb2.so" },       // 8.x 消息库(优先; 7.x 无此库则恒未装, 无害)
    { "libsqlcipher.so" },   // 7.x 消息库 + 8.x 旧组件库
};
static std::mutex g_prov_mutex;
static pthread_mutex_t g_cache_lock = PTHREAD_MUTEX_INITIALIZER;
// chatters 表所在连接与其所属 provider(两库句柄不通用, 必须成对使用)
static void *g_chatters_db = nullptr;          // chatters 表所在连接(与 messages 不同库)
static SqlProv *g_chatters_pv = nullptr;
static int my_step_wcdb(void *), my_step_cipher(void *);   // 前向声明
static bool try_prov_locked(SqlProv *p);                   // 前向声明(maintain_hook 补装迟加载库用)
static long long g_kicked_chat = 0;            // 最近被踢/清群的 chat_id
static int g_export_pending = 0;               // 1=待在 messages 连接上导出
static int g_neutralized = 0;
static volatile int g_recall_enabled = 1;   // fuck lark 开关: 0=停用防撤回中和(设置面板可切)
static volatile int g_keep_kicked = 0;       // 1=保留被踢群聊天记录(拦清群 DELETE + 窗口内拦删消息)
static volatile long long g_kick_window = 0; // 整群拆除信号后开的丢弃窗口(ms 时间戳): 窗口内的 DELETE FROM messages 丢弃
// ── 退群/被移除 提醒 ──
static volatile int g_leave_notify = 0;      // 1=检测"谁退群/被移除"并弹 Toast + 记录
static char g_leave_q[16][256];              // 待弹事件队列(native 检测 -> Java nativePollLeaveEvent 取走)
static int g_leave_head = 0, g_leave_tail = 0;
static pthread_mutex_t g_leave_lock = PTHREAD_MUTEX_INITIALIZER;
static long long g_leave_seen[128];          // 去重: 最近已处理的成员变动消息 id (受 g_cache_lock 保护)
static int g_leave_seen_n = 0;
static long long g_mleave_seen[128];         // 去重: 已确认(真退群)的 (chat^chatter) 键 (受 g_cache_lock 保护)
static int g_mleave_n = 0;
// 静默退群"删后重插"窗口过滤: DELETE 先缓存, 窗口内被 REPLACE 插回 = re-sync/改角色 -> 丢弃;
// 超窗仍没插回 = 真·离职/被移除 -> 才记 leave_log。消灭"打开群/设管理员"造成的批量假退群。
struct PendLeave { long long chat; long long chatter; long long ts; char msg[256]; };
static PendLeave g_pend[64];
static int g_pend_n = 0;
static const long long LEAVE_WIN = 6;        // 确认窗口(秒)
static bool g_installed = false;
static int g_step_rehook = 0;

// ── messages 表 REPLACE 语句的列位(0-based) —— 运行时自适应 ──────────────
// 7.x 固定列序: 0 id, 1 chat_id, 9 content, 12 cid, 14 is_recalled, 30 is_visible。
// 8.x 起 ORM 的 SQL 改为运行时拼接、liblark.so 里无静态模板, 列序随时可变 -> 不再写死:
// 见到 messages 的 REPLACE 语句时现场解析其 SQL 列名表(REPLACE INTO `messages`
// (`id`,`chat_id`,...) VALUES (...)), 解析不到(无列名表/认不出)回退 7.x 旧列序。
// 绑定参数序 = 列位 + 1 (SQLite 1-based)。
struct MsgCols { int id, chat, content, cid, recalled, visible; };   // -1 = 该列不在本语句列名表
static const MsgCols COLS_V7 = { 0, 1, 9, 12, 14, 30 };              // 7.x 实测列序(兜底)
static MsgCols g_mc = COLS_V7;
static int g_mc_parsed = 0;             // 1=已从运行时 SQL 解析出列序(非兜底值)
static pthread_mutex_t g_mc_lock = PTHREAD_MUTEX_INITIALIZER;
static unsigned g_mc_hash = 0;          // 最近成功解析的模板哈希(同模板只解析一次)

static MsgCols get_msg_cols() {
    pthread_mutex_lock(&g_mc_lock);
    MsgCols copy = g_mc;
    pthread_mutex_unlock(&g_mc_lock);
    return copy;
}

static unsigned str_hash32(const char *s) {
    unsigned h = 2166136261u;
    for (; *s; ++s) { h ^= (unsigned char) *s; h *= 16777619u; }
    return h;
}

// 判定是否针对 `messages` 表的 REPLACE 写入(兼容 REPLACE INTO / INSERT OR REPLACE INTO、
// 反引号有无、空格变体)。out_cols 非空时回传列名表起点; 语句无列名表(直接 VALUES)回传 null。
static bool is_msg_replace(const char *t, const char **out_cols) {
    const char *p = nullptr;
    if (strncmp(t, "REPLACE INTO", 12) == 0) p = t + 12;
    else if (strncmp(t, "INSERT OR REPLACE INTO", 22) == 0) p = t + 22;
    else return false;
    while (*p == ' ') p++;
    if (*p == '`') {
        if (strncmp(p + 1, "messages`", 9) != 0) return false;
        p += 10;
    } else {
        if (strncmp(p, "messages", 8) != 0) return false;
        p += 8;
    }
    if (out_cols) *out_cols = nullptr;
    while (*p == ' ') p++;
    if (*p == '(') { if (out_cols) *out_cols = p + 1; return true; }
    return strncmp(p, "VALUES", 6) == 0;   // 无列名表: 值序=建表列序, 调用方走兜底列位
}

// 解析列名表(逗号分隔, 列名可带反引号/空白), 记录关注列的 0-based 列位, 返回总列数。
// 只有 id 与 is_recalled 都在(整行 REPLACE)才算成功 —— 部分列 REPLACE 不覆盖缓存列位。
static int parse_msg_cols(const char *cols, MsgCols *out) {
    out->id = out->chat = out->content = out->cid = out->recalled = out->visible = -1;
    int n = 0;
    const char *p = cols;
    while (*p && *p != ')') {
        const char *s = p;
        while (*p && *p != ',' && *p != ')') p++;
        while (s < p && (*s == ' ' || *s == '`')) s++;
        const char *e2 = p;
        while (e2 > s && (e2[-1] == ' ' || e2[-1] == '`')) e2--;
        char name[40];
        int l = (int) (e2 - s);
        if (l > (int) sizeof name - 1) l = (int) sizeof name - 1;
        if (l > 0) {
            memcpy(name, s, (size_t) l); name[l] = 0;
            if      (strcmp(name, "id") == 0)          out->id = n;
            else if (strcmp(name, "chat_id") == 0)     out->chat = n;
            else if (strcmp(name, "content") == 0)     out->content = n;
            else if (strcmp(name, "cid") == 0)         out->cid = n;
            else if (strcmp(name, "is_recalled") == 0) out->recalled = n;
            else if (strcmp(name, "is_visible") == 0)  out->visible = n;
        }
        n++;
        if (*p == ',') p++;
    }
    return (out->id >= 0 && out->recalled >= 0) ? n : 0;
}

// 模板变化时重解析列位并缓存。撤回改绑(id/cid)、is_recalled 判定、is_visible 改绑
// 全部使用解析出的列位 —— 飞书升级改列序不再需要跟版本改代码。
static void adapt_msg_cols(const char *tmpl, const char *cols) {
    unsigned h = str_hash32(tmpl);
    if (h == __atomic_load_n(&g_mc_hash, __ATOMIC_ACQUIRE)) return;   // 快路径: 同模板不重复解析
    pthread_mutex_lock(&g_mc_lock);
    if (h == g_mc_hash) { pthread_mutex_unlock(&g_mc_lock); return; }
    MsgCols mc;
    int n = cols ? parse_msg_cols(cols, &mc) : 0;
    if (n > 0) {
        bool changed = mc.id != g_mc.id || mc.chat != g_mc.chat || mc.content != g_mc.content
                    || mc.cid != g_mc.cid || mc.recalled != g_mc.recalled || mc.visible != g_mc.visible;
        bool first = (g_mc_parsed == 0);            // 首次解析: 无论是否与7.x一致都留痕, 便于跟版本排查
        g_mc = mc;
        g_mc_parsed = 1;
        __atomic_store_n(&g_mc_hash, h, __ATOMIC_RELEASE);   // 仅成功解析的整行模板进缓存
        if (changed || first) {
            LOGI("ANTIRECALL: messages 列位%s(共%d列) id=%d chat_id=%d content=%d cid=%d is_recalled=%d is_visible=%d",
                 changed ? "已自适应" : "解析确认与7.x一致", n, mc.id, mc.chat, mc.content, mc.cid, mc.recalled, mc.visible);
            flog("防撤回: 本机飞书 messages 列序%s(共%d列): id=%d chat_id=%d content=%d cid=%d is_recalled=%d is_visible=%d",
                 changed ? "与 7.x 不同, 已自适应" : "经解析与 7.x 一致", n, mc.id, mc.chat, mc.content, mc.cid, mc.recalled, mc.visible);
        }
    } else {
        // 部分列 REPLACE(无 id/is_recalled)或无列名表: 不动既有列位。首次遇到记一条。
        static bool warned = false;
        if (!warned) {
            warned = true;
            flog("防撤回: 见到无法解析列名表的 messages REPLACE, 沿用现有列位(7.x兜底/已解析值) %.160s", tmpl);
        }
    }
    pthread_mutex_unlock(&g_mc_lock);
}

// 取 expanded SQL 中 VALUES(...) 第 idx(0-based) 个字段的区间 [fs,fe)。
// 兼容引号(含 '' 转义)、嵌套括号、x'..' blob; 找不到或序越界返回 false。
static bool values_field(const char *e, int idx, const char **fs, const char **fe) {
    if (idx < 0 || !e) return false;
    const char *v = strstr(e, "VALUES");
    if (!v) return false;
    while (*v && *v != '(') v++;
    if (!*v) return false;
    const char *p = v + 1;
    int field = 0; bool inq = false;
    const char *start = p;
    while (*p) {
        char c = *p;
        if (inq) { if (c == '\'') inq = false; p++; continue; }
        if (c == '\'') { inq = true; p++; continue; }
        if (c == '(') {                    // 嵌套括号(展开出的函数值等)整段跳过
            int depth = 1; p++;
            while (*p && depth > 0) {
                if (*p == '\'') inq = !inq;
                else if (!inq && *p == '(') depth++;
                else if (!inq && *p == ')') depth--;
                p++;
            }
            continue;
        }
        if (c == ',' || c == ')') {
            if (field == idx) { *fs = start; *fe = p; return true; }
            if (c == ')') return false;
            field++; p++; start = p;
            continue;
        }
        p++;
    }
    return false;
}
// values_field 区间转 long long(容忍前导空白); 非数字开头返回 0。
static long long field_ll(const char *fs, const char *fe) {
    while (fs < fe && (*fs == ' ' || *fs == '\t')) fs++;
    long long v = 0;
    while (fs < fe && *fs >= '0' && *fs <= '9') { v = v * 10 + (*fs - '0'); fs++; }
    return v;
}

static bool starts_with_ci_word(const char *s, const char *word) {
    while (*word) {
        if (tolower((unsigned char) *s) != *word) return false;
        ++s;
        ++word;
    }
    unsigned char c = (unsigned char) *s;
    return c == 0 || (!isalnum(c) && c != '_');
}

// 仅对可能触发撤回保护的写语句(REPLACE / INSERT / UPDATE)调用 sqlite3_expanded_sql, 跳过 SELECT 等读路径。
static bool is_recall_write_sql(const char *sql) {
    if (!sql) return false;
    const char *p = sql;
    while (*p) {
        if (isspace((unsigned char) *p)) { ++p; continue; }
        if (p[0] == '-' && p[1] == '-') {
            p += 2;
            while (*p && *p != '\n') ++p;
            continue;
        }
        if (p[0] == '/' && p[1] == '*') {
            const char *end = strstr(p + 2, "*/");
            if (!end) return false;
            p = end + 2;
            continue;
        }
        break;
    }
    return starts_with_ci_word(p, "replace")
        || starts_with_ci_word(p, "insert")
        || starts_with_ci_word(p, "update");
}

// [p,p+len) 是否全是可读文本(ASCII 可打印/制表换行 + 合法 UTF-8)
static int is_text(const unsigned char *p, int len) {
    int i = 0;
    while (i < len) {
        unsigned char c = p[i];
        if (c == 0x09 || c == 0x0a || c == 0x0d || (c >= 0x20 && c < 0x7f)) i++;
        else if (c >= 0xC2 && c <= 0xDF && i + 1 < len && (p[i+1] & 0xC0) == 0x80) i += 2;
        else if (c >= 0xE0 && c <= 0xEF && i + 2 < len && (p[i+1] & 0xC0) == 0x80 && (p[i+2] & 0xC0) == 0x80) i += 3;
        else if (c >= 0xF0 && c <= 0xF4 && i + 3 < len && (p[i+1] & 0xC0) == 0x80 && (p[i+2] & 0xC0) == 0x80 && (p[i+3] & 0xC0) == 0x80) i += 4;
        else return 0;
    }
    return 1;
}
static int hexval(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}
// 递归解析 protobuf, 抠字符串字段写进缓冲 out[*pos](跳过 tag/长度/数字框架)。
static void pb_extract_buf(char *out, int outsz, int *pos, const unsigned char *b, int len, int depth) {
    if (depth > 6 || len <= 0) return;
    int i = 0;
    while (i < len) {
        unsigned long long tag = 0; int shift = 0, ok = 0;
        while (i < len && shift <= 63) { unsigned char c = b[i++]; tag |= (unsigned long long)(c & 0x7f) << shift; shift += 7; if (!(c & 0x80)) { ok = 1; break; } }
        if (!ok) return;
        int wt = tag & 7;
        if (wt == 0) { while (i < len && (b[i] & 0x80)) i++; if (i < len) i++; }
        else if (wt == 1) i += 8;
        else if (wt == 5) i += 4;
        else if (wt == 2) {
            unsigned long long L = 0; shift = 0; ok = 0;
            while (i < len && shift <= 63) { unsigned char c = b[i++]; L |= (unsigned long long)(c & 0x7f) << shift; shift += 7; if (!(c & 0x80)) { ok = 1; break; } }
            if (!ok || L > (unsigned long long)(len - i)) return;
            const unsigned char *p = b + i;
            if (L >= 2 && is_text(p, (int) L)) {
                for (unsigned long long k = 0; k < L && *pos < outsz - 1; k++) out[(*pos)++] = p[k];
                if (*pos < outsz - 1) out[(*pos)++] = ' ';
            } else pb_extract_buf(out, outsz, pos, p, (int) L, depth + 1);
            i += (int) L;
        } else return;
    }
}

static void record_chatters_db(void *db, SqlProv *pv) {
    pthread_mutex_lock(&g_cache_lock);
    g_chatters_db = db;
    g_chatters_pv = pv;
    pthread_mutex_unlock(&g_cache_lock);
}

// 查发送人昵称(chatters.name), 带缓存; id=1 视为系统。
static struct { long long id; char name[64]; } g_cn[128];
static int g_cn_n = 0;
static void get_chatter_name(SqlProv *pv, void *db, long long id, char *out, int outsz) {
    out[0] = 0;
    if (id == 1) { strncpy(out, "系统", outsz - 1); out[outsz - 1] = 0; return; }
    void *cdb = nullptr;
    SqlProv *cpv = nullptr;
    pthread_mutex_lock(&g_cache_lock);
    for (int i = 0; i < g_cn_n && i < 128; i++) {
        if (g_cn[i].id == id) {
            strncpy(out, g_cn[i].name, outsz - 1);
            out[outsz - 1] = 0;
            pthread_mutex_unlock(&g_cache_lock);
            return;
        }
    }
    cdb = g_chatters_db;
    cpv = g_chatters_pv;
    pthread_mutex_unlock(&g_cache_lock);

    if (!cdb) { cdb = db; cpv = pv; }   // chatters 在单独连接; 连接与符号必须同库配对
    char c_name[64] = {0}, c_alias[64] = {0}, c_another[64] = {0};
    int prep = -1, rc = -1;
    if (cdb && cpv && cpv->prepare && cpv->finalize && cpv->col_blob && cpv->col_bytes && cpv->orig_step && cpv->bind64) {
        void *st = nullptr;
        prep = cpv->prepare(cdb, "SELECT name, alias, another_name FROM chatters WHERE id = ?", -1, &st, nullptr);
        if (prep == 0 && st) {
            cpv->bind64(st, 1, id);
            rc = cpv->orig_step(st);
            if (rc == 100) {
                const unsigned char *v; int l;
                v = (const unsigned char *) cpv->col_blob(st, 0); l = cpv->col_bytes(st, 0); if (v && l > 0) { int cp = l < 63 ? l : 63; memcpy(c_name, v, cp); c_name[cp] = 0; }
                v = (const unsigned char *) cpv->col_blob(st, 1); l = cpv->col_bytes(st, 1); if (v && l > 0) { int cp = l < 63 ? l : 63; memcpy(c_alias, v, cp); c_alias[cp] = 0; }
                v = (const unsigned char *) cpv->col_blob(st, 2); l = cpv->col_bytes(st, 2); if (v && l > 0) { int cp = l < 63 ? l : 63; memcpy(c_another, v, cp); c_another[cp] = 0; }
            }
            cpv->finalize(st);
        }
    }
    // 优先备注名(alias) > 群昵称(another_name) > 真名(name)
    const char *pick = c_alias[0] ? c_alias : (c_another[0] ? c_another : c_name);
    if (pick[0]) { strncpy(out, pick, outsz - 1); out[outsz - 1] = 0; }
    if (g_diag_log) { static int cd = 0; if (__atomic_add_fetch(&cd, 1, __ATOMIC_RELAXED) <= 30) flog("CHATTER-DIAG id=%lld prep=%d step=%d name=[%s] alias=[%s] another=[%s]", (long long) id, prep, rc, c_name, c_alias, c_another); }
    if (!out[0]) snprintf(out, outsz, "%lld", (long long) id);

    pthread_mutex_lock(&g_cache_lock);
    int idx = -1;
    for (int i = 0; i < g_cn_n && i < 128; i++) {
        if (g_cn[i].id == id) { idx = i; break; }
    }
    if (idx < 0) idx = g_cn_n < 128 ? g_cn_n++ : (int)(((unsigned long long) id) % 128);
    g_cn[idx].id = id;
    strncpy(g_cn[idx].name, out, 63);
    g_cn[idx].name[63] = 0;
    pthread_mutex_unlock(&g_cache_lock);
}
// 群名缓存: 平时从 UPDATE chats SET name=? 解析存下(踢群时 chats.name 会被清, 故要提前缓存)
static struct { long long chat; char name[96]; } g_names[64];
static int g_names_n = 0;
static void cache_name(long long chat, const char *nm) {
    if (!chat || !nm || !nm[0]) return;
    pthread_mutex_lock(&g_cache_lock);
    for (int i = 0; i < g_names_n && i < 64; i++) {
        if (g_names[i].chat == chat) {
            strncpy(g_names[i].name, nm, 95);
            g_names[i].name[95] = 0;
            pthread_mutex_unlock(&g_cache_lock);
            return;
        }
    }
    int idx = g_names_n < 64 ? g_names_n++ : (int)(((unsigned long long) chat) % 64);
    g_names[idx].chat = chat;
    strncpy(g_names[idx].name, nm, 95);
    g_names[idx].name[95] = 0;
    pthread_mutex_unlock(&g_cache_lock);
}
static bool lookup_name(long long chat, char *out, size_t outsz) {
    if (!out || outsz == 0) return false;
    out[0] = 0;
    pthread_mutex_lock(&g_cache_lock);
    for (int i = 0; i < g_names_n && i < 64; i++) {
        if (g_names[i].chat == chat) {
            strncpy(out, g_names[i].name, outsz - 1);
            out[outsz - 1] = 0;
            pthread_mutex_unlock(&g_cache_lock);
            return out[0] != 0;
        }
    }
    pthread_mutex_unlock(&g_cache_lock);
    return false;
}
// 退群提醒: 消息 id 去重(同一条成员变动系统消息在 re-sync 时会重复写入)。
static bool leave_seen(long long id) {
    pthread_mutex_lock(&g_cache_lock);
    for (int i = 0; i < g_leave_seen_n && i < 128; i++) {
        if (g_leave_seen[i] == id) {
            pthread_mutex_unlock(&g_cache_lock);
            return true;
        }
    }
    int idx = g_leave_seen_n < 128 ? g_leave_seen_n++ : (int) (((unsigned long long) id) % 128);
    g_leave_seen[idx] = id;
    pthread_mutex_unlock(&g_cache_lock);
    return false;
}
// 已确认(真退群)集合: 仅查 / 仅标记 (分开, 供"删后重插"窗口过滤: 缓存时只查、确认时才标记)。
static bool mleave_has(long long key) {
    pthread_mutex_lock(&g_cache_lock);
    for (int i = 0; i < g_mleave_n && i < 128; i++) {
        if (g_mleave_seen[i] == key) {
            pthread_mutex_unlock(&g_cache_lock);
            return true;
        }
    }
    pthread_mutex_unlock(&g_cache_lock);
    return false;
}
static void mleave_mark(long long key) {
    pthread_mutex_lock(&g_cache_lock);
    for (int i = 0; i < g_mleave_n && i < 128; i++) {
        if (g_mleave_seen[i] == key) {
            pthread_mutex_unlock(&g_cache_lock);
            return;
        }
    }
    int idx = g_mleave_n < 128 ? g_mleave_n++ : (int) (((unsigned long long) key) % 128);
    g_mleave_seen[idx] = key;
    pthread_mutex_unlock(&g_cache_lock);
}
// 确认为真·静默退群 -> 写 leave_log + 标记去重。
static void leave_commit(long long chat, long long chatter, const char *msg) {
    char tstr[24]; time_t tt = time(nullptr); struct tm tmv; localtime_r(&tt, &tmv);
    strftime(tstr, sizeof tstr, "%m-%d %H:%M", &tmv);
    char ddir[256]; copy_data_dir(ddir, sizeof ddir);
    char _lp[320]; snprintf(_lp, sizeof _lp, "%s/leave_log.txt", ddir);
    FILE *lf = fopen(_lp, "a");
    if (lf) { fprintf(lf, "%s\t%s\n", tstr, msg); fclose(lf); }
    mleave_mark(chat ^ (chatter * 1000003LL));
    flog("静默退群(确认): %s", msg);
}
// 缓存一条待确认删除(同一 chat,chatter 已在缓存则跳过)。
static void pend_add(long long chat, long long chatter, const char *msg) {
    pthread_mutex_lock(&g_leave_lock);
    bool dup = false;
    for (int i = 0; i < g_pend_n; i++) if (g_pend[i].chat == chat && g_pend[i].chatter == chatter) { dup = true; break; }
    if (!dup && g_pend_n < 64) {
        g_pend[g_pend_n].chat = chat; g_pend[g_pend_n].chatter = chatter;
        g_pend[g_pend_n].ts = time(nullptr);
        strncpy(g_pend[g_pend_n].msg, msg, 255); g_pend[g_pend_n].msg[255] = 0;
        __atomic_store_n(&g_pend_n, g_pend_n + 1, __ATOMIC_RELEASE);
    }
    pthread_mutex_unlock(&g_leave_lock);
}
// 成员行被 REPLACE 插回 -> 取消对应待确认删除(re-sync / 改角色, 非真退群)。
static void pend_cancel(long long chat, long long chatter) {
    pthread_mutex_lock(&g_leave_lock);
    for (int i = 0; i < g_pend_n; i++) {
        if (g_pend[i].chat == chat && g_pend[i].chatter == chatter) {
            flog("re-sync/改角色 取消退群误报: chat=%lld chatter=%lld 「%s」", chat, chatter, g_pend[i].msg);
            int next_n = g_pend_n - 1;
            g_pend[i] = g_pend[next_n];   // 末位覆盖删除
            __atomic_store_n(&g_pend_n, next_n, __ATOMIC_RELEASE);
            break;
        }
    }
    pthread_mutex_unlock(&g_leave_lock);
}
// 冲刷超窗仍未被插回的 = 真退群 -> 提交(持锁内直接写, IO 短且低频)。
static void pend_flush() {
    pthread_mutex_lock(&g_leave_lock);
    long long now = time(nullptr);
    for (int i = 0; i < g_pend_n; ) {
        if (now - g_pend[i].ts >= LEAVE_WIN) {
            leave_commit(g_pend[i].chat, g_pend[i].chatter, g_pend[i].msg);
            int next_n = g_pend_n - 1;
            g_pend[i] = g_pend[next_n];   // 末位覆盖删除, 不推进 i
            __atomic_store_n(&g_pend_n, next_n, __ATOMIC_RELEASE);
        } else i++;
    }
    pthread_mutex_unlock(&g_leave_lock);
}
static void leave_enqueue(const char *s) {
    pthread_mutex_lock(&g_leave_lock);
    int nt = (g_leave_tail + 1) % 16;
    if (nt != g_leave_head) { strncpy(g_leave_q[g_leave_tail], s, 255); g_leave_q[g_leave_tail][255] = 0; g_leave_tail = nt; }
    pthread_mutex_unlock(&g_leave_lock);
}
// 查群名(chats.name) —— 兜底(踢群时多半已被清空)
static void query_chat_name(SqlProv *pv, void *db, long long chat, char *out, int outsz) {
    out[0] = 0;
    if (!pv || !pv->prepare || !pv->finalize || !pv->col_blob || !pv->col_bytes || !pv->orig_step || !pv->bind64) return;
    void *st = nullptr;
    if (pv->prepare(db, "SELECT name FROM chats WHERE id = ?", -1, &st, nullptr) != 0 || !st) return;
    pv->bind64(st, 1, chat);
    if (pv->orig_step(st) == 100) {
        const unsigned char *nm = (const unsigned char *) pv->col_blob(st, 0);
        int nl = pv->col_bytes(st, 0);
        if (nm && nl > 0) { int cp = nl < outsz - 1 ? nl : outsz - 1; memcpy(out, nm, cp); out[cp] = 0; }
    }
    pv->finalize(st);
}
// 在 text 里找 "<key>" 紧跟数字的那处, 取出 id(系统消息内含 from_user/to_chatters 的 id)。
static long long find_id_after(const char *text, const char *key) {
    const char *p = text; int kl = (int) strlen(key);
    while ((p = strstr(p, key))) {
        const char *q = p + kl;
        if (*q >= '0' && *q <= '9') { long long v = 0; while (*q >= '0' && *q <= '9') { v = v * 10 + (*q - '0'); q++; } return v; }
        p += kl;
    }
    return 0;
}
// 把系统消息(邀请/移除/改群名)拼成清晰中文: "cheky 邀请了 林觉圣"。
static void build_system_line(SqlProv *pv, const char *text, void *db, char *out, int outsz) {
    long long fid = find_id_after(text, "from_user ");
    long long tid = find_id_after(text, "to_chatters ");
    char fn[64] = {0}, tn[64] = {0};
    if (fid) get_chatter_name(pv, db, fid, fn, sizeof fn);
    if (tid) get_chatter_name(pv, db, tid, tn, sizeof tn);
    if (strstr(text, "updated the group name")) {
        const char *gn = strstr(text, "group_name "); char nm[96] = {0};
        if (gn) { gn += 11; int k = 0; while (gn[k] && gn[k] != ' ' && k < 95) { nm[k] = gn[k]; k++; } nm[k] = 0; }
        snprintf(out, outsz, "%s 把群名改为 %s", fn[0] ? fn : "某人", nm);
        return;
    }
    const char *verb = strstr(text, "invited") ? "邀请了" : (strstr(text, "removed") ? "移除了" : nullptr);
    if (verb && fn[0] && tn[0]) { snprintf(out, outsz, "%s %s %s", fn, verb, tn); return; }
    // 兜底: 去掉 id/占位, 保留可读片段
    const char *g = strstr(text, "{"); snprintf(out, outsz, "%s", g ? g : text);
}
// 导出被踢群聊天记录: 在 messages 所在连接(sqlcipher 已解密)上跑 SELECT, 解析 protobuf 抠文本写 txt。
// 必须在拥有该连接的线程上执行 -> 由 my_step 在遇到 messages 语句时(同线程同连接)调用。
static void do_export(SqlProv *pv, void *db, long long kicked_chat) {
    if (!pv || !db || kicked_chat == 0 || !pv->prepare || !pv->finalize || !pv->col_i64 || !pv->col_blob || !pv->col_bytes || !pv->orig_step || !pv->bind64)
        return;
    char gname[256] = {0};
    if (!lookup_name(kicked_chat, gname, sizeof gname))   // 优先用平时缓存的群名(踢群时 chats.name 已被清)
        query_chat_name(pv, db, kicked_chat, gname, sizeof gname);
    void *st = nullptr;
    const char *q = "SELECT id, from_id, create_time, content FROM messages WHERE chat_id = ? ORDER BY create_time ASC";
    if (pv->prepare(db, q, -1, &st, nullptr) != 0 || !st) return;
    pv->bind64(st, 1, kicked_chat);
    char ddir[256]; copy_data_dir(ddir, sizeof ddir);
    char path[320];
    snprintf(path, sizeof path, "%s/kicked_%lld.txt", ddir, kicked_chat);
    FILE *f = fopen(path, "w");
    if (!f) { pv->finalize(st); return; }
    // 结构化: 首行群名; 之后每条 = 时间 \t 昵称 \t 文本 (Java 侧解析成聊天气泡)
    fprintf(f, "群: %s\n", gname[0] ? gname : "(未取到群名)");
    int n = 0;
    while (pv->orig_step(st) == 100) {   // SQLITE_ROW=100
        long long from = pv->col_i64(st, 1), ct = pv->col_i64(st, 2);
        const unsigned char *blob = (const unsigned char *) pv->col_blob(st, 3);
        int blen = pv->col_bytes(st, 3);
        char text[4096]; int tp = 0; text[0] = 0;
        if (blob && blen > 0) pb_extract_buf(text, sizeof text, &tp, blob, blen, 0);
        text[tp < (int) sizeof text ? tp : (int) sizeof text - 1] = 0;
        while (tp > 0 && (text[tp - 1] == ' ' || text[tp - 1] == '\n' || text[tp - 1] == '\t')) text[--tp] = 0;
        for (int k = 0; k < tp; k++) if (text[k] == '\t' || text[k] == '\n') text[k] = ' ';   // 别破坏分隔
        char *tx = text; while (*tx == ' ') tx++;                                             // 去前导空格
        char nm[64]; get_chatter_name(pv, db, from, nm, sizeof nm);
        for (int k = 0; nm[k]; k++) if (nm[k] == '\t') nm[k] = ' ';
        char tstr[24]; time_t tt = (time_t) ct; struct tm tmv; localtime_r(&tt, &tmv); strftime(tstr, sizeof tstr, "%m-%d %H:%M", &tmv);
        if (from == 1 && tx[0]) {   // 系统消息: 拼成"cheky 邀请了 林觉圣"
            char sysl[256]; build_system_line(pv, tx, db, sysl, sizeof sysl);
            for (int k = 0; sysl[k]; k++) if (sysl[k] == '\t' || sysl[k] == '\n') sysl[k] = ' ';
            fprintf(f, "%s\t系统\t%s\n", tstr, sysl);
        } else {
            fprintf(f, "%s\t%s\t%s\n", tstr, nm, tx[0] ? tx : "[图片/表情/语音等]");
        }
        n++;
    }
    pv->finalize(st);
    fclose(f);
    if (n == 0) {   // 空导出(该群本地无消息): 删掉文件, 别在列表里留"未取到群名"的空壳
        remove(path);
        flog("保留被踢群: 空导出已丢弃 chat=%lld", kicked_chat);
        return;
    }
    flog("导出被踢群: 群=%s chat=%lld 共 %d 条 -> %s", gname[0] ? gname : "?", kicked_chat, n, path);
}

static volatile int g_recall_diag = 0;   // 1=撤回诊断: 记录所有 messages 写 + is_recalled 路径
// 防已读探测结论(2026-07 实测): 开聊天读回执时 sqlite 只有 全列渲染SELECT / UPDATE me_read / REPLACE,
//   无"取要标已读 id 列表"的窄 SELECT -> 报告 id 在内存 async 层, 不过 sqlite -> 桌面"清空messageIds"无 sqlite 落点。
static volatile int g_read_sql_diag = 0;   // 探测已完成, 关闭

// 双 provider 的 step 入口: wcdb2(8.x 消息库) / sqlcipher(7.x + 旧组件库) 各挂一份,
// 共用下面这套逻辑; 语句/句柄只配本库符号(两库 sqlite3_stmt 布局不通用)。
static int step_common(SqlProv *pv, void *stmt, const char *t) {
    const char *recallCols = nullptr;
    if (t && is_msg_replace(t, &recallCols)) adapt_msg_cols(t, recallCols);
    // ── 防已读探测: 抓所有涉及 me_read / read 的 SELECT(去重, 只记不同模板) ──
    //   目的: 看读回执发送前, async 是否从 sqlite 取"要标已读的 message_id 列表"。
    if (g_read_sql_diag && t &&
        (strstr(t, "me_read") ||
         (strncmp(t, "SELECT", 6) == 0 && strstr(t, "messages") &&
          (strstr(t, "read") || strstr(t, "unread"))))) {
        static unsigned seen[128]; static int nseen = 0;
        unsigned h = str_hash32(t);
        bool dup = false;
        int idx = 0;
        pthread_mutex_lock(&g_cache_lock);
        for (int i = 0; i < nseen && i < 128; i++) if (seen[i] == h) { dup = true; break; }
        if (!dup && nseen < 128) { seen[nseen++] = h; idx = nseen; }
        pthread_mutex_unlock(&g_cache_lock);
        if (!dup) LOGI("READSQL[%d]: %.340s", idx, t);
    }
    // ── 撤回诊断 v3: 看 messages 的 UNIQUE 索引 + 撤回 REPLACE 的 VALUES 实际值 ──
    if (g_recall_diag && t) {
        // (A) 抓 messages 的 CREATE TABLE / CREATE UNIQUE INDEX -> 定位唯一约束(REPLACE 冲突删原行的元凶)
        if ((strncmp(t, "CREATE", 6) == 0) && strstr(t, "messages")) {
            static int cd = 0; if (__atomic_add_fetch(&cd, 1, __ATOMIC_RELAXED) <= 20) LOGI("SCHEMA-DIAG: %.500s", t);
        }
        if ((is_msg_replace(t, nullptr) || strncmp(t, "UPDATE `messages`", 17) == 0 ||
             strncmp(t, "DELETE FROM `messages`", 22) == 0)) {
            char *e = pv->exp ? pv->exp(stmt) : nullptr;
            if (e) {
                if (strstr(e, "is_recalled") || strstr(t, "is_recalled") || strstr(t, "DELETE")) {
                    // 打印 VALUES/WHERE 起始处(跳过长列名表), 看 id/position/cid/is_recalled 实际值
                    const char *vp = strstr(e, "VALUES ("); if (!vp) vp = strstr(e, "WHERE"); if (!vp) vp = e;
                    static int rd = 0; int c = __atomic_add_fetch(&rd, 1, __ATOMIC_RELAXED);
                    if (c <= 120) {
                        char verb[10] = {0}; strncpy(verb, t, 8);
                        LOGI("RECALL-DIAG #%d verb=%s val=%.320s", c, verb, vp);
                    }
                }
                pv->free_fn(e);
            }
        }
    }
    // ── 移出群/删群 诊断: 捕获"被踢/退群/解散"时对本地库的删除/清空 SQL(供判断能否拦) ──
    //   开关走诊断日志(Config.diaglog -> g_diag_log)。触发一次真实移出, 看命中哪些 DELETE/UPDATE。
    if (g_diag_log && t) {
        bool isDel = (strncmp(t, "DELETE", 6) == 0);
        bool isUpd = (strncmp(t, "UPDATE", 6) == 0);
        bool tbl = strstr(t, "`chats`") || strstr(t, "`messages`") || strstr(t, "chatter") ||
                   strstr(t, "feed_card") || strstr(t, "feed_channel") || strstr(t, "`chat_");
        if ((isDel || isUpd) && tbl) {
            // 按 SQL 模板去重: 正常操作的模板各记一次, 踢群时独特模板作为"新行"冒出。
            static unsigned kseen[256]; static int knseen = 0;
            unsigned h = str_hash32(t);
            bool dup = false;
            pthread_mutex_lock(&g_cache_lock);
            for (int i = 0; i < knseen && i < 256; i++) if (kseen[i] == h) { dup = true; break; }
            if (!dup && knseen < 256) kseen[knseen++] = h;
            pthread_mutex_unlock(&g_cache_lock);
            if (!dup) {
                static int kd = 0; int c = __atomic_add_fetch(&kd, 1, __ATOMIC_RELAXED);
                if (c <= 256) flog("KICK-DIAG #%d %.300s", c, t);
            }
        }
    }
    // ── 退群/被移除 检测(Toast 提醒 + 记录) + 可行性诊断 ──
    //   数据来源已验证: removed/left 系统消息本就写进普通成员本地库(飞书仅 UI 层对普通成员隐藏)。
    if ((g_diag_log || g_leave_notify) && t) {
        // 冲刷超窗仍未被插回的待确认删除 = 真退群 -> 提交(借每条 SQL 流过的时机, 飞书 SQL 频繁足够及时)。
        if (g_leave_notify && __atomic_load_n(&g_pend_n, __ATOMIC_ACQUIRE) > 0) pend_flush();
        // chatters 连接捕获(供查发送人名; 独立于"保留被踢群"开关, 否则只开退群提醒时查不到名字)
        if (pv->db_handle && strstr(t, "`chatters`")) record_chatters_db(pv->db_handle(stmt), pv);
        // 诊断B: 成员级删除展开(可行性佐证用)
        if (g_diag_log && pv->exp &&
            (strncmp(t, "DELETE FROM `non_departmental_chatters`", 39) == 0 ||
             (strncmp(t, "DELETE FROM `chat_chatter_ref`", 30) == 0 && strstr(t, "chatter_id")))) {
            char *e = pv->exp(stmt);
            if (e) { static int md = 0; if (__atomic_add_fetch(&md, 1, __ATOMIC_RELAXED) <= 80) flog("成员变动探测B: %.220s", e); pv->free_fn(e); }
        }
        // 诊断B2: chat_chatter_ref 的插入/重插(供确认 re-sync/改角色时把成员行插回来的语句形态)
        if (g_diag_log && pv->exp && strstr(t, "`chat_chatter_ref`") &&
            (strncmp(t, "INSERT", 6) == 0 || strncmp(t, "REPLACE", 7) == 0)) {
            char *e = pv->exp(stmt);
            if (e) { static int mi = 0; if (__atomic_add_fetch(&mi, 1, __ATOMIC_RELAXED) <= 120) flog("成员重插探测B2: %.260s", e); pv->free_fn(e); }
        }
        // ★ 静默退群/被移除 检测 —— 公司里离职自动退群【不广播系统消息】, 只删成员表:
        //   DELETE FROM `chat_chatter_ref` WHERE `chat_id` = <X> AND `chatter_id` IN (<a>,<b>,...)
        //   系统消息路径(下方 REPLACE INTO messages)漏掉这类, 故在此补: 解析 chat_id + 每个 chatter_id,
        //   查名记进 leave_log(只记录、不弹 Toast, 避免公司群批量退群刷屏)。
        //   仅认【带 chatter_id 的定向删除】= 真·某成员离开; 不含 chatter_id 的整表刷新是 re-sync, 不算。
        if (g_leave_notify && pv->exp &&
            strncmp(t, "DELETE FROM `chat_chatter_ref`", 30) == 0 && strstr(t, "chatter_id")) {
            char *e = pv->exp(stmt);
            if (e) {
                long long chat = 0;
                const char *cp = strstr(e, "chat_id");
                if (cp) { const char *q = cp + 7; while (*q && !(*q >= '0' && *q <= '9')) q++;
                          while (*q >= '0' && *q <= '9') { chat = chat * 10 + (*q - '0'); q++; } }
                const char *ip = strstr(e, "chatter_id");
                const char *lp = ip ? strstr(ip, "IN") : nullptr;
                if (chat && lp) {
                    void *db = pv->db_handle ? pv->db_handle(stmt) : nullptr;
                    char gname[128] = {0};
                    if (!lookup_name(chat, gname, sizeof gname) && db)
                        query_chat_name(pv, db, chat, gname, sizeof gname);
                    const char *grp = gname[0] ? gname : "群聊";
                    const char *q = lp; while (*q && *q != '(') q++;   // 定位 (
                    while (*q && *q != ')') {
                        while (*q && !(*q >= '0' && *q <= '9') && *q != ')') q++;
                        if (!*q || *q == ')') break;
                        long long cid = 0; while (*q >= '0' && *q <= '9') { cid = cid * 10 + (*q - '0'); q++; }
                        if (cid && !mleave_has(chat ^ (cid * 1000003LL))) {
                            char nm[64] = {0};
                            if (db) get_chatter_name(pv, db, cid, nm, sizeof nm);
                            char msg[256];
                            if (nm[0] && (nm[0] < '0' || nm[0] > '9'))
                                snprintf(msg, sizeof msg, "%s 离开了群聊「%s」(成员移除/离职退群)", nm, grp);
                            else
                                snprintf(msg, sizeof msg, "成员(%lld) 离开了群聊「%s」(名字未取到)", (long long) cid, grp);
                            // 不立即记: 先缓存, 窗口内若被 REPLACE 插回则判 re-sync/改角色丢弃, 超窗才确认。
                            pend_add(chat, cid, msg);
                            flog("静默退群(待确认): %s", msg);
                        }
                    }
                }
                pv->free_fn(e);
            }
        }
        // ★ 成员行被 REPLACE 插回 -> 取消对应待确认删除(re-sync / 改角色, 非真退群)。
        //   语句形态: REPLACE INTO `chat_chatter_ref` (`chat_id`,`chatter_id`,`weight`) VALUES (X, Y, Z)
        if (g_leave_notify && __atomic_load_n(&g_pend_n, __ATOMIC_ACQUIRE) > 0 && pv->exp &&
            strncmp(t, "REPLACE", 7) == 0 && strstr(t, "`chat_chatter_ref`")) {
            char *e = pv->exp(stmt);
            if (e) {
                const char *v = strstr(e, "VALUES");
                if (v) {
                    const char *q = v + 6;
                    while (*q && !(*q >= '0' && *q <= '9')) q++;
                    long long rc = 0; while (*q >= '0' && *q <= '9') { rc = rc * 10 + (*q - '0'); q++; }
                    while (*q && !(*q >= '0' && *q <= '9')) q++;
                    long long ru = 0; while (*q >= '0' && *q <= '9') { ru = ru * 10 + (*q - '0'); q++; }
                    if (rc && ru) pend_cancel(rc, ru);
                }
                pv->free_fn(e);
            }
        }
        if (is_msg_replace(t, nullptr) && pv->exp) {
            char *e = pv->exp(stmt);
            if (e) {
                const char *hx = strstr(e, "x'");   // content protobuf blob (十六进制)
                if (hx) {
                    hx += 2;
                    unsigned char bb[2048]; int bl = 0;
                    while (hx[0] && hx[1] && hx[0] != '\'' && bl < (int) sizeof bb) {
                        int a = hexval(hx[0]), b = hexval(hx[1]);
                        if (a < 0 || b < 0) break;
                        bb[bl++] = (unsigned char) ((a << 4) | b); hx += 2;
                    }
                    char text[1024]; int tp = 0; text[0] = 0;
                    if (bl > 0) pb_extract_buf(text, sizeof text, &tp, bb, bl, 0);
                    text[tp < (int) sizeof text ? tp : (int) sizeof text - 1] = 0;
                    bool isRemove = strstr(text, "removed") && strstr(text, "from this chat");
                    bool isLeft   = strstr(text, "left the group") || strstr(text, "quit the");
                    if (g_diag_log && (isRemove || isLeft || strstr(text, "invited") || strstr(text, "joined"))) {
                        const char *tag = isRemove ? "REMOVE" : (isLeft ? "LEFT" : "ADD");
                        static int sm = 0; if (__atomic_add_fetch(&sm, 1, __ATOMIC_RELAXED) <= 60) flog("系统消息探测A[%s]: %.220s", tag, text);
                    }
                    if (g_leave_notify && (isRemove || isLeft)) {
                        MsgCols mc = get_msg_cols();
                        // ★ 群内持久显示: 飞书把"移除/退群"系统消息写 is_visible=0 对普通成员隐藏 ->
                        //   改绑成 1, 飞书即像渲染"邀请"消息一样把它显示进聊天窗(持久, 原生样式)。
                        //   每次写入都翻(re-sync 会重写), 与去重(Toast/记录)无关。列位自适应(8.x)。
                        if (pv->bind64 && mc.visible >= 0) pv->bind64(stmt, mc.visible + 1, 1LL);
                        // VALUES 的 id 与 chat_id 列(列位自适应)
                        long long mid = 0, chat = 0;
                        const char *fs = nullptr, *fe = nullptr;
                        if (values_field(e, mc.id, &fs, &fe)) mid = field_ll(fs, fe);
                        if (values_field(e, mc.chat, &fs, &fe)) chat = field_ll(fs, fe);
                        if (mid && !leave_seen(mid)) {
                            void *db = pv->db_handle ? pv->db_handle(stmt) : nullptr;
                            long long tid = find_id_after(text, "to_chatters ");
                            long long fid = find_id_after(text, "from_user ");
                            char tn[64] = {0}, fn[64] = {0}, gname[128] = {0};
                            if (db) { if (tid) get_chatter_name(pv, db, tid, tn, sizeof tn);
                                      if (fid) get_chatter_name(pv, db, fid, fn, sizeof fn); }
                            if (!lookup_name(chat, gname, sizeof gname) && db)
                                query_chat_name(pv, db, chat, gname, sizeof gname);
                            const char *who = tn[0] ? tn : "某成员";
                            const char *grp = gname[0] ? gname : "群聊";
                            char msg[256];
                            if (isLeft) snprintf(msg, sizeof msg, "%s 退出了群聊「%s」", who, grp);
                            else if (fn[0] && strcmp(fn, tn) != 0) snprintf(msg, sizeof msg, "%s 被 %s 移出群聊「%s」", who, fn, grp);
                            else snprintf(msg, sizeof msg, "%s 被移出群聊「%s」", who, grp);
                            leave_enqueue(msg);
                            char tstr[24]; time_t tt = time(nullptr); struct tm tmv; localtime_r(&tt, &tmv);
                            strftime(tstr, sizeof tstr, "%m-%d %H:%M", &tmv);
                            char ddir[256]; copy_data_dir(ddir, sizeof ddir);
                            char _lp[320]; snprintf(_lp, sizeof _lp, "%s/leave_log.txt", ddir);
                            FILE *lf = fopen(_lp, "a");
                            if (lf) { fprintf(lf, "%s\t%s\n", tstr, msg); fclose(lf); }
                            flog("退群提醒: %s", msg);
                        }
                    }
                }
                pv->free_fn(e);
            }
        }
    }
    // ── 保留被踢群聊天记录 ──
    //   被踢/退群时飞书按 chat_id 爆发式清群(DELETE FROM chat_xxx/non_departmental_chatters WHERE chat_id IN)
    //   紧接着 DELETE FROM messages 删记录。策略: 见"清群删除"就丢弃它并开 3s 窗口, 窗口内丢弃删消息。
    if (g_keep_kicked && t) {
        // 捕获 chatters 表所在连接(与 messages 不同库), 供导出查发送人昵称。
        if (pv->db_handle && strstr(t, "`chatters`")) record_chatters_db(pv->db_handle(stmt), pv);
        // 平时缓存群名(踢群前 UPDATE chats SET name=? 里解析), 供导出用。
        if (strncmp(t, "UPDATE `chats`", 14) == 0 && strstr(t, "`name`")) {
            char *e = pv->exp ? pv->exp(stmt) : nullptr;
            if (e) {
                long long cid = 0; const char *wp = strstr(e, "WHERE");
                if (wp) { const char *q = strstr(wp, "id"); if (q) { while (*q && !(*q >= '0' && *q <= '9')) q++; while (*q >= '0' && *q <= '9') { cid = cid * 10 + (*q - '0'); q++; } } }
                const char *np = strstr(e, "`name`");
                if (cid && np) { const char *s = strchr(np, '\''); if (s) { s++; char nm[96]; int k = 0; while (s[k] && s[k] != '\'' && k < 95) { nm[k] = s[k]; k++; } nm[k] = 0; cache_name(cid, nm); } }
                pv->free_fn(e);
            }
        }
        // 注意: 导出【不再】在此处靠 strstr(t,"messages") 松触发 —— 那会命中任何含 messages 的语句
        //   (SELECT/UPDATE me_read/REPLACE 新消息...), 导致"别人退群整表刷新成员"被误判成被踢、
        //   把你还在的群整群导出(实测会把某个正常群名误当被踢), 还产出一堆空的"未取到群名"文件。
        //   真被踢的唯一可靠信号 = 紧接着【真的删这个群的消息】(DELETE FROM messages)。故把导出
        //   下移到下面"窗口内拦删消息"处: 只有真要删消息时才 do_export(此刻消息还在, 读得到完整数据)。
        if (strncmp(t, "DELETE", 6) == 0) {
            // ★ 真·被踢/退群/群解散信号 = 整群拆除: 对整表按 `chat_id` IN 清除、且 SQL 里【无 chatter_id 约束】。
            //   实测被踢序列: DELETE chat_chatter_ref/chat_chatter_extra/chat_top_notice/wanted_at_chatters/
            //   schedule_message WHERE `chat_id` IN (?) -> 随即 DELETE FROM messages。
            //   而【别人退群】只删他自己那一行(`... chatter_id IN (他)`), 不会整表按 chat_id 清 ->
            //   排除 non_departmental_chatters(带 chatter_id) 即消除"别人退群误导出你还在的群"的老 bug。
            bool teardown =
                (strstr(t, "`chat_id` IN") != nullptr) && (strstr(t, "chatter_id") == nullptr) &&
                (strncmp(t, "DELETE FROM `chat_chatter_ref`", 30) == 0 ||
                 strncmp(t, "DELETE FROM `chat_chatter_extra`", 32) == 0 ||
                 strncmp(t, "DELETE FROM `chat_top_notice`", 29) == 0 ||
                 strncmp(t, "DELETE FROM `wanted_at_chatters`", 32) == 0 ||
                 strncmp(t, "DELETE FROM `schedule_message`", 30) == 0);
            if (teardown) {
                long long dcid = 0;
                char *e = pv->exp ? pv->exp(stmt) : nullptr;
                if (e) {
                    const char *cp = strstr(e, "chat_id");
                    if (cp) { const char *q = cp; while (*q && !(*q >= '0' && *q <= '9')) q++;
                              while (*q >= '0' && *q <= '9') { dcid = dcid * 10 + (*q - '0'); q++; } }
                    pv->free_fn(e);
                }
                if (dcid > 0) {
                    __atomic_store_n(&g_kicked_chat, dcid, __ATOMIC_RELEASE);
                    __atomic_store_n(&g_export_pending, 1, __ATOMIC_RELEASE);
                    g_kick_window = now_ms() + 5000;   // 5s 内的 DELETE FROM messages 判为本次拆群 -> 丢弃
                    static int kc = 0; int c = __atomic_add_fetch(&kc, 1, __ATOMIC_RELAXED);
                    if (c <= 80) flog("保留被踢群: 确认整群拆除(被踢/退群) chat=%lld %.80s", (long long) dcid, t);
                }
                // teardown 本身放行(让飞书正常清关系表); 只保 messages。
            }
            if (now_ms() < g_kick_window && strncmp(t, "DELETE FROM `messages`", 22) == 0) {
                // 真要删消息了 = 确认被踢/退群/解散(别人退群只刷成员表, 不会走到这) -> 此刻先导出(消息还在), 再丢弃删除。
                if (__atomic_exchange_n(&g_export_pending, 0, __ATOMIC_ACQ_REL) && pv->db_handle) {
                    long long kc_id = __atomic_load_n(&g_kicked_chat, __ATOMIC_ACQUIRE);
                    do_export(pv, pv->db_handle(stmt), kc_id);
                }
                static int km = 0; int c = __atomic_add_fetch(&km, 1, __ATOMIC_RELAXED);
                if (c <= 80) flog("保留被踢群: 窗口内拦删消息 %.80s", t);
                return 101;   // 丢弃删消息 -> 聊天记录保留
            }
        }
    }
    if (g_recall_enabled && t && pv->exp && pv->db_handle
            && strstr(t, "messages")) {
        static unsigned long long recallScans = 0;
        if (g_diag_log && __atomic_add_fetch(&recallScans, 1, __ATOMIC_RELAXED) == 1) {
            LOGI("RECALL-ANCHOR: first messages statement reached provider=%s", pv->so);
            flog("防撤回进度 2/3：已观察到消息库SQL活动，provider=%s；等待撤回写入命中", pv->so);
        }
        if (is_recall_write_sql(t)) {
            char *expanded = pv->exp(stmt);
            if (expanded) {
                int result = g_recall_store.filter(recall_api(pv), pv, pv->db_handle(stmt), expanded, stmt);
                pv->free_fn(expanded);
                if (result != recall::PASS) {
                    if (result == recall::DONE) {
                        int n = __atomic_add_fetch(&g_neutralized, 1, __ATOMIC_RELAXED);
                        flog("防撤回成功 3/3：已安全处理一条他人撤回写入，原文保留、撤回标记已保存（累计=%d）", n);
                    }
                    return result;
                }
            }
        }
    }
    return pv->orig_step(stmt);
}
// A snapshot is published only after commit. ROLLBACK/SAVEPOINT paths are
// observed too, so the UI never needs to retain or use a SQLite connection.
static int step_observed(SqlProv *pv, void *stmt) {
    bool observe = pv->db_handle && pv->db_filename && pv->autocommit && pv->prepare && pv->finalize && pv->col_i64;
    const char *sql = pv->sql ? pv->sql(stmt) : nullptr;
    bool messages = sql && strstr(sql, "messages");
    // A SELECT may return one row then be finalized without another step. Read
    // pending markers BEFORE the next cursor starts as well as after commit.
    if (observe) g_recall_store.observe(recall_api(pv), pv, pv->db_handle(stmt), messages);
    int result = step_common(pv, stmt, sql);
    if (observe) g_recall_store.observe(recall_api(pv), pv, pv->db_handle(stmt), messages);
    return result;
}
static int my_step_wcdb(void *stmt)   { return step_observed(&g_prov[0], stmt); }
static int my_step_cipher(void *stmt) { return step_observed(&g_prov[1], stmt); }

// 维护: 飞书 7.70.x 的 PV-MON 会定期把 SQLite .text 还原, 抹掉我们的 inline hook。
// 由 Java 线程周期调用本函数, 检测入口字节被还原则重打 hook, 并补装迟加载的 provider。
static void maintain_hook() {
    {
        std::lock_guard<std::mutex> guard(g_prov_mutex);
        for (SqlProv &p : g_prov) {
            if (p.installed && p.step_ep && memcmp(p.step_ep, p.hookbytes, 16) != 0) {
                A64HookFunction(p.step_ep, (void *) (&p == &g_prov[0] ? my_step_wcdb : my_step_cipher),
                                p.orig_step ? nullptr : (void **) &p.orig_step);
                memcpy(p.hookbytes, p.step_ep, 16);
                int c = __atomic_add_fetch(&g_step_rehook, 1, __ATOMIC_RELAXED);
                if (c <= 40 || c % 100 == 0) {
                    LOGI("ANTIRECALL: re-hooked sqlite3_step@%s (anti-tamper restored it) #%d", p.so, c);
                    if (c == 1 || c % 100 == 0)
                        flog("防撤回: 飞书反篡改还原了 hook, 已重装 (第%d次) —— 属正常, hook 仍在工作", c);
                }
            }
        }
        // 迟加载的 provider 补装(节流 >=2s 一次; 7.x 无 wcdb2 则永远扫不到, 无害)
        static long last_sweep_ms = 0;
        long now = now_ms();
        if (last_sweep_ms == 0 || now - last_sweep_ms >= 2000) {
            last_sweep_ms = now;
            for (SqlProv &p : g_prov) if (!p.installed) try_prov_locked(&p);
        }
    }
    resign_maintain_hooks();
}

// Java 日志走专属 tag "antiread-j" (避开被其它 LSPosed 模块刷爆的 LSPosedFramework tag)
extern "C" JNIEXPORT void JNICALL
Java_com_chekayo_feishuantirecall_AntiRecall_nativeLog(JNIEnv *env, jclass, jstring s) {
    if (!s) return;
    const char *c = env->GetStringUTFChars(s, nullptr);
    __android_log_print(ANDROID_LOG_INFO, "antiread-j", "%s", c ? c : "(null)");
    if (c) env->ReleaseStringUTFChars(s, c);
}

// 由 Java 维护线程周期调用: 重打被反篡改还原的 SQLite hook 及补装迟加载 provider。
extern "C" JNIEXPORT void JNICALL
Java_com_chekayo_feishuantirecall_AntiRecall_nativeMaintain(JNIEnv *, jclass) {
    maintain_hook();
}

// fuck lark 设置面板: 实时开关防撤回中和
extern "C" JNIEXPORT void JNICALL
Java_com_chekayo_feishuantirecall_AntiRecall_nativeBindRecallAccount(JNIEnv *env, jclass, jstring uid, jstring storage) {
    if (!uid || !storage) return;
    const char *u = env->GetStringUTFChars(uid, nullptr);
    if (!u) return;
    const char *s = env->GetStringUTFChars(storage, nullptr);
    if (s) {
        recall::bindOwner(s, u);
        env->ReleaseStringUTFChars(storage, s);
    }
    env->ReleaseStringUTFChars(uid, u);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_chekayo_feishuantirecall_AntiRecall_nativeIsRecallMarked(JNIEnv *env, jclass, jstring uid, jlong chat, jlong id) {
    if (!uid) return JNI_FALSE;
    const char *s = env->GetStringUTFChars(uid, nullptr);
    if (!s) return JNI_FALSE;
    bool marked = g_recall_store.contains(s, chat, id);
    env->ReleaseStringUTFChars(uid, s);
    return marked ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_chekayo_feishuantirecall_AntiRecall_nativeRecallEpoch(JNIEnv *, jclass) {
    return static_cast<jlong>(g_recall_store.epoch());
}

extern "C" JNIEXPORT void JNICALL
Java_com_chekayo_feishuantirecall_AntiRecall_nativeSetRecall(JNIEnv *, jclass, jboolean on) {
    g_recall_enabled = on ? 1 : 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_chekayo_feishuantirecall_AntiRecall_nativeSetDiag(JNIEnv *, jclass, jboolean on) {
    g_diag_log = on ? 1 : 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_chekayo_feishuantirecall_AntiRecall_nativeSetKeepKicked(JNIEnv *, jclass, jboolean on) {
    g_keep_kicked = on ? 1 : 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_chekayo_feishuantirecall_AntiRecall_nativeSetLeaveNotify(JNIEnv *, jclass, jboolean on) {
    g_leave_notify = on ? 1 : 0;
}

// Java 侧 startNative 设当前目标包的 files 目录(国内/国际版自适应); native 据此拼所有日志/导出路径。
extern "C" JNIEXPORT void JNICALL
Java_com_chekayo_feishuantirecall_AntiRecall_nativeSetDataDir(JNIEnv *env, jclass, jstring d) {
    if (!d) return;
    const char *c = env->GetStringUTFChars(d, nullptr);
    if (c) {
        bool changed = false;
        pthread_mutex_lock(&g_dir_lock);
        if (strncmp(g_data_dir, c, sizeof g_data_dir - 1) != 0) {
            strncpy(g_data_dir, c, sizeof g_data_dir - 1);
            g_data_dir[sizeof g_data_dir - 1] = 0;
            changed = true;
        }
        pthread_mutex_unlock(&g_dir_lock);
        env->ReleaseStringUTFChars(d, c);
        if (changed) g_recall_store.clearObserved();
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_chekayo_feishuantirecall_AntiRecall_nativeSetLogDir(JNIEnv *env, jclass, jstring d) {
    if (!d) return;
    const char *c = env->GetStringUTFChars(d, nullptr);
    if (c) {
        pthread_mutex_lock(&g_dir_lock);
        strncpy(g_log_dir, c, sizeof g_log_dir - 1);
        g_log_dir[sizeof g_log_dir - 1] = 0;
        pthread_mutex_unlock(&g_dir_lock);
        env->ReleaseStringUTFChars(d, c);
    }
}

// Java 维护线程轮询取一条待弹的"退群/被移除"提醒(无则返回 null)。
extern "C" JNIEXPORT jstring JNICALL
Java_com_chekayo_feishuantirecall_AntiRecall_nativePollLeaveEvent(JNIEnv *env, jclass) {
    char out[256]; bool got = false;
    pthread_mutex_lock(&g_leave_lock);
    if (g_leave_head != g_leave_tail) {
        strncpy(out, g_leave_q[g_leave_head], sizeof out - 1); out[sizeof out - 1] = 0;
        g_leave_head = (g_leave_head + 1) % 16; got = true;
    }
    pthread_mutex_unlock(&g_leave_lock);
    return got ? env->NewStringUTF(out) : nullptr;
}

// 尝试给一个 provider 解析符号并 hook 其 sqlite3_step(调用方须持 g_prov_mutex)。
// 库未加载 -> false(下次再试); 符号不全 -> 标记放弃; 成功 -> installed。
static bool try_prov_locked(SqlProv *p) {
    if (p->installed || p->gave_up) return p->installed;
    uintptr_t base = feishukit_elf::find_base(p->so);
    if (!base) return false;
    void *step = feishukit_elf::resolve(base, "sqlite3_step");
    p->sql         = (sql_t)     feishukit_elf::resolve(base, "sqlite3_sql");
    p->exp         = (expsql_t)  feishukit_elf::resolve(base, "sqlite3_expanded_sql");
    p->free_fn     = (free_t)    feishukit_elf::resolve(base, "sqlite3_free");
    p->bind64      = (bind64_t)  feishukit_elf::resolve(base, "sqlite3_bind_int64");
    p->bind_blob   = (bindblob_t)feishukit_elf::resolve(base, "sqlite3_bind_blob");
    p->bind_text   = (bindtext_t)feishukit_elf::resolve(base, "sqlite3_bind_text");
    p->db_handle   = (dbhandle_t)feishukit_elf::resolve(base, "sqlite3_db_handle");
    p->db_filename = (dbfilename_t)feishukit_elf::resolve(base, "sqlite3_db_filename");
    p->autocommit  = (autocommit_t)feishukit_elf::resolve(base, "sqlite3_get_autocommit");
    p->error       = (const char* (*)(void*))feishukit_elf::resolve(base, "sqlite3_errmsg");
    p->next_stmt   = (void* (*)(void*, void*))feishukit_elf::resolve(base, "sqlite3_next_stmt");
    p->busy        = (int (*)(void*))feishukit_elf::resolve(base, "sqlite3_stmt_busy");
    p->reset       = (int (*)(void*))feishukit_elf::resolve(base, "sqlite3_reset");
    p->db_mutex    = (void* (*)(void*))feishukit_elf::resolve(base, "sqlite3_db_mutex");
    p->mutex_enter = (void (*)(void*))feishukit_elf::resolve(base, "sqlite3_mutex_enter");
    p->mutex_leave = (void (*)(void*))feishukit_elf::resolve(base, "sqlite3_mutex_leave");
    p->prepare     = (prepare_t) feishukit_elf::resolve(base, "sqlite3_prepare_v2");
    p->finalize    = (finalize_t)feishukit_elf::resolve(base, "sqlite3_finalize");
    p->col_i64     = (coli64_t)  feishukit_elf::resolve(base, "sqlite3_column_int64");
    p->col_blob    = (colblob_t) feishukit_elf::resolve(base, "sqlite3_column_blob");
    p->col_bytes   = (colbytes_t)feishukit_elf::resolve(base, "sqlite3_column_bytes");
    LOGI("%s base=%p step=%p sql=%p exp=%p bind64=%p", p->so, (void *) base, step,
         (void *) p->sql, (void *) p->exp, (void *) p->bind64);
    if (!step || !p->sql || !p->exp || !p->free_fn || !p->bind64) {
        LOGE("%s resolve failed (sym missing), give up this provider", p->so);
        flog("防撤回扫描: provider=%s 符号缺失，跳过该库", p->so);
        p->gave_up = true;
        return false;
    }
    A64HookFunction(step, (void *) (p == &g_prov[0] ? my_step_wcdb : my_step_cipher),
                    (void **) &p->orig_step);
    p->step_ep = step;
    memcpy(p->hookbytes, step, 16);   // 记录 hook 后的入口字节, 供 maintain 检测被还原
    p->installed = true;
    LOGI("ANTI-RECALL native hook installed on %s sqlite3_step @%p", p->so, step);
    flog("防撤回进度 1/3：SQLite拦截钩子已安装，数据库=%s；尚未证明已命中撤回", p->so);
    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_chekayo_feishuantirecall_AntiRecall_tryInstall(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> guard(g_prov_mutex);
    // 任一 provider 挂上即完成; 迟加载的 provider 由 maintain_hook 周期补装。
    if (g_installed) return JNI_TRUE;
    bool any = false;
    for (SqlProv &p : g_prov) if (try_prov_locked(&p)) any = true;
    if (!any) {
        static int waiting = 0;
        if (++waiting == 1 || waiting % 200 == 0) {
            LOGI("RECALL-ANCHOR: SQLite provider not loaded yet");
            flog("防撤回扫描: 等待 SQLite provider 加载 attempt=%d", waiting);
        }
        return JNI_FALSE;
    }
    g_installed = true;
    LOGI("RECALL-ANCHOR: SQLite hook ready wcdb2=%d sqlcipher=%d",
         g_prov[0].installed, g_prov[1].installed);
    return JNI_TRUE;
}
