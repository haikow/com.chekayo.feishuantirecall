// 飞书防撤回 —— 原生 SQL 层 (libsqlcipher.so) inline hook
// 机制(已验证): 撤回 = REPLACE INTO `messages` 且 is_recalled=1, content 被清空。
// 对策: 该写入即将执行时把 id+chat_id 改绑成哨兵(1,1), 真正的原始行(收信时已入库,
//       content 完整, is_recalled=0)原封不动 -> 打开聊天即见原文。免电脑、免 frida。
//
// 符号解析不走 dlopen/dlsym: 模块经 System.load 进的是 app classloader-namespace,
// 与 app 自己加载的 libsqlcipher 不在同一 linker namespace, dlopen(RTLD_NOLOAD) 看不到。
// 改为直接读 /proc/self/maps 找 base, 解析其 ELF .dynsym 得到符号绝对地址 (namespace 无关)。
// tryInstall() 由 Java 线程经 JNI 反复调用 (在 Java 线程上 libc/PLT 正常; 自起 raw pthread 会崩)。
#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <stdarg.h>
#include <stdint.h>
#include <string.h>
#include <elf.h>
#include <unwind.h>
#include <time.h>
#include <dlfcn.h>
#include <sys/socket.h>
#include <sys/uio.h>
#include <sys/mman.h>
#include <pthread.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <android/log.h>
#include "And64InlineHook.hpp"

#define TAG "antirecall-native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// 诊断日志: 追加到飞书私有目录的统一日志文件(与 Java 侧 Diag.java 同一个文件),
// 供终端用户在设置面板一键复制发作者排查“防撤回不生效”。带 MM-dd HH:mm:ss 时间戳。
static volatile int g_diag_log = 0;   // 诊断日志开关(Java 经 nativeSetDiag 设置); 0=不写文件

