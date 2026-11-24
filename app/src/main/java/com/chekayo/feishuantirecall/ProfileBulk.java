package com.chekayo.feishuantirecall;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * FeishuKit「V3 富资料批量归档」——替代 ProfileCapture 的「点谁存谁」。
 *
 * 数据源: contact.db 的 chatter_profiles_v3.profile(protobuf BLOB), 由 native doProfileDump 就地掏出,
 *         按行落成 JSONL(每行 {chatter_id,tenant_id,name,is_resigned,hex})。本类负责:
 *   1) 解 protobuf: 顶层 field 3 是重复 section, 每段 3.1=段标识(B-DEPARTMENT/B-ENTERPRISE-EMAIL/...)、
 *      3.7=值(JSON字符串); field 1→3→1 = 公司名(外部人也能标)。
 *   2) 化简值 -> 部门/邮箱/工号/职务/上级/手机/群昵称。
 *   3) 按 tenant_id 分类(本地 chatters 里人数最多的 tenant = 本公司, 其余=外部客户)。
 *   4) append-only 并入 profiles.json(保留已有非空字段 -> 人离职后飞书清了 blob, 存档仍在)。
 *
 * 注: 飞书对离职者会清空 blob 明细, 故只能趁在职时抓; 外部人只给姓名/群昵称/公司名, 无部门/邮箱/工号。
 */
public class ProfileBulk {

    // section 标识 -> profiles.json 字段名 (与 ProfileCapture / 离职名单 UI 对齐)
    static final String SEC_DEPT   = "B-DEPARTMENT";
    static final String SEC_EMAIL  = "B-ENTERPRISE-EMAIL";
    static final String SEC_JOBNO  = "B-JOBNUMBER";
    static final String SEC_TITLE  = "B-JOB-TITLE";
    static final String SEC_LEADER = "B-LEADER";
    static final String SEC_PHONE  = "B-PHONE";
    static final String SEC_NICK   = "B-CHATGROUPNICKNAME";

    /** 解析 JSONL, 批量并入 profiles.json。返回本次处理的行数。 */
    public static int merge(File jsonl, File profilesJson) throws Exception {
        return mergeInternal(jsonl, profilesJson, true, "bulk_at");
    }

    /**
     * 解析全量花名册 JSONL(每行同 merge 的格式: {chatter_id,tenant_id,name,is_resigned,hex}),
     * append-only 并入 profiles.json。无 hex 时仅补姓名/租户/离职态，不解 protobuf。
     */
    public static int mergeRoster(File jsonl, File profilesJson) throws Exception {
        return mergeInternal(jsonl, profilesJson, false, "roster_at");
    }

    private static int mergeInternal(File jsonl, File profilesJson, boolean requireBlob, String tsField) throws Exception {
        if (!jsonl.exists()) return 0;

        // pass 1: 统计 tenant 分布，优先按真人(chatter_type=1)投票，忽略空与 "0"，人数最多者 = 本公司
        HashMap<String, Integer> userTcount = new HashMap<String, Integer>();
        HashMap<String, Integer> allTcount = new HashMap<String, Integer>();
        BufferedReader br = reader(jsonl);
        try {
            String line;
            while ((line = br.readLine()) != null) {
                String t = quickField(line, "tenant_id");
                if (t == null || t.isEmpty() || "0".equals(t)) continue;
                Integer c = allTcount.get(t);
                allTcount.put(t, c == null ? 1 : c + 1);
                if ("1".equals(quickField(line, "chatter_type"))) {
                    Integer uc = userTcount.get(t);
                    userTcount.put(t, uc == null ? 1 : uc + 1);
                }
            }
        } finally {
            br.close();
        }
        HashMap<String, Integer> pick = userTcount.isEmpty() ? allTcount : userTcount;
        String home = null; int best = -1;
        for (Map.Entry<String, Integer> e : pick.entrySet()) {
            if (e.getValue() > best) { best = e.getValue(); home = e.getKey(); }
        }

        // pass 2: 解每行, 攒 updates(不碰 profiles.json, 缩短持锁时间)
        List<JSONObject> updates = new ArrayList<JSONObject>();
        br = reader(jsonl);
        try {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                JSONObject row;
                try { row = new JSONObject(line); } catch (Throwable t) { continue; }
                String uid = row.optString("chatter_id", "").trim();
                // 严防异常/拼串 hex 残片混入成为伪 UID
                if (!isValidNumericUid(uid)) continue;

                String hexStr = row.optString("hex", "");
                byte[] blob = null;
                if (!hexStr.isEmpty()) {
                    try {
                        blob = hex(hexStr);
                    } catch (Throwable t) {
                        if (requireBlob) continue;
                        blob = null;
                    }
                }
                if (requireBlob && (blob == null || blob.length == 0)) continue;

                JSONObject u = new JSONObject();
                u.put("id", uid);
                String name = clean(row.optString("name", ""));
                if (isPrintableText(name) && name.length() <= 128) u.put("name", name);
                String tid = row.optString("tenant_id", "").trim();
                boolean validTid = !tid.isEmpty() && !"0".equals(tid);
                u.put("tenant_id", validTid ? tid : "");
                u.put("is_home", validTid && home != null && home.equals(tid));
                u.put("is_resigned", "1".equals(row.optString("is_resigned", "")));
                int chatterType = parseChatterType(row.optString("chatter_type", ""));
                if (chatterType > 0) u.put("chatter_type", chatterType);

                if (blob != null && blob.length > 0) {
                    HashMap<String, String> sec = new HashMap<String, String>();
                    collectSections(blob, sec);
                    String company = companyName(blob);
                    u.put("department",  flat(sec.get(SEC_DEPT)));
                    u.put("email",       flat(sec.get(SEC_EMAIL)));
                    u.put("employee_id", flat(sec.get(SEC_JOBNO)));
                    u.put("position",    flat(sec.get(SEC_TITLE)));
                    u.put("leader",      flat(sec.get(SEC_LEADER)));
                    u.put("phone",       flat(sec.get(SEC_PHONE)));
                    u.put("nickname",    flat(sec.get(SEC_NICK)));
                    if (company != null && !company.isEmpty()) u.put("company", company);
                }
                updates.add(u);
            }
        } finally {
            br.close();
        }

