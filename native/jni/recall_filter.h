#pragma once

// 防撤回 SQL 策略同时适配 SQLCipher/WCDB：在宿主原连接上调用原始 SQLite 入口；
// JNI 只查询已提交的内存快照，绝不把数据库连接交给 UI 线程。
#include <algorithm>
#include <cctype>
#include <climits>
#include <map>
#include <mutex>
#include <set>
#include <string>
#include <vector>

namespace recall {
constexpr int PASS = -1, ROW = 100, DONE = 101;
struct Api {
    int (*step)(void*);
    int (*prepare)(void*, const char*, int, void**, const char**);
    int (*finalize)(void*);
    long long (*integer)(void*, int);
    const char* (*filename)(void*, const char*);
    int (*autocommit)(void*);
    const char* (*error)(void*) = nullptr;
    void (*trace)(const char*) = nullptr;
    void* (*next_stmt)(void*, void*) = nullptr;
    int (*busy)(void*) = nullptr;
    int (*reset)(void*) = nullptr;
    void* (*db_mutex)(void*) = nullptr;
    void (*enter)(void*) = nullptr;
    void (*leave)(void*) = nullptr;
};
struct ConnectionLock {
    const Api &a; void *mutex;
    ConnectionLock(const Api &api, void *db) : a(api), mutex(a.db_mutex && a.enter && a.leave ? a.db_mutex(db) : nullptr) {
        if (mutex) a.enter(mutex);
    }
    ~ConnectionLock() { if (mutex) a.leave(mutex); }
};
struct Token { std::string s; size_t begin, end; bool quoted; };
inline std::string lower(std::string s) {
    for (char &c : s) c = (char)std::tolower((unsigned char)c);
    return s;
}
inline long long number(const std::string &s) {
    if (s.empty()) return 0;
    long long n = 0;
    for (char c : s) {
        if (c < '0' || c > '9' || n > (LLONG_MAX - (c - '0')) / 10) return 0;
        n = n * 10 + c - '0';
    }
    return n;
}
struct Owners { std::mutex mutex; std::map<std::string, std::string> ids; };
inline Owners &owners() { static Owners value; return value; }
inline void bindOwner(const std::string &storage, const std::string &uid) {
    if (!number(uid) || storage.empty()) return;
    for (char c : storage) if (!std::isxdigit((unsigned char)c)) return;
    auto &o = owners();
    std::lock_guard<std::mutex> guard(o.mutex);
    o.ids[storage] = uid;
}
// SDK 库目录可能是账号哈希；只有已验证绑定的目录才映射为账号，未知目录不猜测。
inline std::string owner(const std::string &path) {
    const std::string prefix = "/sdk_storage/";
    size_t p = path.find(prefix);
    if (p == std::string::npos) return "";
    p += prefix.size();
    size_t e = path.find('/', p);
    if (e == std::string::npos) return "";
    std::string uid = path.substr(p, e - p);
    if (number(uid) > 0) return uid;
    auto &o = owners();
    std::lock_guard<std::mutex> guard(o.mutex);
    auto it = o.ids.find(uid);
    return it == o.ids.end() ? "" : it->second;
}
// 用词法扫描判断撤回语句，避免消息正文/注释里的 is_recalled 被误认成 SQL 赋值。
inline std::vector<Token> lex(const std::string &sql) {
    std::vector<Token> out;
    size_t p = 0;
    while (p < sql.size()) {
        unsigned char c = sql[p];
        if (std::isspace(c)) { ++p; continue; }
        if (sql.compare(p, 2, "--") == 0) {
            p = sql.find('\n', p); if (p == std::string::npos) break; continue;
        }
        if (sql.compare(p, 2, "/*") == 0) {
            size_t end = sql.find("*/", p + 2);
            if (end == std::string::npos) return {};
            p = end + 2; continue;
        }
        size_t start = p;
        std::string s;
        bool quoted = c == '\'' || c == '"' || c == '`' || c == '[';
        if (quoted) {
            char stop = c == '[' ? ']' : c;
            ++p; bool closed = false;
            while (p < sql.size()) {
                char x = sql[p++];
                if (x == stop) {
                    if (p < sql.size() && sql[p] == stop && stop != ']') { s += x; ++p; }
                    else { closed = true; break; }
                } else s += x;
            }
            if (!closed) return {};
        } else if (std::isalnum(c) || c == '_') {
            while (p < sql.size() && (std::isalnum((unsigned char)sql[p]) || sql[p] == '_')) s += sql[p++];
        } else { s += sql[p++]; }
        out.push_back({s, start, p, quoted});
    }
    return out;
}
inline bool word(const std::vector<Token> &t, size_t p, const char *s) {
    return p < t.size() && lower(t[p].s) == s;
}
inline bool keyword(const std::vector<Token> &t, size_t p, const char *s) {
    return p < t.size() && !t[p].quoted && word(t, p, s);
}
struct Write {
    bool valid = false, update = false;
    long long id = 0;
    std::string prefix, where;
};
inline Write parse(const std::string &sql) {
    Write w;
    auto t = lex(sql);
    size_t p = 0;
    if (keyword(t, p, "replace") && keyword(t, p + 1, "into")) p += 2;
    else if (keyword(t, p, "insert") && keyword(t, p + 1, "or")
            && keyword(t, p + 2, "replace") && keyword(t, p + 3, "into")) p += 4;
    else if (keyword(t, p, "update")) { w.update = true; ++p; }
    else return w;
    if (word(t, p, "main") && word(t, p + 1, ".")) p += 2;
    if (!word(t, p++, "messages")) return w;
    if (!w.update) {
        if (!word(t, p++, "(")) return w; // no guessed legacy column offsets
        std::vector<std::string> cols;
        while (p < t.size()) {
            cols.push_back(lower(t[p++].s));
            if (word(t, p, ")")) { ++p; break; }
            if (!word(t, p++, ",")) return w;
        }
        if (!keyword(t, p++, "values") || !word(t, p++, "(")) return w;
        size_t start = p; int depth = 0; size_t col = 0;
        bool recalled = false;
        for (; p < t.size(); ++p) {
            bool end = !t[p].quoted && t[p].s == ")" && depth == 0;
            bool comma = !t[p].quoted && t[p].s == "," && depth == 0;
            if (end || comma) {
                if (col >= cols.size()) return w;
                if (p == start + 1) {
                    if (cols[col] == "id") w.id = number(t[start].s);
                    if (cols[col] == "is_recalled") recalled = t[start].s == "1";
                }
                ++col;
                if (end) { ++p; break; }
                start = p + 1;
            } else if (!t[p].quoted) {
                if (t[p].s == "(") ++depth;
                if (t[p].s == ")") --depth;
            }
        }
        if (word(t, p, ";")) ++p;
        w.valid = recalled && w.id > 0 && col == cols.size() && p == t.size();
        return w;
    }
    if (!keyword(t, p++, "set")) return w;
    bool recalled = false;
    while (p < t.size()) {
        std::string col = lower(t[p++].s);
        // Rewriting identity or a RETURNING/limited UPDATE needs a different plan.
        if (col == "id" || col == "chat_id" || col == "from_id") return w;
        if (!word(t, p++, "=")) return w;
        size_t start = p; int depth = 0;
        for (; p < t.size(); ++p) {
            if (!t[p].quoted) {
                if (depth == 0 && (t[p].s == "," || keyword(t, p, "where") || t[p].s == ";")) break;
                if (t[p].s == "(") ++depth;
                if (t[p].s == ")") --depth;
            }
        }
        if (col == "is_recalled") recalled = p == start + 1 && t[start].s == "1";
        if (keyword(t, p, "where")) break;
        if (!word(t, p++, ",")) return w;
    }
    if (!recalled || !keyword(t, p, "where")) return w;
    w.prefix = sql.substr(0, t[p].begin);
    size_t start = ++p;
    if (start >= t.size()) return w;
    size_t end = t.size();
    if (word(t, end - 1, ";")) --end;
    for (; p < end; ++p) {
        if (keyword(t, p, "returning") || keyword(t, p, "limit")
                || keyword(t, p, "order") || keyword(t, p, ";")) return w;
    }
    if (end <= start) return w;
    w.where = sql.substr(t[start].begin, t[end - 1].end - t[start].begin);
    w.valid = true;
    return w;
}

struct Statement {
    const Api &a; void *s = nullptr;
    int prepared;
    Statement(const Api &api, void *db, const std::string &sql) : a(api) {
        prepared = a.prepare(db, sql.c_str(), -1, &s, nullptr);
        if (prepared != 0) {
            if (s) a.finalize(s);
            s = nullptr;
        }
    }
    ~Statement() { if (s) a.finalize(s); }
    int step() { return s ? a.step(s) : prepared; }
};
inline int exec(const Api &a, void *db, const std::string &sql) {
    Statement q(a, db, sql); return q.step();
}
using Mark = std::pair<long long, long long>; // chat, message
struct Snapshot { std::string uid; std::set<Mark> marks; };
class Store {
    std::mutex mutex;
    std::map<std::string, Snapshot> snapshots;
    std::map<std::pair<const void*, void*>, std::string> pending;
public:
    bool contains(const std::string &uid, long long chat, long long id) {
        if (uid.empty() || chat <= 0 || id <= 0) return false;
        std::lock_guard<std::mutex> guard(mutex);
        for (const auto &entry : snapshots) {
            if (entry.second.uid == uid && entry.second.marks.count({chat, id})) return true;
        }
        return false;
    }
    // 仅在没有未提交事务或活动 cursor 时读取标记，避免旧 WAL 快照覆盖新结果。
    bool fresh(const Api &a, void *db) {
        if (!a.autocommit(db) || !a.next_stmt || !a.busy) return false;
        for (void *s = a.next_stmt(db, nullptr); s; s = a.next_stmt(db, s))
            if (a.busy(s)) return false;
        return true;
    }
    bool load(const Api &a, void *db, const std::string &path) {
        ConnectionLock connection(a, db);
        if (!fresh(a, db)) return false;
        Snapshot value;
        value.uid = owner(path);
        if (value.uid.empty()) return false;
        bool hasTable;
        {
            Statement exists(a, db, "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='feishukit_recalled_v1'");
            if (exists.step() != ROW) { if (a.trace) a.trace("snapshot lookup failed"); return false; }
            hasTable = a.integer(exists.s, 0) > 0;
        }
        if (hasTable) {
            Statement q(a, db, "SELECT chat_id,message_id FROM feishukit_recalled_v1");
            if (!q.s) return false;
            int rc;
            while ((rc = q.step()) == ROW) value.marks.insert({a.integer(q.s, 0), a.integer(q.s, 1)});
            if (rc != DONE) return false;
        }
        std::lock_guard<std::mutex> guard(mutex);
        // Markers are append-only. A concurrent reader must never retract an
        // already committed mark, even when its read began before another commit.
        Snapshot &current = snapshots[path];
        if (current.uid != value.uid) current = Snapshot{};
        current.uid = value.uid;
        current.marks.insert(value.marks.begin(), value.marks.end());
        if (a.trace) a.trace(snapshots[path].marks.empty() ? "snapshot empty" : "snapshot loaded markers");
        return true;
    }
    void observe(const Api &a, const void *provider, void *db, bool messages) {
        ConnectionLock connection(a, db);
        const char *fn = a.filename ? a.filename(db, "main") : nullptr;
        if (!fn || !a.autocommit) return;
        std::string path(fn);
        if (owner(path).empty()) return;
        bool refresh = false;
        auto key = std::make_pair(provider, db);
        {
            std::lock_guard<std::mutex> guard(mutex);
            auto it = pending.find(key);
            if (it != pending.end() && it->second != path) { pending.erase(it); it = pending.end(); }
            refresh = it != pending.end() || (messages && snapshots.find(path) == snapshots.end());
            if (refresh) pending[key] = path;
        }
        if (refresh && load(a, db, path)) {
            std::lock_guard<std::mutex> guard(mutex);
            pending.erase(key);
        }
    }
    void changed(const Api &a, const void *provider, void *db, const std::string &path) {
        if (!load(a, db, path)) {
            std::lock_guard<std::mutex> guard(mutex);
            pending[std::make_pair(provider, db)] = path;
        }
    }
    // 自己的消息交给宿主正常撤回；他人的原文保留并在同一事务里落撤回标记。
    int filter(const Api &a, const void *provider, void *db, const std::string &sql, void *original) {
        if (!a.prepare || !a.finalize || !a.step || !a.integer || !a.filename || !a.autocommit) return PASS;
        ConnectionLock connection(a, db);
        Write w = parse(sql);
        if (!w.valid) return PASS;
        if (a.trace) a.trace("recall write recognized");
        const char *fn = a.filename(db, "main");
        std::string path = fn ? fn : "", uid = owner(path);
        long long me = number(uid);
        if (!me) { if (a.trace) a.trace("database owner unknown"); return PASS; }
        // Never enter the destructive original statement on a transient metadata
        // failure: it would remain busy and prevent SAVEPOINT on a direct retry.
        // A statement already stepped by the host was previously passed through
        // (e.g. a self recall). Bindings cannot change while it is busy; preserve
        // SQLite's normal direct-retry behavior for that statement. Protected
        // statements never enter the original on a metadata/SAVEPOINT error.
        if (original && a.busy && a.busy(original)) return PASS;
        std::vector<Mark> protect;
        if (w.update) {
            if (!original || !a.reset) return 1;
        } else {
            Statement q(a, db, "SELECT id,chat_id,from_id,is_recalled,length(content) FROM messages WHERE id=" + std::to_string(w.id));
            if (!q.s) return q.prepared;
            int rc;
            while ((rc = q.step()) == ROW) {
                long long id = a.integer(q.s, 0), chat = a.integer(q.s, 1), sender = a.integer(q.s, 2);
                if (id > 0 && chat > 0 && sender > 0 && sender != me
                        && a.integer(q.s, 3) == 0 && a.integer(q.s, 4) > 0) protect.push_back({chat, id});
            }
            if (rc != DONE) return rc;
        }
        if (!w.update && protect.empty()) { if (a.trace) a.trace("no eligible original"); return PASS; }
        int result = exec(a, db, "SAVEPOINT feishukit_recall_guard");
        if (result != DONE) {
            if (a.trace) a.trace("recall savepoint failed; original not executed"); return result;
        }
        result = exec(a, db, "CREATE TABLE IF NOT EXISTS feishukit_recalled_v1 ("
                "message_id INTEGER PRIMARY KEY,chat_id INTEGER NOT NULL)");
        for (const auto &m : protect) {
            if (result != DONE) break;
            result = exec(a, db, "INSERT OR IGNORE INTO feishukit_recalled_v1(message_id,chat_id) VALUES("
                    + std::to_string(m.second) + "," + std::to_string(m.first) + ")");
        }
        if (result == DONE && w.update) {
            // Run the original prepared UPDATE, preserving ALL binding bytes and
            // types (including embedded NUL). The connection-local TEMP trigger
            // marks/skips only other people's retained rows, in the same transaction.
            // No deprecated transfer_bindings dependency: WCDB2 omits that export.
            result = exec(a, db, "CREATE TEMP TRIGGER feishukit_recall_update_guard BEFORE UPDATE ON main.messages "
                    "WHEN NEW.is_recalled=1 AND OLD.id>0 AND OLD.chat_id>0 AND OLD.from_id>0 "
                    "AND OLD.from_id<>" + std::to_string(me) + " AND OLD.is_recalled=0 AND length(OLD.content)>0 "
                    "BEGIN INSERT OR IGNORE INTO feishukit_recalled_v1(message_id,chat_id) VALUES(OLD.id,OLD.chat_id); "
                    "SELECT RAISE(IGNORE); END");
            if (result == DONE) {
                result = a.step(original);
                // An error/BUSY can leave the original active. Reset before
                // rollback so a direct retry can open a new savepoint. Bindings survive.
                if (result != DONE) a.reset(original);
                else result = exec(a, db, "DROP TRIGGER temp.feishukit_recall_update_guard");
            }
        }
        if (result != DONE) {
            if (a.trace) a.trace("recall transaction failed; rolling back");
            exec(a, db, "ROLLBACK TO feishukit_recall_guard");
            exec(a, db, "RELEASE feishukit_recall_guard");
            return result;
        }
        result = exec(a, db, "RELEASE feishukit_recall_guard");
        if (result != DONE) {
            exec(a, db, "ROLLBACK TO feishukit_recall_guard");
            exec(a, db, "RELEASE feishukit_recall_guard");
            return result;
        }
        changed(a, provider, db, path);
        if (a.trace) a.trace("recall transaction completed");
        return result;
    }
};
} // namespace recall