// 当前目标包的 files 目录(Java 经 nativeSetDataDir 设置; 默认国内版路径做 fallback,
// 国际版 Lark 起来后会被覆盖成 /data/data/com.larksuite.suite/files)。
static char g_data_dir[256] = "/data/data/com.ss.android.lark/files";
static void flog(const char *fmt, ...) {
    if (!g_diag_log) return;
    char _p[320]; snprintf(_p, sizeof _p, "%s/fucklark_log.txt", g_data_dir);
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

// 撤回拦截去重: 飞书对同一撤回会重放多次 REPLACE, 只记不同 msg 的首次(避免刷屏)。
static long long g_last_flog_oid = 0;
static void flog_recall(long long oid, int total, const char *how) {
    if (oid == g_last_flog_oid) return;
    g_last_flog_oid = oid;
    flog("防撤回: 拦截撤回 原文已保留(%s) msg=%lld 累计=%d", how, oid, total);
}

// ──────────────────────────────────────────────────────────────────────────
// 防已读 调研探针 (diagnostic only, 不改飞书行为)
// 目的: 在飞书进程内 (已过字节系反frida) 确认 arm64 上哪个函数发出 frontier
//       命令 PUT_READ_MESSAGES (= command id 40 / 0x28), 并打印整条调用栈.
// liblark.so v7.52.4 静态定位的候选 (升级会变):
//   sub_53F6178  payload.rs 建包, a1(x0)=command 候选  <- 主嫌
//   sub_50EF7B4  get_message_me_read_state_packet 建包
//   sub_50C6BC4  read_message::logic
#define PROBE_ENABLED 1
static const unsigned  CMD_PUT_READ = 40;         // PUT_READ_MESSAGES (供参考)

static uintptr_t g_lark_base = 0;
static bool g_probe_installed = false;
static volatile long g_last_read_ms = 0;   // 最近一次 read 模块活动时刻

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

static step_t    orig_step = nullptr;
static sql_t     p_sql      = nullptr;
static expsql_t  p_exp      = nullptr;
static free_t    p_free     = nullptr;
static bind64_t  p_bind64   = nullptr;
static bindblob_t p_bind_blob = nullptr;
static bindtext_t p_bind_text = nullptr;
// 导出被踢群记录: 进程内(sqlcipher 已解密)跑 SELECT 需要的额外符号
typedef int         (*prepare_t)(void*, const char*, int, void**, const char**);
typedef int         (*finalize_t)(void*);
typedef long long   (*coli64_t)(void*, int);
typedef const void* (*colblob_t)(void*, int);
typedef int         (*colbytes_t)(void*, int);
typedef void*       (*dbhandle_t)(void*);
static dbhandle_t p_db_handle = nullptr;
static prepare_t  p_prepare  = nullptr;
static finalize_t p_finalize = nullptr;
static coli64_t   p_col_i64  = nullptr;
static colblob_t  p_col_blob = nullptr;
static colbytes_t p_col_bytes = nullptr;
static volatile long long g_kicked_chat = 0;   // 最近被踢/清群的 chat_id
static volatile int g_export_pending = 0;      // 1=待在 messages 连接上导出
static void *g_chatters_db = nullptr;          // chatters 表所在连接(与 messages 不同库)
#define SQLITE_TRANSIENT ((void*)-1)
static volatile int g_neutralized = 0;
static volatile int g_recall_enabled = 1;   // fuck lark 开关: 0=停用防撤回中和(设置面板可切)
static volatile int g_recall_drop = 0;       // 0=持久提示(id+cid双改绑,原文+撤回提示并存); 1=纯丢弃(仅原文)
static volatile int g_keep_kicked = 0;       // 1=保留被踢群聊天记录(拦清群 DELETE + 窗口内拦删消息)
static volatile long long g_kick_window = 0; // 整群拆除信号后开的丢弃窗口(ms 时间戳): 窗口内的 DELETE FROM messages 丢弃
// ── 退群/被移除 提醒 ──
static volatile int g_leave_notify = 0;      // 1=检测"谁退群/被移除"并弹 Toast + 记录
static char g_leave_q[16][256];              // 待弹事件队列(native 检测 -> Java nativePollLeaveEvent 取走)
static volatile int g_leave_head = 0, g_leave_tail = 0;
static pthread_mutex_t g_leave_lock = PTHREAD_MUTEX_INITIALIZER;
static long long g_leave_seen[128];          // 去重: 最近已处理的成员变动消息 id
static volatile int g_leave_seen_n = 0;
static long long g_mleave_seen[128];         // 去重: 静默退群(成员表删除)的 (chat^chatter) 键
static volatile int g_mleave_n = 0;
static bool g_installed = false;
// sqlite3_step hook 维护(飞书 7.70.x 反篡改会还原 libsqlcipher .text, 抹掉本 hook)
static void *g_step_ep = nullptr;
static unsigned char g_step_hookbytes[16] = {0};
static volatile int g_step_rehook = 0;
static volatile int g_antiread_cnt = 0;
static const int g_antiread_sqlite = 0;   // sqlite me_read/read_position 中和(只保自己未读, 不防对方); 本轮关
// 防已读 native: services/read_message.rs (sub_5298C3C) 开聊天触发、干净可hook, 疑似高层上报API.
static const uintptr_t OFF_EXPORT = 0x5298C3C;
static const int g_antiread_export = 1;   // 1=no-op 测防已读
static volatile int g_export_cnt = 0;

// REPLACE INTO `messages` 列序(0-based): 9 content, 14 is_recalled, 0 id, 1 chat_id
static const int FIELD_IS_RECALLED = 14;
static const int PARAM_ID      = 1;
static const int PARAM_CHAT    = 2;
static const int PARAM_CONTENT = 10;   // content 是第10列(1-based 绑定索引)
static const int PARAM_CID     = 13;   // cid 是第13列(0-based 12) 1-based 绑定索引
static const int PARAM_IS_VISIBLE = 31; // is_visible 是第31列(0-based 30) 1-based 绑定索引; 飞书对普通成员把"移除"系统消息写 0 隐藏, 改绑 1 即群内可见

// ──────────────────────────────────────────────────────────────────────────
// 防已读 (stealth-read) — 已验证方案 (frida hunt15 移植):
//   ① 回执: hook frontier 终端入队 sub_649FE5C; payload 含"最近读过 chat 的 ASCII 十进制 id"则丢(return 0).
//      读报告把 chat_id 编成 ASCII 字符串塞进 protobuf, 其它命令多用二进制小端 -> ASCII 形是稳定签名. 通杀立即+周期.
//   ② 红点: 拦 REPLACE INTO `feed_channel`, 把 new_message_count(第10参) 重绑为 max(0, lmp-rp) = 本地真实未读.
#define OFF_ENQ649 0x649fe5c
static volatile int g_stealth_read = 0;          // 0=纯防撤回(日常); 防已读受阻于 app 漂移, scaffolding 保留
static volatile int g_sr_debug = 0;              // 诊断日志
static volatile long g_read_window = 0;          // 读后诊断窗口截止(ms)
static const long ACTIVE_MS = 600000;            // 读过的 chat 活跃时长(丢回执窗口)

struct ChatInfo { uint64_t id; char ascii[24]; int alen; int rp; int lmp; long active_until; bool used; };
static ChatInfo g_chats[64];
static pthread_mutex_t g_chats_lock = PTHREAD_MUTEX_INITIALIZER;

// 监视 id 集合: 读时标记已读的 message_id + chat_id. 回执引用这些 id(ASCII 或二进制小端两种编码).
struct WatchId { char ascii[24]; int alen; unsigned char le[8]; long expire; bool used; };
static WatchId g_watch[256];
static pthread_mutex_t g_watch_lock = PTHREAD_MUTEX_INITIALIZER;
static void watch_add(uint64_t id) {
    if (!id) return;
    char tmp[24]; int l = snprintf(tmp, sizeof tmp, "%llu", (unsigned long long) id);
    if (l <= 0) return; if (l > 23) l = 23;
    long exp = now_ms() + 600000;
    pthread_mutex_lock(&g_watch_lock);
    int slot = -1; long oldest = 0x7fffffffffffffffLL; int oldslot = 0;
    for (int i = 0; i < 256; i++) {
        if (g_watch[i].used && g_watch[i].alen == l && memcmp(g_watch[i].ascii, tmp, l) == 0) { g_watch[i].expire = exp; pthread_mutex_unlock(&g_watch_lock); return; }
        if (!g_watch[i].used && slot < 0) slot = i;
        if (g_watch[i].expire < oldest) { oldest = g_watch[i].expire; oldslot = i; }
    }
    if (slot < 0) slot = oldslot;      // 满则淘汰最旧
    WatchId *w = &g_watch[slot];
    w->used = true; memcpy(w->ascii, tmp, l); w->alen = l; w->expire = exp;
    for (int i = 0; i < 8; i++) { w->le[i] = (unsigned char) (id & 0xff); id >>= 8; }
    pthread_mutex_unlock(&g_watch_lock);
}
// 解析 "... IN (id1, id2, ...)" 里的所有十进制 id, 逐个 watch_add. 返回个数.
static int watch_in_list(const char *e) {
    const char *p = strstr(e, " IN ("); if (!p) return 0; p += 5;
    int cnt = 0;
    while (*p && *p != ')') {
        while (*p == ' ' || *p == ',') p++;
        if (*p < '0' || *p > '9') { if (*p == ')') break; p++; continue; }
        uint64_t v = 0; while (*p >= '0' && *p <= '9') { v = v * 10 + (*p - '0'); p++; }
        watch_add(v); cnt++;
    }
    return cnt;
}

// 安全读: 经 pipe write/read 探测可读性(坏指针返回 EFAULT 而非崩). 多线程经 mutex 串行.
static int g_pfd[2] = { -1, -1 };
static pthread_mutex_t g_pipe_lock = PTHREAD_MUTEX_INITIALIZER;
static int safe_copy(const void *src, void *dst, int n) {
    if (g_pfd[1] < 0 || !src || n <= 0) return 0;
    int got = 0;
    pthread_mutex_lock(&g_pipe_lock);
    ssize_t w = write(g_pfd[1], src, (size_t) n);
    if (w > 0) { ssize_t r = read(g_pfd[0], dst, (size_t) w); if (r > 0) got = (int) r; }
    pthread_mutex_unlock(&g_pipe_lock);
    return got;
}

// 取/建 chat 槽 (调用者持 g_chats_lock). 满则淘汰 active_until 最小者.
static ChatInfo *chat_slot(uint64_t id) {
    for (int i = 0; i < 64; i++) if (g_chats[i].used && g_chats[i].id == id) return &g_chats[i];
    int slot = -1; long oldest = 0x7fffffffffffffffLL;
    for (int i = 0; i < 64; i++) {
        if (!g_chats[i].used) { slot = i; break; }
        if (g_chats[i].active_until < oldest) { oldest = g_chats[i].active_until; slot = i; }
    }
    if (slot < 0) return nullptr;
    ChatInfo *ci = &g_chats[slot];
    ci->used = true; ci->id = id; ci->rp = -1; ci->lmp = -1; ci->active_until = 0;
    char tmp[24]; int l = snprintf(tmp, sizeof tmp, "%llu", (unsigned long long) id);
    if (l < 0) l = 0; if (l > 23) l = 23;
    memcpy(ci->ascii, tmp, l); ci->alen = l;
    return ci;
}

// 从 expanded SQL 取 key 后第一个十进制数 (key 含反引号, 如 "`read_position`"). 无则 -1.
static long long find_num(const char *s, const char *key) {
    const char *p = strstr(s, key);
    if (!p) return -1;
    p += strlen(key);
    while (*p && (*p == ' ' || *p == '=' || *p == '`')) p++;
    if (*p < '0' || *p > '9') return -1;
    long long v = 0; while (*p >= '0' && *p <= '9') { v = v * 10 + (*p - '0'); p++; }
    return v;
}

// 解析 feed_channel 的 VALUES: 取第0个(id) 与 第9个(new_message_count, 0-based).
static bool feed_parse(const char *e, uint64_t *id, long long *nmc) {
    const char *v = strstr(e, "VALUES ("); if (!v) return false; v += 8;
    int field = 0; bool inq = false; const char *fs = v; bool gotid = false, gotn = false;
    for (const char *p = v; *p; ++p) {
        char c = *p;
        if (c == '\'') { inq = !inq; continue; }
        if ((c == ',' || c == ')') && !inq) {
            const char *q = fs; while (q < p && *q == ' ') q++;
            long long x = 0; bool ok = false;
            while (q < p && *q >= '0' && *q <= '9') { x = x * 10 + (*q - '0'); q++; ok = true; }
            if (field == 0 && ok) { *id = (uint64_t) x; gotid = true; }
            else if (field == 9 && ok) { *nmc = x; gotn = true; }
            if (c == ')') break;
            field++; fs = p + 1;
        }
    }
    return gotid && gotn;
}

static int recall_is_one(const char *expanded) {
    const char *v = strstr(expanded, "VALUES (");
    if (!v) return 0;
    v += 8;
    int field = 0;
    bool inq = false;
    const char *fs = v;
    for (const char *p = v; *p; ++p) {
        char c = *p;
        if (c == '\'') { inq = !inq; continue; }
        if (c == ',' && !inq) {
            if (field == FIELD_IS_RECALLED) {
                while (fs < p && *fs == ' ') ++fs;
                return (fs < p && *fs == '1') ? 1 : 0;
            }
            ++field;
            fs = p + 1;
        }
    }
    if (field == FIELD_IS_RECALLED) {
        while (*fs == ' ') ++fs;
        return (*fs == '1') ? 1 : 0;
    }
    return 0;
}

static void dump_bt(const char *what, unsigned a1);   // 前向声明(定义在下方)

// 安全栈扫描(不依赖 CFI/unwind, 避免穿 async rust 栈帧崩): 读自己栈, 挑落在 liblark .text 的值.
// .text vaddr 0x2fd4940..0x65fb280. 噪声大但真返回地址必在其中, 用来定位运行时函数链.
static void scan_stack_for_lark(const char *what, unsigned tag) {
    if (!g_lark_base) return;
    uintptr_t sp;
    __asm__ volatile("mov %0, sp" : "=r"(sp));
    uintptr_t lo = g_lark_base + 0x2fd4940;
    uintptr_t hi = g_lark_base + 0x65fb280;
    LOGI("STKSCAN %s #%u sp=%p base=%p", what, tag, (void *) sp, (void *) g_lark_base);
    int found = 0;
    uintptr_t *st = (uintptr_t *) sp;
    for (int i = 0; i < 400 && found < 30; i++) {
        uintptr_t v = st[i];
        if (v >= lo && v < hi) {
            LOGI("  stk[%3d] liblark+0x%lx", i, (unsigned long) (v - g_lark_base));
            found++;
        }
    }
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
// 查发送人昵称(chatters.name), 带缓存; id=1 视为系统。
static struct { long long id; char name[64]; } g_cn[128];
static volatile int g_cn_n = 0;
static void get_chatter_name(void *db, long long id, char *out, int outsz) {
    out[0] = 0;
    if (id == 1) { strncpy(out, "系统", outsz - 1); out[outsz - 1] = 0; return; }
    for (int i = 0; i < g_cn_n && i < 128; i++) if (g_cn[i].id == id) { strncpy(out, g_cn[i].name, outsz - 1); out[outsz - 1] = 0; return; }
    void *cdb = g_chatters_db ? g_chatters_db : db;   // chatters 在单独连接
    char c_name[64] = {0}, c_alias[64] = {0}, c_another[64] = {0};
    int prep = -1, rc = -1;
    if (cdb && p_prepare && p_finalize && p_col_blob && p_col_bytes && orig_step && p_bind64) {
        void *st = nullptr;
        prep = p_prepare(cdb, "SELECT name, alias, another_name FROM chatters WHERE id = ?", -1, &st, nullptr);
        if (prep == 0 && st) {
            p_bind64(st, 1, id);
            rc = orig_step(st);
            if (rc == 100) {
                const unsigned char *v; int l;
                v = (const unsigned char *) p_col_blob(st, 0); l = p_col_bytes(st, 0); if (v && l > 0) { int cp = l < 63 ? l : 63; memcpy(c_name, v, cp); c_name[cp] = 0; }
                v = (const unsigned char *) p_col_blob(st, 1); l = p_col_bytes(st, 1); if (v && l > 0) { int cp = l < 63 ? l : 63; memcpy(c_alias, v, cp); c_alias[cp] = 0; }
                v = (const unsigned char *) p_col_blob(st, 2); l = p_col_bytes(st, 2); if (v && l > 0) { int cp = l < 63 ? l : 63; memcpy(c_another, v, cp); c_another[cp] = 0; }
            }
            p_finalize(st);
        }
    }
    // 优先备注名(alias) > 群昵称(another_name) > 真名(name)
    const char *pick = c_alias[0] ? c_alias : (c_another[0] ? c_another : c_name);
    if (pick[0]) { strncpy(out, pick, outsz - 1); out[outsz - 1] = 0; }
    if (g_diag_log) { static volatile int cd = 0; if (++cd <= 30) flog("CHATTER-DIAG id=%lld prep=%d step=%d name=[%s] alias=[%s] another=[%s]", (long long) id, prep, rc, c_name, c_alias, c_another); }
    if (!out[0]) snprintf(out, outsz, "%lld", (long long) id);
    int idx = g_cn_n < 128 ? g_cn_n++ : (int)(((unsigned long long) id) % 128);
    g_cn[idx].id = id; strncpy(g_cn[idx].name, out, 63); g_cn[idx].name[63] = 0;
}
// 群名缓存: 平时从 UPDATE chats SET name=? 解析存下(踢群时 chats.name 会被清, 故要提前缓存)
static struct { long long chat; char name[96]; } g_names[64];
static volatile int g_names_n = 0;
static void cache_name(long long chat, const char *nm) {
    if (!chat || !nm || !nm[0]) return;
    for (int i = 0; i < g_names_n && i < 64; i++) if (g_names[i].chat == chat) { strncpy(g_names[i].name, nm, 95); g_names[i].name[95] = 0; return; }
    int idx = g_names_n < 64 ? g_names_n++ : (int)(((unsigned long long) chat) % 64);
    g_names[idx].chat = chat; strncpy(g_names[idx].name, nm, 95); g_names[idx].name[95] = 0;
}
static const char *lookup_name(long long chat) {
    for (int i = 0; i < g_names_n && i < 64; i++) if (g_names[i].chat == chat) return g_names[i].name;
    return nullptr;
}
// 退群提醒: 消息 id 去重(同一条成员变动系统消息在 re-sync 时会重复写入)。
static bool leave_seen(long long id) {
    for (int i = 0; i < g_leave_seen_n && i < 128; i++) if (g_leave_seen[i] == id) return true;
    int idx = g_leave_seen_n < 128 ? g_leave_seen_n++ : (int) (((unsigned long long) id) % 128);
    g_leave_seen[idx] = id;
    return false;
}
// 静默退群去重: 同一 (chat, chatter) 的成员表删除在 re-sync 时会重复。
static bool mleave_seen(long long key) {
    for (int i = 0; i < g_mleave_n && i < 128; i++) if (g_mleave_seen[i] == key) return true;
    int idx = g_mleave_n < 128 ? g_mleave_n++ : (int) (((unsigned long long) key) % 128);
    g_mleave_seen[idx] = key;
    return false;
}
static void leave_enqueue(const char *s) {
    pthread_mutex_lock(&g_leave_lock);
    int nt = (g_leave_tail + 1) % 16;
    if (nt != g_leave_head) { strncpy(g_leave_q[g_leave_tail], s, 255); g_leave_q[g_leave_tail][255] = 0; g_leave_tail = nt; }
    pthread_mutex_unlock(&g_leave_lock);
}
// 查群名(chats.name) —— 兜底(踢群时多半已被清空)
static void query_chat_name(void *db, long long chat, char *out, int outsz) {
    out[0] = 0;
    if (!p_prepare || !p_finalize || !p_col_blob || !p_col_bytes || !orig_step || !p_bind64) return;
    void *st = nullptr;
    if (p_prepare(db, "SELECT name FROM chats WHERE id = ?", -1, &st, nullptr) != 0 || !st) return;
    p_bind64(st, 1, chat);
    if (orig_step(st) == 100) {
        const unsigned char *nm = (const unsigned char *) p_col_blob(st, 0);
        int nl = p_col_bytes(st, 0);
        if (nm && nl > 0) { int cp = nl < outsz - 1 ? nl : outsz - 1; memcpy(out, nm, cp); out[cp] = 0; }
    }
    p_finalize(st);
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
// 把系统消息(邀请/移除/改群名)拼成清晰中文: "cheky 邀请了 张三"。
static void build_system_line(const char *text, void *db, char *out, int outsz) {
    long long fid = find_id_after(text, "from_user ");
    long long tid = find_id_after(text, "to_chatters ");
    char fn[64] = {0}, tn[64] = {0};
    if (fid) get_chatter_name(db, fid, fn, sizeof fn);
    if (tid) get_chatter_name(db, tid, tn, sizeof tn);
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
static void do_export(void *db) {
    if (!db || g_kicked_chat == 0 || !p_prepare || !p_finalize || !p_col_i64 || !p_col_blob || !p_col_bytes || !orig_step || !p_bind64)
        return;
    char gname[256] = {0};
    const char *cached = lookup_name(g_kicked_chat);   // 优先用平时缓存的群名(踢群时 chats.name 已被清)
    if (cached && cached[0]) { strncpy(gname, cached, sizeof gname - 1); }
    else query_chat_name(db, g_kicked_chat, gname, sizeof gname);
    void *st = nullptr;
    const char *q = "SELECT id, from_id, create_time, content FROM messages WHERE chat_id = ? ORDER BY create_time ASC";
    if (p_prepare(db, q, -1, &st, nullptr) != 0 || !st) return;
    p_bind64(st, 1, g_kicked_chat);
    char path[320];
    snprintf(path, sizeof path, "%s/kicked_%lld.txt", g_data_dir, (long long) g_kicked_chat);
    FILE *f = fopen(path, "w");
    if (!f) { p_finalize(st); return; }
    // 结构化: 首行群名; 之后每条 = 时间 \t 昵称 \t 文本 (Java 侧解析成聊天气泡)
    fprintf(f, "群: %s\n", gname[0] ? gname : "(未取到群名)");
    int n = 0;
    while (orig_step(st) == 100) {   // SQLITE_ROW=100
        long long from = p_col_i64(st, 1), ct = p_col_i64(st, 2);
        const unsigned char *blob = (const unsigned char *) p_col_blob(st, 3);
        int blen = p_col_bytes(st, 3);
        char text[4096]; int tp = 0; text[0] = 0;
        if (blob && blen > 0) pb_extract_buf(text, sizeof text, &tp, blob, blen, 0);
        text[tp < (int) sizeof text ? tp : (int) sizeof text - 1] = 0;
        while (tp > 0 && (text[tp - 1] == ' ' || text[tp - 1] == '\n' || text[tp - 1] == '\t')) text[--tp] = 0;
        for (int k = 0; k < tp; k++) if (text[k] == '\t' || text[k] == '\n') text[k] = ' ';   // 别破坏分隔
        char *tx = text; while (*tx == ' ') tx++;                                             // 去前导空格
        char nm[64]; get_chatter_name(db, from, nm, sizeof nm);
        for (int k = 0; nm[k]; k++) if (nm[k] == '\t') nm[k] = ' ';
        char tstr[24]; time_t tt = (time_t) ct; struct tm tmv; localtime_r(&tt, &tmv); strftime(tstr, sizeof tstr, "%m-%d %H:%M", &tmv);
        if (from == 1 && tx[0]) {   // 系统消息: 拼成"cheky 邀请了 张三"
            char sysl[256]; build_system_line(tx, db, sysl, sizeof sysl);
            for (int k = 0; sysl[k]; k++) if (sysl[k] == '\t' || sysl[k] == '\n') sysl[k] = ' ';
            fprintf(f, "%s\t系统\t%s\n", tstr, sysl);
        } else {
            fprintf(f, "%s\t%s\t%s\n", tstr, nm, tx[0] ? tx : "[图片/表情/语音等]");
        }
        n++;
    }
    p_finalize(st);
    fclose(f);
    if (n == 0) {   // 空导出(该群本地无消息): 删掉文件, 别在列表里留"未取到群名"的空壳
        remove(path);
        flog("保留被踢群: 空导出已丢弃 chat=%lld", (long long) g_kicked_chat);
        return;
    }
    flog("导出被踢群: 群=%s chat=%lld 共 %d 条 -> %s", gname[0] ? gname : "?", (long long) g_kicked_chat, n, path);
}

static volatile int g_recall_diag = 0;   // 1=撤回诊断: 记录所有 messages 写 + is_recalled 路径
static volatile int g_msg_dump = 0;      // 1=dump 前几条普通消息完整字段(摸文本 schema)
// 防已读探测结论(2026-07 实测): 开聊天读回执时 sqlite 只有 全列渲染SELECT / UPDATE me_read / REPLACE,
//   无"取要标已读 id 列表"的窄 SELECT -> 报告 id 在内存 async 层, 不过 sqlite -> 桌面"清空messageIds"无 sqlite 落点。
static volatile int g_read_sql_diag = 0;   // 探测已完成, 关闭
static int my_step(void *stmt) {
    const char *t = p_sql ? p_sql(stmt) : nullptr;
    // ── 防已读探测: 抓所有涉及 me_read / read 的 SELECT(去重, 只记不同模板) ──
    //   目的: 看读回执发送前, async 是否从 sqlite 取"要标已读的 message_id 列表"。
    if (g_read_sql_diag && t &&
        (strstr(t, "me_read") ||
         (strncmp(t, "SELECT", 6) == 0 && strstr(t, "messages") &&
          (strstr(t, "read") || strstr(t, "unread"))))) {
        static unsigned seen[128]; static volatile int nseen = 0;
        unsigned h = 2166136261u;
        for (const char *p = t; *p; ++p) { h ^= (unsigned char)*p; h *= 16777619u; }
        bool dup = false;
        for (int i = 0; i < nseen && i < 128; i++) if (seen[i] == h) { dup = true; break; }
        if (!dup) {
            if (nseen < 128) seen[nseen++] = h;
            LOGI("READSQL[%d]: %.340s", nseen, t);
        }
    }
    // ── 撤回诊断 v3: 看 messages 的 UNIQUE 索引 + 撤回 REPLACE 的 VALUES 实际值 ──
    if (g_recall_diag && t) {
        // (A) 抓 messages 的 CREATE TABLE / CREATE UNIQUE INDEX -> 定位唯一约束(REPLACE 冲突删原行的元凶)
        if ((strncmp(t, "CREATE", 6) == 0) && strstr(t, "messages")) {
            static volatile int cd = 0; if (++cd <= 20) LOGI("SCHEMA-DIAG: %.500s", t);
        }
        if ((strncmp(t, "REPLACE INTO `messages`", 23) == 0 || strncmp(t, "UPDATE `messages`", 17) == 0 ||
             strncmp(t, "DELETE FROM `messages`", 22) == 0)) {
            char *e = p_exp ? p_exp(stmt) : nullptr;
            if (e) {
                if (strstr(e, "is_recalled") || strstr(t, "is_recalled") || strstr(t, "DELETE")) {
                    // 打印 VALUES/WHERE 起始处(跳过长列名表), 看 id/position/cid/is_recalled 实际值
                    const char *vp = strstr(e, "VALUES ("); if (!vp) vp = strstr(e, "WHERE"); if (!vp) vp = e;
                    static volatile int rd = 0; int c = ++rd;
                    if (c <= 120) {
                        char verb[10] = {0}; strncpy(verb, t, 8);
                        LOGI("RECALL-DIAG #%d verb=%s val=%.320s", c, verb, vp);
                    }
                }
                p_free(e);
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
            static unsigned kseen[256]; static volatile int knseen = 0;
            unsigned h = 2166136261u;
            for (const char *p = t; *p; ++p) { h ^= (unsigned char)*p; h *= 16777619u; }
            bool dup = false;
            for (int i = 0; i < knseen && i < 256; i++) if (kseen[i] == h) { dup = true; break; }
            if (!dup) {
                if (knseen < 256) kseen[knseen++] = h;
                static volatile int kd = 0; int c = ++kd;
                if (c <= 256) flog("KICK-DIAG #%d %.300s", c, t);
            }
        }
    }
    // ── 退群/被移除 检测(Toast 提醒 + 记录) + 可行性诊断 ──
    //   数据来源已验证: removed/left 系统消息本就写进普通成员本地库(飞书仅 UI 层对普通成员隐藏)。
    if ((g_diag_log || g_leave_notify) && t) {
        // chatters 连接捕获(供查发送人名; 独立于"保留被踢群"开关, 否则只开退群提醒时查不到名字)
        if (p_db_handle && strstr(t, "`chatters`")) g_chatters_db = p_db_handle(stmt);
        // 诊断B: 成员级删除展开(可行性佐证用)
        if (g_diag_log && p_exp &&
            (strncmp(t, "DELETE FROM `non_departmental_chatters`", 39) == 0 ||
             (strncmp(t, "DELETE FROM `chat_chatter_ref`", 30) == 0 && strstr(t, "chatter_id")))) {
            char *e = p_exp(stmt);
            if (e) { static volatile int md = 0; if (++md <= 80) flog("成员变动探测B: %.220s", e); p_free(e); }
        }
        // ★ 静默退群/被移除 检测 —— 公司里离职自动退群【不广播系统消息】, 只删成员表:
        //   DELETE FROM `chat_chatter_ref` WHERE `chat_id` = <X> AND `chatter_id` IN (<a>,<b>,...)
        //   系统消息路径(下方 REPLACE INTO messages)漏掉这类, 故在此补: 解析 chat_id + 每个 chatter_id,
        //   查名记进 leave_log(只记录、不弹 Toast, 避免公司群批量退群刷屏)。
        //   仅认【带 chatter_id 的定向删除】= 真·某成员离开; 不含 chatter_id 的整表刷新是 re-sync, 不算。
        if (g_leave_notify && p_exp &&
            strncmp(t, "DELETE FROM `chat_chatter_ref`", 30) == 0 && strstr(t, "chatter_id")) {
            char *e = p_exp(stmt);
            if (e) {
                long long chat = 0;
                const char *cp = strstr(e, "chat_id");
                if (cp) { const char *q = cp + 7; while (*q && !(*q >= '0' && *q <= '9')) q++;
                          while (*q >= '0' && *q <= '9') { chat = chat * 10 + (*q - '0'); q++; } }
                const char *ip = strstr(e, "chatter_id");
                const char *lp = ip ? strstr(ip, "IN") : nullptr;
                if (chat && lp) {
                    void *db = p_db_handle ? p_db_handle(stmt) : nullptr;
                    char gname[128] = {0};
                    const char *cn = lookup_name(chat);
                    if (cn && cn[0]) strncpy(gname, cn, sizeof gname - 1);
                    else if (db) query_chat_name(db, chat, gname, sizeof gname);
                    const char *grp = gname[0] ? gname : "群聊";
                    const char *q = lp; while (*q && *q != '(') q++;   // 定位 (
                    while (*q && *q != ')') {
                        while (*q && !(*q >= '0' && *q <= '9') && *q != ')') q++;
                        if (!*q || *q == ')') break;
                        long long cid = 0; while (*q >= '0' && *q <= '9') { cid = cid * 10 + (*q - '0'); q++; }
                        if (cid && !mleave_seen(chat ^ (cid * 1000003LL))) {
                            char nm[64] = {0};
                            if (db) get_chatter_name(db, cid, nm, sizeof nm);
                            char msg[256];
                            if (nm[0] && (nm[0] < '0' || nm[0] > '9'))
                                snprintf(msg, sizeof msg, "%s 离开了群聊「%s」(成员移除/离职退群)", nm, grp);
                            else
                                snprintf(msg, sizeof msg, "成员(%lld) 离开了群聊「%s」(名字未取到)", (long long) cid, grp);
                            char tstr[24]; time_t tt = time(nullptr); struct tm tmv; localtime_r(&tt, &tmv);
                            strftime(tstr, sizeof tstr, "%m-%d %H:%M", &tmv);
                            char _lp[320]; snprintf(_lp, sizeof _lp, "%s/leave_log.txt", g_data_dir);
                            FILE *lf = fopen(_lp, "a");
                            if (lf) { fprintf(lf, "%s\t%s\n", tstr, msg); fclose(lf); }
                            flog("静默退群: %s", msg);
                        }
                    }
                }
                p_free(e);
            }
        }
        if (strncmp(t, "REPLACE INTO `messages`", 23) == 0 && p_exp) {
            char *e = p_exp(stmt);
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
                        static volatile int sm = 0; if (++sm <= 60) flog("系统消息探测A[%s]: %.220s", tag, text);
                    }
                    if (g_leave_notify && (isRemove || isLeft)) {
                        // ★ 群内持久显示: 飞书把"移除/退群"系统消息写 is_visible=0 对普通成员隐藏 ->
                        //   改绑成 1, 飞书即像渲染"邀请"消息一样把它显示进聊天窗(持久, 原生样式)。
                        //   每次写入都翻(re-sync 会重写), 与去重(Toast/记录)无关。
                        if (p_bind64) p_bind64(stmt, PARAM_IS_VISIBLE, 1LL);
                        // VALUES 前两个整数 = id(去重) + chat_id
                        long long mid = 0, chat = 0;
                        const char *v = strstr(e, "VALUES (");
                        if (v) { const char *q = v + 8; while (*q == ' ') q++;
                                 while (*q >= '0' && *q <= '9') { mid = mid * 10 + (*q - '0'); q++; }
                                 while (*q == ',' || *q == ' ') q++;
                                 while (*q >= '0' && *q <= '9') { chat = chat * 10 + (*q - '0'); q++; } }
                        if (mid && !leave_seen(mid)) {
                            void *db = p_db_handle ? p_db_handle(stmt) : nullptr;
                            long long tid = find_id_after(text, "to_chatters ");
                            long long fid = find_id_after(text, "from_user ");
                            char tn[64] = {0}, fn[64] = {0}, gname[128] = {0};
                            if (db) { if (tid) get_chatter_name(db, tid, tn, sizeof tn);
                                      if (fid) get_chatter_name(db, fid, fn, sizeof fn); }
                            const char *cn = lookup_name(chat);
                            if (cn && cn[0]) strncpy(gname, cn, sizeof gname - 1);
                            else if (db) query_chat_name(db, chat, gname, sizeof gname);
                            const char *who = tn[0] ? tn : "某成员";
                            const char *grp = gname[0] ? gname : "群聊";
                            char msg[256];
                            if (isLeft) snprintf(msg, sizeof msg, "%s 退出了群聊「%s」", who, grp);
                            else if (fn[0] && strcmp(fn, tn) != 0) snprintf(msg, sizeof msg, "%s 被 %s 移出群聊「%s」", who, fn, grp);
                            else snprintf(msg, sizeof msg, "%s 被移出群聊「%s」", who, grp);
                            leave_enqueue(msg);
                            char tstr[24]; time_t tt = time(nullptr); struct tm tmv; localtime_r(&tt, &tmv);
                            strftime(tstr, sizeof tstr, "%m-%d %H:%M", &tmv);
                            char _lp[320]; snprintf(_lp, sizeof _lp, "%s/leave_log.txt", g_data_dir);
                            FILE *lf = fopen(_lp, "a");
                            if (lf) { fprintf(lf, "%s\t%s\n", tstr, msg); fclose(lf); }
                            flog("退群提醒: %s", msg);
                        }
                    }
                }
                p_free(e);
            }
        }
    }
    // ── 保留被踢群聊天记录 ──
    //   被踢/退群时飞书按 chat_id 爆发式清群(DELETE FROM chat_xxx/non_departmental_chatters WHERE chat_id IN)
    //   紧接着 DELETE FROM messages 删记录。策略: 见"清群删除"就丢弃它并开 3s 窗口, 窗口内丢弃删消息。
    if (g_keep_kicked && t) {
        // 捕获 chatters 表所在连接(与 messages 不同库), 供导出查发送人昵称。
        if (p_db_handle && strstr(t, "`chatters`")) g_chatters_db = p_db_handle(stmt);
        // 平时缓存群名(踢群前 UPDATE chats SET name=? 里解析), 供导出用。
        if (strncmp(t, "UPDATE `chats`", 14) == 0 && strstr(t, "`name`")) {
            char *e = p_exp ? p_exp(stmt) : nullptr;
            if (e) {
                long long cid = 0; const char *wp = strstr(e, "WHERE");
                if (wp) { const char *q = strstr(wp, "id"); if (q) { while (*q && !(*q >= '0' && *q <= '9')) q++; while (*q >= '0' && *q <= '9') { cid = cid * 10 + (*q - '0'); q++; } } }
                const char *np = strstr(e, "`name`");
                if (cid && np) { const char *s = strchr(np, '\''); if (s) { s++; char nm[96]; int k = 0; while (s[k] && s[k] != '\'' && k < 95) { nm[k] = s[k]; k++; } nm[k] = 0; cache_name(cid, nm); } }
                p_free(e);
            }
        }
        // 注意: 导出【不再】在此处靠 strstr(t,"messages") 松触发 —— 那会命中任何含 messages 的语句
        //   (SELECT/UPDATE me_read/REPLACE 新消息...), 导致"别人退群整表刷新成员"被误判成被踢、
        //   把你还在的群整群导出, 还产出一堆空的"未取到群名"文件。
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
                char *e = p_exp ? p_exp(stmt) : nullptr;
                if (e) {
                    const char *cp = strstr(e, "chat_id");
                    if (cp) { const char *q = cp; while (*q && !(*q >= '0' && *q <= '9')) q++;
                              while (*q >= '0' && *q <= '9') { dcid = dcid * 10 + (*q - '0'); q++; } }
                    p_free(e);
                }
                if (dcid > 0) {
                    g_kicked_chat = dcid;
                    g_export_pending = 1;
                    g_kick_window = now_ms() + 5000;   // 5s 内的 DELETE FROM messages 判为本次拆群 -> 丢弃
                    static volatile int kc = 0; int c = ++kc;
                    if (c <= 80) flog("保留被踢群: 确认整群拆除(被踢/退群) chat=%lld %.80s", (long long) dcid, t);
                }
                // teardown 本身放行(让飞书正常清关系表); 只保 messages。
            }
            if (now_ms() < g_kick_window && strncmp(t, "DELETE FROM `messages`", 22) == 0) {
                // 真要删消息了 = 确认被踢/退群/解散(别人退群只刷成员表, 不会走到这) -> 此刻先导出(消息还在), 再丢弃删除。
                if (g_export_pending && p_db_handle) { g_export_pending = 0; do_export(p_db_handle(stmt)); }
                static volatile int km = 0; int c = ++km;
                if (c <= 80) flog("保留被踢群: 窗口内拦删消息 %.80s", t);
                return 101;   // 丢弃删消息 -> 聊天记录保留
            }
        }
    }
    bool drop_recall = false;
    if (g_recall_enabled && t && strncmp(t, "REPLACE INTO `messages`", 23) == 0) {
        char *e = p_exp ? p_exp(stmt) : nullptr;
        if (e) {
            if (recall_is_one(e)) {
                // 解析原 message id (VALUES 首值)
                long long oid = 0;
                const char *v = strstr(e, "VALUES (");
                if (v) { const char *q = v + 8; while (*q == ' ') q++; while (*q >= '0' && *q <= '9') { oid = oid * 10 + (*q - '0'); q++; } }
                if (oid > 0 && g_recall_drop == 0 && p_bind_text) {
                    // 方案3(7.70.x 持久提示): 撤回行改 id + cid 双改绑成唯一值 => 不撞 messages 唯一索引
                    //   (cid / (chat_id,position) 任一为 unique 时, 旧的只改 id 会因 cid 相同连带删原行)。
                    //   原行(is_recalled=0,内容完整)与撤回行(is_recalled=1)并存 -> 既见原文又见"撤回了一条消息"。
                    long long nid = oid ^ 0x4000000000000000LL;
                    p_bind64(stmt, PARAM_ID, nid);
                    char cidbuf[24]; snprintf(cidbuf, sizeof cidbuf, "ar%lld", nid);
                    p_bind_text(stmt, PARAM_CID, cidbuf, -1, SQLITE_TRANSIENT);
                    int n = ++g_neutralized;
                    LOGI("ANTIRECALL: 撤回→持久提示 oid=%lld nid=%lld cid=%s (total=%d)", oid, nid, cidbuf, n);
                    flog_recall(oid, n, "id+cid改绑");
                } else {
                    // 兜底: 直接丢弃撤回写入(原文保留, 无持久提示)
                    drop_recall = true;
                    int n = ++g_neutralized;
                    LOGI("ANTIRECALL: 撤回写入已丢弃(原文保留) oid=%lld (total=%d)", oid, n);
                    flog_recall(oid, n, "丢弃撤回写入");
                }
            }
            // 防已读破时序: 消息入库即提前追 message_id(第0列=id, VALUES 首值), 远早于读 -> 回执发出时已在监视.
            if (g_stealth_read) {
                const char *v = strstr(e, "VALUES (");
                if (v) { uint64_t mid = 0; const char *q = v + 8; while (*q == ' ') q++; while (*q >= '0' && *q <= '9') { mid = mid * 10 + (*q - '0'); q++; } if (mid > 0) watch_add(mid); }
            }
            p_free(e);
        }
        if (drop_recall) return 101;   // SQLITE_DONE: 谎报"写入完成", 实际不执行, 原行保留
    }
    // 防已读 中和 (手册稳定存储层咽喉): UPDATE `messages` SET `me_read` = ? WHERE id IN (?)
    //   参数1 = me_read 值; 改绑成 0 -> 消息本地保持未读, 已读上报进程"无 me_read 可报".
    if (g_antiread_sqlite && t && strncmp(t, "UPDATE `messages` SET `me_read` = ?", 35) == 0) {
        p_bind64(stmt, 1, 0LL);
        int n = ++g_antiread_cnt;
        LOGI("ANTIREAD: me_read 1->0 (total=%d)", n);
    }
    // 冻结 chats.read_position: 专门的 read_position 更新, 把 WHERE chat_id(参数9)改哨兵 -> 不命中 -> 读位置不前进
    if (g_antiread_sqlite && t &&
        strncmp(t, "UPDATE `chats` SET `read_position` = ?, `read_position_badge_count`", 65) == 0) {
        p_bind64(stmt, 9, 1LL);
        LOGI("ANTIREAD: froze chats.read_position (WHERE id->sentinel)");
    }
    // ── 防已读 stealth-read: ① 追踪 chats.rp/lmp(并标活跃) ② 重算 feed_channel 红点 ──
    if (g_stealth_read && t) {
        if (strncmp(t, "UPDATE `chats` SET", 18) == 0 &&
            (strstr(t, "read_position") || strstr(t, "last_message_position"))) {
            char *e = p_exp ? p_exp(stmt) : nullptr;
            if (e) {
                long long id = find_num(e, "`chats`.`id`");
                if (id > 0) {
                    watch_add((uint64_t) id);    // chat_id 也进监视(回执可能引用)
                    long long rp  = find_num(e, "`read_position`");
                    long long lmp = find_num(e, "`last_message_position`");
                    pthread_mutex_lock(&g_chats_lock);
                    ChatInfo *ci = chat_slot((uint64_t) id);
                    if (ci) {
                        if (rp  >= 0) { ci->rp = (int) rp; ci->active_until = now_ms() + ACTIVE_MS; }
                        if (lmp >= 0)   ci->lmp = (int) lmp;
                    }
                    pthread_mutex_unlock(&g_chats_lock);
                    if (rp >= 0) g_read_window = now_ms() + 10000;
                    static volatile int tk = 0; if (++tk <= 40)
                        LOGI("STEALTH-READ TRACK chat=%llu rp=%lld lmp=%lld", (unsigned long long) id, rp, lmp);
                }
                p_free(e);
            }
        } else if (strncmp(t, "UPDATE `messages` SET `me_read`", 30) == 0) {
            // 读时标记已读的消息 id 列表 -> 监视(回执引用这些 message_id)
            char *e = p_exp ? p_exp(stmt) : nullptr;
            if (e) {
                int got = watch_in_list(e);
                if (got > 0) { g_read_window = now_ms() + 10000; static volatile int mr = 0; if (++mr <= 40) LOGI("STEALTH-READ watch me_read ids=%d", got); }
                p_free(e);
            }
        } else if (strncmp(t, "REPLACE INTO `message_read_time`", 32) == 0) {
            char *e = p_exp ? p_exp(stmt) : nullptr;
            if (e) {
                const char *v = strstr(e, "VALUES (");
                if (v) { long long mid = 0; const char *q = v + 8; while (*q == ' ') q++; while (*q >= '0' && *q <= '9') { mid = mid * 10 + (*q - '0'); q++; } if (mid > 0) watch_add((uint64_t) mid); }
                p_free(e);
            }
        } else if (strncmp(t, "REPLACE INTO `feed_channel`", 27) == 0) {
            char *e = p_exp ? p_exp(stmt) : nullptr;
            if (e) {
                uint64_t id = 0; long long nmc = -1;
                if (feed_parse(e, &id, &nmc)) {
                    int desired = -1;
                    pthread_mutex_lock(&g_chats_lock);
                    for (int i = 0; i < 64; i++)
                        if (g_chats[i].used && g_chats[i].id == id) {
                            if (g_chats[i].rp >= 0 && g_chats[i].lmp >= 0) {
                                desired = g_chats[i].lmp - g_chats[i].rp; if (desired < 0) desired = 0;
                            }
                            break;
                        }
                    pthread_mutex_unlock(&g_chats_lock);
                    if (desired >= 0 && desired != (int) nmc) {
                        p_bind64(stmt, 10, (long long) desired);   // new_message_count = 本地真实未读
                        static volatile int bf = 0; int b = ++bf;
                        if (b <= 8) LOGI("STEALTH-READ badge: feed_channel new_message_count %lld->%d (chat=%llu)",
                                          nmc, desired, (unsigned long long) id);
                    }
                }
                p_free(e);
            }
        }
    }
    return orig_step(stmt);
}