        long now = System.currentTimeMillis();
        synchronized (Config.PROFILES_LOCK) {
            JSONObject all = (profilesJson.exists() && profilesJson.length() > 0)
                    ? new JSONObject(readFile(profilesJson)) : new JSONObject();
            pruneCorruptedEntries(all);
            for (JSONObject u : updates) {
                String uid = u.getString("id");
                JSONObject rec = all.optJSONObject(uid);
                if (rec == null) rec = new JSONObject();
                rec.put("id", uid);
                // 刷新: 名字/租户/分类/真实成员类型/离职态/时间
                if (u.optString("name", "").length() > 0) rec.put("name", u.getString("name"));
                if (u.optString("tenant_id", "").length() > 0) {
                    rec.put("tenant_id", u.getString("tenant_id"));
                    rec.put("is_home", u.optBoolean("is_home"));
                }
                int ct = u.optInt("chatter_type", 0);
                if (ct > 0) rec.put("chatter_type", ct);
                rec.put("is_resigned", u.optBoolean("is_resigned"));
                // append-only: 明细字段只补空或替换脏值, 不覆盖已有有效值(离职清空后旧值得以保留)
                putIfEmpty(rec, "department",  u.optString("department", ""));
                putIfEmpty(rec, "email",       u.optString("email", ""));
                putIfEmpty(rec, "employee_id", u.optString("employee_id", ""));
                putIfEmpty(rec, "position",    u.optString("position", ""));
                putIfEmpty(rec, "leader",      u.optString("leader", ""));
                putIfEmpty(rec, "phone",       u.optString("phone", ""));
                putIfEmpty(rec, "nickname",    u.optString("nickname", ""));
                putIfEmpty(rec, "company",     u.optString("company", ""));
                rec.put(tsField, now);
                all.put(uid, rec);
            }
            writeAtomic(profilesJson, all.toString(1));
            ResignedArchive.update(profilesJson, all);
        }
        // 归档后推副本到模块进程，桌面「同事记录」才能读到
        ArchiveSync.pushProfiles();
        ArchiveSync.pushResigned();
        return updates.size();
    }

    /** 飞书 chatters.id 恒为 1~24 位十进制数字；过滤并发脏写产生的超长 hex 残片。 */
    public static boolean isValidNumericUid(String uid) {
        if (uid == null) return false;
        int len = uid.length();
        if (len == 0 || len > 24) return false;
        for (int i = 0; i < len; i++) {
            char c = uid.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    /** profiles.json 顶层键校验：接受合法数字 UID 及 ProfileCapture 兜底的 name:<短姓名>。 */
    public static boolean isValidStoredKey(String key) {
        if (isValidNumericUid(key)) return true;
        if (key != null && key.startsWith("name:") && key.length() > 5 && key.length() <= 64) {
            return isPrintableText(key.substring(5));
        }
        return false;
    }

    /** 校验可打印文本（拒绝 UTF-8 替换符 \uFFFD 及 ASCII 控制字符）。 */
    static boolean isPrintableText(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\uFFFD' || c < 0x20 || c == 0x7F) return false;
        }
        return true;
    }

    static boolean isValidCompany(String s) {
        return isPrintableText(s) && s.length() <= 128;
    }

    static int parseChatterType(String raw) {
        if (raw == null) return 0;
        String t = raw.trim();
        if ("1".equals(t) || "USER".equalsIgnoreCase(t)) return 1;
        if ("2".equals(t) || "BOT".equalsIgnoreCase(t)) return 2;
        return 0;
    }

    /** 清理历史 profiles.json 中非法/拼串垃圾键及损坏的公司名字段，返回清理条目数。 */
    public static int pruneCorruptedEntries(JSONObject all) {
        if (all == null) return 0;
        List<String> badKeys = new ArrayList<String>();
        int cleaned = 0;
        Iterator<String> it = all.keys();
        while (it.hasNext()) {
            String k = it.next();
            JSONObject rec = all.optJSONObject(k);
            if (!isValidStoredKey(k) || rec == null) {
                badKeys.add(k);
                continue;
            }
            String comp = rec.optString("company", "");
            if (!comp.isEmpty() && !isValidCompany(comp)) {
                rec.remove("company");
                cleaned++;
            }
        }
        for (int i = 0; i < badKeys.size(); i++) {
            all.remove(badKeys.get(i));
            cleaned++;
        }
        return cleaned;
    }

    // ── 只补空(或覆盖历史乱码值), 保留已有有效非空 ──
    static void putIfEmpty(JSONObject o, String k, String v) {
        if (v == null || v.isEmpty() || !isPrintableText(v)) return;
        String cur = o.optString(k, "");
        if (cur != null && !cur.isEmpty() && isPrintableText(cur)) return;
        try { o.put(k, v); } catch (Throwable ignored) {}
    }

    // ── protobuf wire-format(仅需的部分) ─────────────────────────────
    // varint: 返回 {value, newPos}
    static long[] varint(byte[] b, int pos) {
        long r = 0; int shift = 0;
        while (pos < b.length) {
            int x = b[pos++] & 0xff;
            r |= ((long) (x & 0x7f)) << shift;
            if ((x & 0x80) == 0) break;
            shift += 7;
        }
        return new long[]{ r, pos };
    }

    // 顶层遍历: 收集所有 field 3(section)的 3.1=标识 -> 3.7=值JSON
    static void collectSections(byte[] b, HashMap<String, String> out) {
        int i = 0, n = b.length;
        while (i < n) {
            long[] kv = varint(b, i); int key = (int) kv[0]; i = (int) kv[1];
            int fn = key >>> 3, wt = key & 7;
            if (wt == 0) { i = (int) varint(b, i)[1]; }
            else if (wt == 2) {
                long[] l = varint(b, i); i = (int) l[1]; int len = (int) l[0];
                if (len < 0 || i + len > n) break;
                if (fn == 3) {
                    String sid = subStr(b, i, i + len, 1);
                    String val = subStr(b, i, i + len, 7);
                    if (sid != null && val != null && !out.containsKey(sid)) out.put(sid, val);
                }
                i += len;
            }
            else if (wt == 5) i += 4;
            else if (wt == 1) i += 8;
            else break;
        }
    }

    // 公司名: field 1 -> field 3 -> field 1 (string)，校验合法可打印文本防止误解嵌套 protobuf
    static String companyName(byte[] b) {
        int[] f1 = subRange(b, 0, b.length, 1);
        if (f1 == null) return null;
        int[] f13 = subRange(b, f1[0], f1[0] + f1[1], 3);
        if (f13 == null) return null;
        String cn = subStr(b, f13[0], f13[0] + f13[1], 1);
        if (cn == null) return null;
        String cleaned = clean(cn);
        return isValidCompany(cleaned) ? cleaned : null;
    }

    // 在 [start,end) 内找 field 号为 field 的 wt2 字段, 返回其字符串值; 无则 null
    static String subStr(byte[] b, int start, int end, int field) {
        int[] r = subRange(b, start, end, field);
        if (r == null) return null;
        try { return new String(b, r[0], r[1], "UTF-8"); } catch (Throwable t) { return null; }
    }
    // 在 [start,end) 内找 field 号为 field 的 wt2 字段, 返回 {payloadOffset, payloadLen}; 无则 null
    static int[] subRange(byte[] b, int start, int end, int field) {
        int i = start;
        while (i < end) {
            long[] kv = varint(b, i); int key = (int) kv[0]; i = (int) kv[1];
            int fn = key >>> 3, wt = key & 7;
            if (wt == 0) { i = (int) varint(b, i)[1]; }
            else if (wt == 2) {
                long[] l = varint(b, i); i = (int) l[1]; int len = (int) l[0];
                if (len < 0 || i + len > end) return null;
                if (fn == field) return new int[]{ i, len };
                i += len;
            }
            else if (wt == 5) i += 4;
            else if (wt == 1) i += 8;
            else break;
        }
        return null;
    }

    // 去掉双向排版控制符(‎/‏/‪-‮ 等不可见字符), 与 ProfileCapture 一致
    static String clean(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\u200e\\u200f\\u202a-\\u202e\\u2066-\\u2069]", "").trim();
    }

    // ── 值化简: 3.7 的 JSON 值 -> 可读文本 ──
    static String flat(String js) {
        if (js == null || js.isEmpty()) return "";
        String out = "";
        try {
            JSONObject o = new JSONObject(js);
            if (o.has("department_paths")) {         // 部门: 多路径 -> "总部/研发中心/软件组 ; ..."
                JSONArray ps = o.getJSONArray("department_paths");
                StringBuilder all = new StringBuilder();
                for (int i = 0; i < ps.length(); i++) {
                    JSONArray nodes = ps.getJSONObject(i).optJSONArray("department_nodes");
                    StringBuilder one = new StringBuilder();
                    if (nodes != null) for (int j = 0; j < nodes.length(); j++) {
                        String nm = dv(nodes.getJSONObject(j).optJSONObject("department_name"));
                        if (nm != null && !nm.isEmpty()) { if (one.length() > 0) one.append('/'); one.append(nm); }
                    }
                    if (one.length() > 0) { if (all.length() > 0) all.append(" ; "); all.append(one); }
                }
                out = clean(all.toString());
            } else if (o.has("title"))  out = clean(dv(o.optJSONObject("title")));
            else if (o.has("text"))     out = clean(dv(o.optJSONObject("text")));
            else if (o.has("number"))   out = clean(o.optString("number", ""));
            else out = "";
        } catch (Throwable t) {
            out = clean(js);
        }
        return isPrintableText(out) ? out : "";
    }
    static String dv(JSONObject x) { return x == null ? null : x.optString("default_val", null); }

    // ── 工具 ──
    static byte[] hex(String s) {
        if (s == null || (s.length() & 1) != 0) return null;
        int n = s.length() / 2;
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            int hi = Character.digit(s.charAt(i * 2), 16);
            int lo = Character.digit(s.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) return null;
            b[i] = (byte) ((hi << 4) | lo);
        }
        return b;
    }

    // 从一行 JSONL 里廉价抠出 "key":"value" 的 value(避免 pass1 全量解析 hex)
    static String quickField(String line, String key) {
        String pat = "\"" + key + "\":\"";
        int i = line.indexOf(pat);
        if (i < 0) return null;
        i += pat.length();
        int j = line.indexOf('"', i);
        if (j < 0) return null;
        return line.substring(i, j);
    }

    static BufferedReader reader(File f) throws Exception {
        return new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
    }
    static String readFile(File f) throws Exception {
        FileInputStream is = new FileInputStream(f);
        byte[] b = new byte[(int) f.length()];
        int off = 0, r; while (off < b.length && (r = is.read(b, off, b.length - off)) > 0) off += r;
        is.close(); return new String(b, 0, off, "UTF-8");
    }
    // 原子写: 先写 .tmp 再 rename, 防写一半崩溃留下坏 JSON
    static void writeAtomic(File f, String s) throws Exception {
        File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
        FileOutputStream os = new FileOutputStream(tmp);
        os.write(s.getBytes("UTF-8")); os.flush();
        os.getFD().sync(); os.close();
        if (!tmp.renameTo(f)) { // rename 失败兜底直接写
            FileOutputStream o2 = new FileOutputStream(f);
            o2.write(s.getBytes("UTF-8")); o2.flush(); o2.close();
            tmp.delete();
        }
        f.setReadable(true, false);
    }
}