// 在 /proc/self/maps 里找 so 的加载基址: 取【文件偏移=0 且以 ELF magic 开头】的映射
static uintptr_t find_base(const char *name) {
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
        if (off != 0 || !start) continue;                 // 只要含 ELF 头的首段
        const unsigned char *m = (const unsigned char *) start;
        if (m[0] == 0x7f && m[1] == 'E' && m[2] == 'L' && m[3] == 'F') { base = start; break; }
    }
    fclose(f);
    return base;
}

// 用 GNU_HASH / DT_HASH 求动态符号数量 (与 dynsym/dynstr 内存布局无关)
static size_t dynsym_count(uintptr_t gnu_hash, uintptr_t sysv_hash) {
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
        for (uint32_t i = 0; i < nbuckets; i++) if (buckets[i] > last) last = buckets[i];
        if (last < symoffset) return symoffset;
        while (!(chain[last - symoffset] & 1)) last++;
        return last + 1;
    }
    return 0;
}

// 解析已加载 ELF 的 .dynsym, 返回符号绝对地址
static void *resolve(uintptr_t base, const char *sym) {
    Elf64_Ehdr *eh = (Elf64_Ehdr *) base;
    Elf64_Phdr *ph = (Elf64_Phdr *) (base + eh->e_phoff);
    uintptr_t dynp = 0;
    for (int i = 0; i < eh->e_phnum; i++)
        if (ph[i].p_type == PT_DYNAMIC) { dynp = base + ph[i].p_vaddr; break; }
    if (!dynp) return NULL;

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
    if (!straddr || !symaddr) return NULL;
    if (straddr < base) straddr += base;
    if (symaddr < base) symaddr += base;
    if (gnuh  && gnuh  < base) gnuh  += base;
    if (sysvh && sysvh < base) sysvh += base;

    size_t nsym = dynsym_count(gnuh, sysvh);
    if (!nsym) {                                            // 兜底: 假设 dynsym 紧邻 dynstr
        if (straddr > symaddr && syment) nsym = (straddr - symaddr) / syment;
        else return NULL;
    }
    Elf64_Sym *st = (Elf64_Sym *) symaddr;
    const char *str = (const char *) straddr;
    for (size_t i = 0; i < nsym; i++) {
        if (st[i].st_name == 0 || st[i].st_value == 0) continue;
        if (strsz && st[i].st_name >= strsz) continue;
        if (strcmp(str + st[i].st_name, sym) == 0)
            return (void *) (base + st[i].st_value);
    }
    return NULL;
}

// ── 探针: 调用栈回溯 (_Unwind_Backtrace, 不依赖 frida) ───────────────
struct bt_state { uintptr_t *pcs; int count; int max; };
static _Unwind_Reason_Code bt_cb(struct _Unwind_Context *ctx, void *arg) {
    bt_state *s = (bt_state *) arg;
    uintptr_t pc = _Unwind_GetIP(ctx);
    if (pc && s->count < s->max) s->pcs[s->count++] = pc;
    return (s->count >= s->max) ? _URC_END_OF_STACK : _URC_NO_REASON;
}
static void dump_bt(const char *what, unsigned a1) {
    uintptr_t pcs[20];
    bt_state s = { pcs, 0, 20 };
    _Unwind_Backtrace(bt_cb, &s);
    LOGI("PROBE %s a1=%u(0x%x) base=%p frames=%d", what, a1, a1, (void *) g_lark_base, s.count);
    for (int i = 0; i < s.count; i++) {
        uintptr_t pc = pcs[i];
        if (g_lark_base && pc >= g_lark_base && pc < g_lark_base + 0x9000000)
            LOGI("  bt[%2d] liblark+0x%lx", i, (unsigned long) (pc - g_lark_base));
        else
            LOGI("  bt[%2d] %p", i, (void *) pc);
    }
}

// 8-寄存器参数透传 trampoline (覆盖 ≤8 个整型/指针参数的 aarch64 函数)
typedef void *(*gen8_t)(void *, void *, void *, void *, void *, void *, void *, void *);

// 宏: 为每个目标生成 {orig 指针 + handler}. handler 打前 N 次调用栈.
#define MK_PROBE(NM)                                                                       \
    static gen8_t orig_##NM = nullptr;                                                      \
    static void *my_##NM(void *a0, void *a1, void *a2, void *a3,                            \
                         void *a4, void *a5, void *a6, void *a7) {                          \
        g_last_read_ms = now_ms();                /* 开 socket-dump 窗口 */                  \
        static volatile int n = 0;                                                          \
        int c = __atomic_add_fetch(&n, 1, __ATOMIC_RELAXED);                                \
        if (c <= 25) dump_bt(#NM, (unsigned) (uintptr_t) a0);                               \
        return orig_##NM(a0, a1, a2, a3, a4, a5, a6, a7);                                   \
    }

// liblark v7.52.4 出站/已读 候选函数 (静态 xref 锚定; 升级会变):
//   uplink.rs   sub_53F7B64 / sub_53B51B4    frontier 出站上行 (所有发送必经)
//   me_read.rs  sub_50B7784 / sub_50B7E18    我方已读 上报逻辑
//   store.rs    sub_52A6D94                  read_message 存储/请求
//   services    sub_5298C3C                  services::read_message
//   export.rs   sub_51EFA50                  read_message::export
MK_PROBE(UPLINK1)   // 0x53F7B64
MK_PROBE(UPLINK2)   // 0x53B51B4
MK_PROBE(MEREAD1)   // 0x50B7784
MK_PROBE(MEREAD2)   // 0x50B7E18
MK_PROBE(STORE)     // 0x52A6D94
MK_PROBE(SERVICES)  // 0x5298C3C
MK_PROBE(EXPORT)    // 0x51EFA50

// ── 防已读 中和钩子: read_message::logic (sub_50C6BC4) ──
// 开聊天时该函数发起"已读上报". 调用者 sub_515CDCC 以语句形式调用且忽略其返回/缓冲,
// 故直接返回不调 orig = 跳过已读处理 -> 对方看不到"已读". 返回 void, 安全.
#define OFF_NEUT_READLOGIC 0x50C6BC4
static gen8_t orig_NEUT = nullptr;
static volatile int g_neutralize_read = 0;   // 1=开启防已读(整段跳过会崩, 暂关)
static void *my_NEUT(void *a0, void *a1, void *a2, void *a3, void *a4, void *a5, void *a6, void *a7) {
    if (g_neutralize_read) {
        static volatile int n = 0;
        int c = __atomic_add_fetch(&n, 1, __ATOMIC_RELAXED);
        if (c <= 8) LOGI("ANTIREAD: skipped read_message::logic (call %d) -> no read report", c);
        return nullptr;                        // 跳过 orig: 不上报已读
    }
    return orig_NEUT(a0, a1, a2, a3, a4, a5, a6, a7);
}

struct probe_ent { const char *name; uintptr_t off; void *repl; void **orig; };
static probe_ent g_probes[] = {
    { "UPLINK1",  0x53F7B64, (void *) my_UPLINK1,  (void **) &orig_UPLINK1  },
    { "UPLINK2",  0x53B51B4, (void *) my_UPLINK2,  (void **) &orig_UPLINK2  },
    { "MEREAD1",  0x50B7784, (void *) my_MEREAD1,  (void **) &orig_MEREAD1  },
    { "MEREAD2",  0x50B7E18, (void *) my_MEREAD2,  (void **) &orig_MEREAD2  },
    { "STORE",    0x52A6D94, (void *) my_STORE,    (void **) &orig_STORE    },
    { "SERVICES", 0x5298C3C, (void *) my_SERVICES, (void **) &orig_SERVICES },
    { "EXPORT",   0x51EFA50, (void *) my_EXPORT,   (void **) &orig_EXPORT   },
};

// ── socket 写族 hook: 仅在 read 窗口(read模块刚活动)内 dump 小包+调用栈 ──
// 目的: 抓主进程写给 wschannel 的明文 frontier 包, 内含 command (PUT_READ_MESSAGES=40).
static const long READ_WINDOW_MS = 6000;

static void dump_sock(const char *fn, const unsigned char *buf, long len) {
    if (len < 6 || len > 512) return;
    long dt = now_ms() - g_last_read_ms;
    if (dt < 0 || dt > READ_WINDOW_MS) return;       // 不在 read 窗口内, 忽略
    // 命中命令字节 0x28(=40) 在前 24 字节 -> 高亮
    bool hot = false;
    for (long i = 0; i < len && i < 24; i++) if (buf[i] == 0x28) { hot = true; break; }
    char hex[3 * 64 + 1]; int p = 0;
    for (long i = 0; i < len && i < 64; i++) { static const char *H = "0123456789abcdef"; hex[p++] = H[buf[i] >> 4]; hex[p++] = H[buf[i] & 15]; hex[p++] = ' '; }
    hex[p] = 0;
    LOGI("%s %s fn=%s len=%ld dt=%ldms  %s", hot ? "[SOCK40?]" : "[SOCK]", "", fn, len, dt, hex);
    if (hot) dump_bt("SOCK40-write", (unsigned) len);
}

typedef long (*send_t)(int, const void *, size_t, int);
typedef long (*sendto_t)(int, const void *, size_t, int, const void *, unsigned);
typedef long (*sendmsg_t)(int, const struct msghdr *, int);
typedef long (*writev_t)(int, const struct iovec *, int);
static send_t   orig_send = nullptr;
static sendto_t orig_sendto = nullptr;
static sendmsg_t orig_sendmsg = nullptr;
static writev_t orig_writev = nullptr;

static long my_send(int fd, const void *buf, size_t n, int flags) {
    dump_sock("send", (const unsigned char *) buf, (long) n);
    return orig_send(fd, buf, n, flags);
}
static long my_sendto(int fd, const void *buf, size_t n, int flags, const void *da, unsigned dl) {
    dump_sock("sendto", (const unsigned char *) buf, (long) n);
    return orig_sendto(fd, buf, n, flags, da, dl);
}
static long my_sendmsg(int fd, const struct msghdr *m, int flags) {
    if (m && m->msg_iovlen > 0 && m->msg_iov)
        dump_sock("sendmsg", (const unsigned char *) m->msg_iov[0].iov_base, (long) m->msg_iov[0].iov_len);
    return orig_sendmsg(fd, m, flags);
}
static long my_writev(int fd, const struct iovec *iov, int cnt) {
    if (iov && cnt > 0)
        dump_sock("writev", (const unsigned char *) iov[0].iov_base, (long) iov[0].iov_len);
    return orig_writev(fd, iov, cnt);
}

static void hook_libc(const char *name, void *repl, void **orig) {
    void *p = dlsym(RTLD_DEFAULT, name);
    if (p) { A64HookFunction(p, repl, orig); LOGI("PROBE libc hook %s @%p", name, p); }
    else LOGE("PROBE libc %s not found", name);
}

// SSL_write 探针: liblark 自带 BoringSSL, SSL_write(ssl, buf, num) 收到的是【加密前明文 frontier 包】.
// 普通 C 函数(非 async/非 BR-X10), 安全可 inline hook. 在此能看到 cmd 40 (PUT_READ_MESSAGES) 并丢弃.
// SSL_write 是 import: 读 liblark GOT 槽(base+0x60de610)拿加载时解析好的真实地址.
static const uintptr_t GOT_SSL_WRITE = 0x6c4d528;   // .got.plt 槽 (r2 ir: SET_64 SSL_write @0x6c4d528); 旧 0x60de610 是 .text 误值
typedef int (*sslwrite_t)(void *, const void *, int);
static sslwrite_t orig_sslwrite = nullptr;
// 读窗口相关法: dump 小二进制 frontier 帧 + 时间戳(ms,取模便于读). 受控"开聊天 vs 空闲"对比认读命令.
static int my_sslwrite(void *ssl, const void *buf, int num) {
    if (buf && num >= 4 && num <= 256) {
        const unsigned char *b = (const unsigned char *) buf;
        bool ascii = b[0] >= 0x20 && b[0] < 0x7f;     // 跳过纯文本(http/h2 头), 只看二进制 frontier 帧
        if (!ascii) {
            char hex[3 * 48 + 1]; int p = 0;
            int lim = num < 48 ? num : 48;
            for (int i = 0; i < lim; i++) { static const char *H = "0123456789abcdef"; hex[p++] = H[b[i] >> 4]; hex[p++] = H[b[i] & 15]; hex[p++] = ' '; }
            hex[p] = 0;
            LOGI("[SSLW] t=%ld len=%d %s", now_ms() % 1000000, num, hex);
        }
    }
    return orig_sslwrite(ssl, buf, num);
}

// 防已读 native: hook read_message/export.rs (sub_51EFA50) —— 建 cmd40 已读报告的干净叶子.
static gen8_t orig_export = nullptr;
static void *my_export(void *a0, void *a1, void *a2, void *a3, void *a4, void *a5, void *a6, void *a7) {
    int n = __atomic_add_fetch(&g_export_cnt, 1, __ATOMIC_RELAXED);
    if (n <= 30) LOGI("ANTIREAD-NATIVE export.rs hit #%d%s", n, g_antiread_export ? " [NEUTRALIZED]" : "");
    if (g_antiread_export) return nullptr;     // no-op: 不建/不发已读报告
    return orig_export(a0, a1, a2, a3, a4, a5, a6, a7);
}

// ── 防已读 cmd40 咽喉观测 (v7.69.6 静态定位): 统一打包函数 liblark+0x6111d8c ──
// 该函数 sub sp,#0x50 同步; w0=cmd id; x8=sret(输出包结构指针); 51 个 caller 各传不同 cmd.
// gen_put_packets(read_message/export.rs) 以 `movz w0,0x28; bl 此函数` 组已读包(PUT_READ_MESSAGES=40).
// C 替换函数会在调用 orig 前破坏 x8 -> 必崩. 故用寄存器保全的 naked 跳板, 第一轮仅观测、不中和.
#define OFF_PACK 0x6111d8c
extern "C" __attribute__((visibility("hidden"))) void *g_orig_pack = nullptr;

extern "C" __attribute__((used)) void log_pack_cmd(unsigned cmd) {
    g_last_read_ms = now_ms();
    static volatile int n = 0, n28 = 0;
    int c = __atomic_add_fetch(&n, 1, __ATOMIC_RELAXED);
    if (cmd == 0x28) {
        int k = __atomic_add_fetch(&n28, 1, __ATOMIC_RELAXED);
        LOGI("[PACK] cmd=0x28(40) PUT_READ_MESSAGES  <== 已读包! hit#%d (total %d)", k, c);
        if (k <= 6) scan_stack_for_lark("PUTREAD-pack", (unsigned) k);
    } else if (c <= 400) {
        LOGI("[PACK] cmd=0x%x(%u) call#%d", cmd, cmd, c);
    }
}

// naked 跳板: 保 x0-x9 + x30, 调 log_pack_cmd(w0), 复原(含 x8 sret), 尾跳 orig -> 不破坏 ABI.
extern "C" __attribute__((naked, used)) void my_PACK() {
    __asm__ volatile(
        "sub  sp, sp, #0x70\n"
        "stp  x0, x1, [sp, #0x00]\n"
        "stp  x2, x3, [sp, #0x10]\n"
        "stp  x4, x5, [sp, #0x20]\n"
        "stp  x6, x7, [sp, #0x30]\n"
        "stp  x8, x9, [sp, #0x40]\n"   // x8 = sret, 必须保住
        "str  x30,    [sp, #0x50]\n"
        "bl   log_pack_cmd\n"          // w0 已是 cmd id
        "ldp  x0, x1, [sp, #0x00]\n"
        "ldp  x2, x3, [sp, #0x10]\n"
        "ldp  x4, x5, [sp, #0x20]\n"
        "ldp  x6, x7, [sp, #0x30]\n"
        "ldp  x8, x9, [sp, #0x40]\n"
        "ldr  x30,    [sp, #0x50]\n"
        "add  sp, sp, #0x70\n"
        "adrp x16, g_orig_pack\n"
        "ldr  x16, [x16, #:lo12:g_orig_pack]\n"
        "br   x16\n"
    );
}

// ── v7.69.6 read_message 多函数 tracer (bl-target 法精确定位的真函数入口) ──
// 用 x8 保全的 naked 跳板, 仅打印"被调用", 判断开聊天时已读真正走哪条函数.
#define OFF_GPP   0x5aec998   // gen_put_packets (含 movz w0,0x28; bl)
#define OFF_SEND  0x5aed350   // logic.rs #read send success req_data
#define OFF_FG    0x5b36a88   // fg im.message.put_read 闸
extern "C" __attribute__((visibility("hidden"))) void *g_orig_gpp  = nullptr;
extern "C" __attribute__((visibility("hidden"))) void *g_orig_send = nullptr;
extern "C" __attribute__((visibility("hidden"))) void *g_orig_fg   = nullptr;

static const char *trace_name(unsigned id) {
    switch (id) {
        case 1: return "gen_put_packets@5aec998";
        case 2: return "send_success@5aed350";
        case 3: return "fg_check@5b36a88";
        case 4: return "F_5b1994c";      // STKSCAN 实质read处理 frame=0x210
        case 5: return "F_3ee3e28";      // frame=0x1000
        case 6: return "F_400fe50";
        case 7: return "F_3f3c66c";
        case 8: return "F_6373b40";
        default: return "?";
    }
}
static volatile int g_trace_cnt[16];
extern "C" __attribute__((used)) void log_trace(unsigned id) {
    g_last_read_ms = now_ms();
    int c = (id < 16) ? __atomic_add_fetch(&g_trace_cnt[id], 1, __ATOMIC_RELAXED) : 0;
    LOGI("[TRACE] %s #%d", trace_name(id), c);
}

#define MK_TRACE(FN, IDNUM, ORIGSYM)                          \
extern "C" __attribute__((naked, used)) void FN() {           \
    __asm__ volatile(                                         \
        "sub  sp, sp, #0x70\n"                                \
        "stp  x0, x1, [sp, #0x00]\n"                          \
        "stp  x2, x3, [sp, #0x10]\n"                          \
        "stp  x4, x5, [sp, #0x20]\n"                          \
        "stp  x6, x7, [sp, #0x30]\n"                          \
        "stp  x8, x9, [sp, #0x40]\n"                          \
        "str  x30,    [sp, #0x50]\n"                          \
        "mov  w0, #" #IDNUM "\n"                              \
        "bl   log_trace\n"                                    \
        "ldp  x0, x1, [sp, #0x00]\n"                          \
        "ldp  x2, x3, [sp, #0x10]\n"                          \
        "ldp  x4, x5, [sp, #0x20]\n"                          \
        "ldp  x6, x7, [sp, #0x30]\n"                          \
        "ldp  x8, x9, [sp, #0x40]\n"                          \
        "ldr  x30,    [sp, #0x50]\n"                          \
        "add  sp, sp, #0x70\n"                                \
        "adrp x16, " #ORIGSYM "\n"                            \
        "ldr  x16, [x16, #:lo12:" #ORIGSYM "]\n"              \
        "br   x16\n"                                          \
    );                                                        \
}
MK_TRACE(my_trace_gpp,  1, g_orig_gpp)
MK_TRACE(my_trace_send, 2, g_orig_send)
MK_TRACE(my_trace_fg,   3, g_orig_fg)
extern "C" __attribute__((visibility("hidden"))) void *g_orig_f4 = nullptr;
extern "C" __attribute__((visibility("hidden"))) void *g_orig_f5 = nullptr;
extern "C" __attribute__((visibility("hidden"))) void *g_orig_f6 = nullptr;
extern "C" __attribute__((visibility("hidden"))) void *g_orig_f7 = nullptr;
extern "C" __attribute__((visibility("hidden"))) void *g_orig_f8 = nullptr;
MK_TRACE(my_trace_f4, 4, g_orig_f4)
MK_TRACE(my_trace_f5, 5, g_orig_f5)
MK_TRACE(my_trace_f6, 6, g_orig_f6)
MK_TRACE(my_trace_f7, 7, g_orig_f7)
MK_TRACE(my_trace_f8, 8, g_orig_f8)
#define OFF_F4 0x5b1994c
#define OFF_F5 0x3ee3e28
#define OFF_F6 0x400fe50
#define OFF_F7 0x3f3c66c
#define OFF_F8 0x6373b40

// ★★ 防已读核心: 0x5aef148 = 已读发送 poll(0x5aed350)内部【逐条派发读回执给frontier上行】的transmit函数.
//   只1个caller(0x5aed840,read专属) / 无x8 sret / 返回被caller忽略 / caller随后照常打"send success"推进状态机.
//   替换成空返回 = 回执根本不出门, 状态机不崩. 这是"patch发送bl+伪造返回"的干净实现.
#define OFF_TRANSMIT 0x5aef148
static gen8_t orig_transmit = nullptr;
static volatile int g_drop_read = 1;   // 1=防已读(丢弃读回执发送); 0=放行
static void *my_transmit(void *a0, void *a1, void *a2, void *a3, void *a4, void *a5, void *a6, void *a7) {
    static volatile int n = 0;
    int c = __atomic_add_fetch(&n, 1, __ATOMIC_RELAXED);
    if (g_drop_read) {
        if (c <= 30) LOGI("[ANTIREAD] dropped read transmit #%d (回执未发送)", c);
        return nullptr;                       // 跳过实际派发, 返回被 caller 忽略
    }
    if (c <= 5) LOGI("[ANTIREAD] read transmit PASS #%d (g_drop_read=0)", c);
    return orig_transmit(a0, a1, a2, a3, a4, a5, a6, a7);
}

// ★ chat-open 读 future(sub_5AED840)的"发送派发"= sub_5AEDB20(→sub_5AEEE50 async 发送链).
//   liblark .text 安全可 hook. g_drop_readsend=1 => 跳过发送(构造照常). 双账号测对方是否还显示已读.
#define OFF_READSEND 0x5aedb20
static gen8_t orig_readsend = nullptr;
static volatile int g_drop_readsend = 1;
static void *my_readsend(void *a0, void *a1, void *a2, void *a3, void *a4, void *a5, void *a6, void *a7) {
    static volatile int n = 0;
    int c = __atomic_add_fetch(&n, 1, __ATOMIC_RELAXED);
    if (g_drop_readsend) {
        if (c <= 30) LOGI("[ANTIREAD] dropped read-send dispatch #%d (sub_5AEDB20)", c);
        return nullptr;
    }
    if (c <= 5) LOGI("[ANTIREAD] read-send PASS #%d", c);
    return orig_readsend(a0, a1, a2, a3, a4, a5, a6, a7);
}

// ★ 共享 chat 命令发送 sub_59AFFEC (8 caller, 含 auto_open). naked 跳板保 x8/x30, 记录 caller=看读时哪个发送点亮.
#define OFF_FRONTSEND 0x59affec
extern "C" __attribute__((visibility("hidden"))) void *g_orig_frontsend = nullptr;
static unsigned long g_drop_descs[32]; static int g_drop_n = 0;   // 命中任一则丢弃该发送
extern "C" __attribute__((used)) int log_frontsend(unsigned long desc, unsigned long caller) {
    g_last_read_ms = now_ms();
    static volatile int n = 0; int c = __atomic_add_fetch(&n, 1, __ATOMIC_RELAXED);
    for (int i = 0; i < g_drop_n; i++) if (desc == g_drop_descs[i]) {
        static volatile int k = 0; int kk = __atomic_add_fetch(&k, 1, __ATOMIC_RELAXED);
        if (kk <= 40) LOGI("[FRONTSEND] DROP desc=liblark+0x%lx (拦截) #%d", desc - g_lark_base, kk);
        return 1;
    }
    if (c <= 300 && g_lark_base) LOGI("[FRONTSEND] desc=liblark+0x%lx caller=+0x%lx #%d", desc - g_lark_base, caller - g_lark_base, c);
    return 0;
}
extern "C" __attribute__((naked, used)) void my_frontsend() {
    __asm__ volatile(
        "sub  sp, sp, #0x70\n"
        "stp  x0, x1, [sp, #0x00]\n stp x2, x3, [sp, #0x10]\n stp x4, x5, [sp, #0x20]\n"
        "stp  x6, x7, [sp, #0x30]\n stp x8, x9, [sp, #0x40]\n str x30, [sp, #0x50]\n"
        "mov  x0, x2\n mov x1, x30\n bl log_frontsend\n"
        "mov  x17, x0\n"                       // x17 = drop decision (caller-saved, survives ldp)
        "ldp  x0, x1, [sp, #0x00]\n ldp x2, x3, [sp, #0x10]\n ldp x4, x5, [sp, #0x20]\n"
        "ldp  x6, x7, [sp, #0x30]\n ldp x8, x9, [sp, #0x40]\n ldr x30, [sp, #0x50]\n"
        "add  sp, sp, #0x70\n"
        "cbnz x17, 1f\n"
        "adrp x16, g_orig_frontsend\n ldr x16, [x16, #:lo12:g_orig_frontsend]\n br x16\n"
        "1:\n mov x0, #0\n ret\n");
}

// ── 防已读核心: frontier 终端入队 sub_649FE5C 的内容法丢弃 ──
// a4(x3) 指向 v8[3]; a4[1] 指向序列化 payload. 若 payload 含活跃 chat 的 ASCII 十进制 id => 丢(读报告).
// caller sub_64A0364 忽略返回值且照常唤醒执行器 => 丢弃安全, 不破坏发收/心跳.
extern "C" __attribute__((visibility("hidden"))) void *g_orig_649 = nullptr;
static bool g_649_installed = false;
static unsigned char g_hook_bytes[8] = {0};   // 已 patch 的入口字节(用于检测被还原)
static volatile int g_rehook_cnt = 0;
extern "C" void my_649();                     // 前向声明(naked 跳板定义在下方)

// 维护: lark 反篡改会定期还原 liblark .text, 抹掉我们的 inline hook. 由 Java 线程周期调用本函数,
// 检测入口字节被还原则重打 hook (保持像 frida 那样新鲜).
static void maintain_hook() {
    // ★ sqlite3_step 反篡改维护: 飞书 7.70.x 的 PV-MON 会把 libsqlcipher .text 还原,
    //   抹掉我们的 inline hook. 检测入口字节被改回原样则立即重装. (必须在 649 早返回之前)
    if (g_step_ep && orig_step && memcmp(g_step_ep, g_step_hookbytes, 16) != 0) {
        A64HookFunction(g_step_ep, (void *) my_step, (void **) &orig_step);
        memcpy(g_step_hookbytes, g_step_ep, 16);
        int c = __atomic_add_fetch(&g_step_rehook, 1, __ATOMIC_RELAXED);
        if (c <= 40 || c % 100 == 0) {
            LOGI("ANTIRECALL: re-hooked sqlite3_step (anti-tamper restored it) #%d", c);
            if (c == 1 || c % 100 == 0)
                flog("防撤回: 飞书反篡改还原了 hook, 已重装 (第%d次) —— 属正常, hook 仍在工作", c);
        }
    }
    if (!g_649_installed || !g_lark_base) return;
    void *ep = (void *) (g_lark_base + OFF_ENQ649);
    if (memcmp(ep, g_hook_bytes, 8) != 0) {       // 被还原了
        A64HookFunction(ep, (void *) my_649, (void **) &g_orig_649);
        memcpy(g_hook_bytes, ep, 8);
        int c = __atomic_add_fetch(&g_rehook_cnt, 1, __ATOMIC_RELAXED);
        if (c <= 30 || c % 50 == 0) LOGI("STEALTH-READ: re-hooked sub_649FE5C (anti-tamper restored it) #%d", c);
    }
}
static volatile int g_drop_all_test = 0;   // 1=丢弃所有 frontier 入队 (验证用)
extern "C" __attribute__((used)) int decide_649(void *a4) {
    if (!g_stealth_read || !a4) return 0;
    if (g_drop_all_test) return 1;
    unsigned char buf[384], abuf[64];
    int n = 0, an = 0;
    void *q1 = nullptr;
    an = safe_copy(a4, abuf, 64);
    if (an >= 16) q1 = *(void **) (abuf + 8);   // a4[1] (经安全读取出指针)
    if (q1) n = safe_copy(q1, buf, 360);
    long now = now_ms();
    // 监视 id 匹配: 读时标记已读的 message_id/chat_id, 在 payload 里按 ASCII 或二进制小端两种编码找 -> 命中=读回执.
    int drop = 0, nactive = 0;
    pthread_mutex_lock(&g_watch_lock);
    for (int i = 0; i < 256 && !drop; i++) {
        if (!g_watch[i].used || g_watch[i].expire < now) continue;
        nactive++;
        const char *a = g_watch[i].ascii; size_t al = (size_t) g_watch[i].alen;
        if ((n  > 0 && memmem(buf,  (size_t) n,  a, al)) || (an > 0 && memmem(abuf, (size_t) an, a, al)) ||
            (n  > 0 && memmem(buf,  (size_t) n,  g_watch[i].le, 8)) || (an > 0 && memmem(abuf, (size_t) an, g_watch[i].le, 8))) {
            drop = 1;
        }
    }
    pthread_mutex_unlock(&g_watch_lock);
    if (g_sr_debug && now < g_read_window) {
        // 读报告签名: 0a 13 后跟 19 个 ASCII 数字(chat_id 字符串字段). 不依赖 active 匹配, 直接探测回执是否经此.
        int sig = 0;
        for (int i = 0; i + 21 <= n; i++) {
            if (buf[i] == 0x0a && buf[i + 1] == 0x13) {
                int ok = 1; for (int j = 0; j < 19; j++) if (buf[i + 2 + j] < '0' || buf[i + 2 + j] > '9') { ok = 0; break; }
                if (ok) { sig = 1; break; }
            }
        }
        char _sp[320]; snprintf(_sp, sizeof _sp, "%s/sr_diag.log", g_data_dir);
        FILE *f = fopen(_sp, "a");
        if (f) { fprintf(f, "decide a3=? nactive=%d n=%d drop=%d sig=%d\n", nactive, n, drop, sig); fclose(f); }
        if (sig && !drop) {
            char hx[3 * 64 + 1]; int hp = 0; int lo = 0;
            // 找 sig 起点附近 dump
            for (int i = 0; i + 21 <= n; i++) if (buf[i]==0x0a&&buf[i+1]==0x13){lo=i;break;}
            int st2 = lo > 8 ? lo - 8 : 0;
            for (int i = st2; i < n && i < st2 + 64; i++) { static const char *H = "0123456789abcdef"; hx[hp++] = H[buf[i] >> 4]; hx[hp++] = H[buf[i] & 15]; hx[hp++]=' '; }
            hx[hp] = 0;
            FILE *f2 = fopen(_sp, "a");   // 复用外层 _sp (同一 g_sr_debug 块)
            if (f2) { fprintf(f2, "  SIG-NODROP payload=%s\n", hx); fclose(f2); }
        }
    }
    if (drop) {
        static volatile int k = 0; int kk = ++k;
        if (kk <= 8 || kk % 50 == 0) LOGI("STEALTH-READ: dropped read receipt #%d", kk);
    }
    return drop;
}
extern "C" __attribute__((naked, used)) void my_649() {
    __asm__ volatile(
        "sub  sp, sp, #0x70\n"
        "stp  x0, x1, [sp, #0x00]\n stp x2, x3, [sp, #0x10]\n stp x4, x5, [sp, #0x20]\n"
        "stp  x6, x7, [sp, #0x30]\n stp x8, x9, [sp, #0x40]\n str x30, [sp, #0x50]\n"
        "mov  x0, x3\n bl decide_649\n mov x17, x0\n"
        "ldp  x0, x1, [sp, #0x00]\n ldp x2, x3, [sp, #0x10]\n ldp x4, x5, [sp, #0x20]\n"
        "ldp  x6, x7, [sp, #0x30]\n ldp x8, x9, [sp, #0x40]\n ldr x30, [sp, #0x50]\n"
        "add  sp, sp, #0x70\n"
        "cbnz x17, 1f\n"
        "adrp x16, g_orig_649\n ldr x16, [x16, #:lo12:g_orig_649]\n br x16\n"
        "1:\n mov x0, #0\n ret\n");
}

// 安装探针: 找到 liblark base 后给候选集下 inline hook. 一次性.
static void install_probe() {
#if PROBE_ENABLED
    if (g_probe_installed) return;
    uintptr_t base = find_base("liblark.so");
    if (!base) return;                       // liblark 还没加载, 下次再试
    g_lark_base = base;
    int n = 0;
    // ── 防已读 stealth-read (已验证): hook frontier 终端入队丢回执 ──
    if (g_stealth_read && !g_649_installed) {
        if (g_pfd[0] < 0 && pipe2(g_pfd, O_NONBLOCK) != 0) { g_pfd[0] = g_pfd[1] = -1; }
        A64HookFunction((void *) (base + OFF_ENQ649), (void *) my_649, (void **) &g_orig_649);
        memcpy(g_hook_bytes, (void *) (base + OFF_ENQ649), 8);   // 记录已 patch 的入口字节
        g_649_installed = true;
        LOGI("STEALTH-READ: hooked sub_649FE5C @liblark+0x%x (receipt drop + badge recalc armed)", OFF_ENQ649);
    }
    (void) decide_649;
    // ── 已恢复纯防撤回 (2026-06-24 实验后): 防已读各 hook 全撤, 仅保函数定义避免 unused 告警. ──
    //   实验结论(本轮新增, 双账号实证):
    //     实测1 transmit-drop @0x5aef148 命中但对方仍显示已读 => 同步 transmit 非真实送出点.
    //     实测2 packer-observer @0x6111d8c 读时主进程内静默 => 读回执不经此同步打包点.
    //   两者共同坐实 doc §4: 真实已读上报为异步, 同步 inline-hook 不可达. 续攻方向改为"生成/特性闸"层(见 doc §8).
    // ── 纯防撤回 (2026-06-24 Path B 调查后恢复): 防已读 hook 全撤. ──
    //   Path B 结论(naked跳板已校验 g_orig 非空=确实装上): put_read 生成路径(gen_put_packets 0x5aec998 +
    //   其打包 0x6111d8c)开 1:1 聊天读消息时**根本不跑**, 后台心跳 25s 内 0x6111d8c 也零命中 ⇒
    //   0x6111d8c 非"统一打包函数"(cpp 旧注静态猜错), 只服务 put_read; 整个 cmd 0x28 路径与"开聊天→对方已读"无关.
    //   ⇒ Path B 作废. 已读载体另在它处(疑 auto_open_v2 / read_position 同步), 见 doc §8 重定向.
    (void) OFF_TRANSMIT; (void) my_transmit; (void) orig_transmit; (void) g_drop_read;
    (void) my_trace_send; (void) g_orig_send; (void) OFF_SEND;
    (void) my_trace_fg; (void) g_orig_fg; (void) OFF_FG;
    (void) my_trace_f4; (void) g_orig_f4; (void) OFF_F4;
    (void) my_trace_f5; (void) g_orig_f5; (void) OFF_F5;
    (void) my_trace_f6; (void) g_orig_f6; (void) OFF_F6;
    (void) my_trace_f7; (void) g_orig_f7; (void) OFF_F7;
    (void) my_trace_f8; (void) g_orig_f8; (void) OFF_F8;
    (void) trace_name; (void) log_trace; (void) g_trace_cnt;
    // ✗ read_message/put_read 子系统 已整条排除 (2026-06-24 双账号实证): neuter sub_5AEDB20(发送派发)命中但
    //   对方仍已读; 加上 transmit=埋点、gen_put_packets 开聊天不触发 ⇒ chat-open 已读不经 read_message 子系统.
    //   ⇒ 载体在 chat auto_open / read_position 子系统(chat-open handler 里 0x5774c64 闸那条), 待 IDA 反查其 send.
    // ★ 防已读单拦扫 (2026-06-25): 一次只拦一个候选 desc, 双账号测(对方已读? 自己收发正常?). 见 doc §9.
    // ✗ sub_59AFFEC 全拦18desc仍已读=排除; sub_57B62E8 trace 收/读均0命中(hook疑失效或链路系反编译退化误判)=无信号.
    //   ⇒ 已读发送通道仍未找到. 恢复纯防撤回. (续法见 doc §8.9: 从 read_position 数据流正向追.)
    g_drop_n = 0;
    (void) my_frontsend; (void) g_orig_frontsend; (void) log_frontsend; (void) OFF_FRONTSEND; (void) g_drop_descs;
    (void) OFF_READSEND; (void) my_readsend; (void) orig_readsend; (void) g_drop_readsend;
    (void) my_sslwrite; (void) orig_sslwrite; (void) GOT_SSL_WRITE;
    (void) g_probes; (void) my_NEUT; (void) orig_NEUT;
    (void) my_export; (void) orig_export; (void) OFF_EXPORT; (void) g_export_cnt;
    (void) scan_stack_for_lark;
    // NOTE: libc socket hooks (send/sendmsg/...) 会卡死飞书启动(反篡改/不稳), 已禁用.
    (void) my_send; (void) my_sendto; (void) my_sendmsg; (void) my_writev; (void) hook_libc;
    g_probe_installed = true;
    (void) n;
    LOGI("ANTI-RECALL + STEALTH-READ mode (liblark base=%p)", (void *) base);
#endif
}

// Java 日志走专属 tag "antiread-j" (避开被其它 LSPosed 模块刷爆的 LSPosedFramework tag)
extern "C" JNIEXPORT void JNICALL
Java_com_chekayo_feishuantirecall_AntiRecall_nativeLog(JNIEnv *env, jclass, jstring s) {
    if (!s) return;
    const char *c = env->GetStringUTFChars(s, nullptr);
    __android_log_print(ANDROID_LOG_INFO, "antiread-j", "%s", c ? c : "(null)");
    if (c) env->ReleaseStringUTFChars(s, c);
}

// 由 Java 维护线程周期调用: 重打被反篡改还原的 liblark hook.
extern "C" JNIEXPORT void JNICALL
Java_com_chekayo_feishuantirecall_AntiRecall_nativeMaintain(JNIEnv *, jclass) {
    maintain_hook();
}

// fuck lark 设置面板: 实时开关防撤回中和
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
        strncpy(g_data_dir, c, sizeof g_data_dir - 1);
        g_data_dir[sizeof g_data_dir - 1] = 0;
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

extern "C" JNIEXPORT jboolean JNICALL
Java_com_chekayo_feishuantirecall_AntiRecall_tryInstall(JNIEnv *, jclass) {
    install_probe();   // 防已读调研探针 (与 sqlcipher 防撤回 hook 互不影响)

    // 只有 sqlcipher 与 探针 都搞定才停轮询 (探针需等 liblark 加载)
    if (g_installed) return g_probe_installed ? JNI_TRUE : JNI_FALSE;
    uintptr_t base = find_base("libsqlcipher.so");
    if (!base) return JNI_FALSE;               // 库还没加载, 继续重试

    void *step = resolve(base, "sqlite3_step");
    p_sql    = (sql_t)    resolve(base, "sqlite3_sql");
    p_exp    = (expsql_t) resolve(base, "sqlite3_expanded_sql");
    p_free   = (free_t)   resolve(base, "sqlite3_free");
    p_bind64 = (bind64_t) resolve(base, "sqlite3_bind_int64");
    p_bind_blob = (bindblob_t) resolve(base, "sqlite3_bind_blob");
    p_bind_text = (bindtext_t) resolve(base, "sqlite3_bind_text");
    // 导出被踢群记录用
    p_db_handle = (dbhandle_t)  resolve(base, "sqlite3_db_handle");
    p_prepare   = (prepare_t)   resolve(base, "sqlite3_prepare_v2");
    p_finalize  = (finalize_t)  resolve(base, "sqlite3_finalize");
    p_col_i64   = (coli64_t)    resolve(base, "sqlite3_column_int64");
    p_col_blob  = (colblob_t)   resolve(base, "sqlite3_column_blob");
    p_col_bytes = (colbytes_t)  resolve(base, "sqlite3_column_bytes");
    LOGI("base=%p step=%p sql=%p exp=%p free=%p bind64=%p blob=%p",
         (void *) base, step, (void *) p_sql, (void *) p_exp, (void *) p_free, (void *) p_bind64, (void *) p_bind_blob);
    if (!step || !p_sql || !p_exp || !p_free || !p_bind64) {
        LOGE("resolve failed (sym missing)");
        g_installed = true;                     // sqlcipher 这边放弃, 但仍等探针
        return g_probe_installed ? JNI_TRUE : JNI_FALSE;
    }
    A64HookFunction(step, (void *) my_step, (void **) &orig_step);
    g_step_ep = step;
    memcpy(g_step_hookbytes, step, 16);   // 记录 hook 后的入口字节, 供 maintain 检测被还原
    g_installed = true;
    LOGI("ANTI-RECALL native hook installed on sqlite3_step @%p", step);
    return g_probe_installed ? JNI_TRUE : JNI_FALSE;
}
