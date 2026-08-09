package com.chekayo.feishuantirecall;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * fuck lark 「V3 富资料批量归档」——替代 ProfileCapture 的「点谁存谁」。
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
        if (!jsonl.exists()) return 0;

        // pass 1: 统计 tenant 分布, 人数最多者 = 本公司
        HashMap<String, Integer> tcount = new HashMap<String, Integer>();
        BufferedReader br = reader(jsonl);
        String line;
        while ((line = br.readLine()) != null) {
            String t = quickField(line, "tenant_id");
            if (t != null && !t.isEmpty()) { Integer c = tcount.get(t); tcount.put(t, c == null ? 1 : c + 1); }
        }
        br.close();
        String home = null; int best = -1;
        for (Map.Entry<String, Integer> e : tcount.entrySet()) if (e.getValue() > best) { best = e.getValue(); home = e.getKey(); }

        // pass 2: 解每行 blob, 攒成 updates(不碰 profiles.json, 缩短持锁时间)
        List<JSONObject> updates = new ArrayList<JSONObject>();
        br = reader(jsonl);
        while ((line = br.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) continue;
            JSONObject row;
            try { row = new JSONObject(line); } catch (Throwable t) { continue; }
            String uid = row.optString("chatter_id", "");
            if (uid.isEmpty()) continue;
            byte[] blob;
            try { blob = hex(row.optString("hex", "")); } catch (Throwable t) { continue; }
            if (blob == null || blob.length == 0) continue;

            HashMap<String, String> sec = new HashMap<String, String>();
            collectSections(blob, sec);
            String company = companyName(blob);

            JSONObject u = new JSONObject();
            u.put("id", uid);
            u.put("name", row.optString("name", ""));
            String tid = row.optString("tenant_id", "");
            u.put("tenant_id", tid);
            u.put("is_home", home != null && home.equals(tid));
            u.put("is_resigned", "1".equals(row.optString("is_resigned", "")));
            u.put("department",  flat(sec.get(SEC_DEPT)));
            u.put("email",       flat(sec.get(SEC_EMAIL)));
            u.put("employee_id", flat(sec.get(SEC_JOBNO)));
            u.put("position",    flat(sec.get(SEC_TITLE)));
            u.put("leader",      flat(sec.get(SEC_LEADER)));
            u.put("phone",       flat(sec.get(SEC_PHONE)));
            u.put("nickname",    flat(sec.get(SEC_NICK)));
            if (company != null && !company.isEmpty()) u.put("company", company);
            updates.add(u);
        }
        br.close();

        long now = System.currentTimeMillis();
        synchronized (Config.PROFILES_LOCK) {
            JSONObject all = profilesJson.exists() ? new JSONObject(readFile(profilesJson)) : new JSONObject();
            for (JSONObject u : updates) {
                String uid = u.getString("id");
                JSONObject rec = all.optJSONObject(uid);
                if (rec == null) rec = new JSONObject();
                rec.put("id", uid);
                // 刷新: 名字/租户/分类/离职态/时间
                if (u.optString("name", "").length() > 0) rec.put("name", u.getString("name"));
                if (u.optString("tenant_id", "").length() > 0) { rec.put("tenant_id", u.getString("tenant_id")); rec.put("is_home", u.optBoolean("is_home")); }
                rec.put("is_resigned", u.optBoolean("is_resigned"));
                // append-only: 明细字段只补空, 不覆盖已有非空(离职清空后旧值得以保留)
                putIfEmpty(rec, "department",  u.optString("department", ""));
                putIfEmpty(rec, "email",       u.optString("email", ""));
                putIfEmpty(rec, "employee_id", u.optString("employee_id", ""));
                putIfEmpty(rec, "position",    u.optString("position", ""));
                putIfEmpty(rec, "leader",      u.optString("leader", ""));
                putIfEmpty(rec, "phone",       u.optString("phone", ""));
                putIfEmpty(rec, "nickname",    u.optString("nickname", ""));
                putIfEmpty(rec, "company",     u.optString("company", ""));
                rec.put("bulk_at", now);
                all.put(uid, rec);
            }
            writeAtomic(profilesJson, all.toString(1));
        }
        return updates.size();
    }

    // ── 只补空, 保留已有非空 ──
    static void putIfEmpty(JSONObject o, String k, String v) {
        if (v == null || v.isEmpty()) return;
        String cur = o.optString(k, "");
        if (cur != null && !cur.isEmpty()) return;
        try { o.put(k, v); } catch (Throwable ignored) {}
    }

    /**
     * 解析全量花名册 JSONL(每行同 merge 的格式: {chatter_id,tenant_id,name,is_resigned,hex}),
     * append-only 并入 profiles.json。区别于 merge:
     *   - 覆盖全员(不限 is_resigned), 无 hex 的人也落 name/tenant_id/is_resigned -> 全员档案 UI 能显示。
     *   - hex 空 -> 只补 name/分类, 不解 protobuf(那些无富资料的同事至少有姓名)。
     * tenant 分布统计与本类其它方法一致: 人数最多的 tenant = 本公司(is_home)。
     * 返回本次处理的行数(含无 hex 的)。
     */
    public static int mergeRoster(File jsonl, File profilesJson) throws Exception {
        if (!jsonl.exists()) return 0;

        // pass 1: 统计 tenant 分布(用全量, 不像 merge 只看有 blob 的人), 人数最多者 = 本公司
        HashMap<String, Integer> tcount = new HashMap<String, Integer>();
        BufferedReader br = reader(jsonl);
        String line;
        while ((line = br.readLine()) != null) {
            String t = quickField(line, "tenant_id");
            if (t != null && !t.isEmpty()) { Integer c = tcount.get(t); tcount.put(t, c == null ? 1 : c + 1); }
        }
        br.close();
        String home = null; int best = -1;
        for (Map.Entry<String, Integer> e : tcount.entrySet()) if (e.getValue() > best) { best = e.getValue(); home = e.getKey(); }

        // pass 2: 解每行, 攒 updates(不碰 profiles.json, 缩短持锁时间)
        List<JSONObject> updates = new ArrayList<JSONObject>();
        br = reader(jsonl);
        while ((line = br.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) continue;
            JSONObject row;
            try { row = new JSONObject(line); } catch (Throwable t) { continue; }
            String uid = row.optString("chatter_id", "");
            if (uid.isEmpty()) continue;

            JSONObject u = new JSONObject();
            u.put("id", uid);
            u.put("name", row.optString("name", ""));
            String tid = row.optString("tenant_id", "");
            u.put("tenant_id", tid);
            u.put("is_home", home != null && home.equals(tid));
            u.put("is_resigned", "1".equals(row.optString("is_resigned", "")));

            // 有 hex 才解 protobuf 明细; 无 hex 只补姓名/分类(放在 update 里, merge 时按 append-only 补)
            String hex = row.optString("hex", "");
            byte[] blob = null;
            if (!hex.isEmpty()) {
                try { blob = hex(hex); } catch (Throwable t) { blob = null; }
            }
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
        br.close();

        long now = System.currentTimeMillis();
        synchronized (Config.PROFILES_LOCK) {
            JSONObject all = profilesJson.exists() ? new JSONObject(readFile(profilesJson)) : new JSONObject();
            for (JSONObject u : updates) {
                String uid = u.getString("id");
                JSONObject rec = all.optJSONObject(uid);
                if (rec == null) rec = new JSONObject();
                rec.put("id", uid);
                // 刷新: 名字/租户/分类/离职态/时间(姓名对"全员档案"最重要, 每次以最新为准)
                if (u.optString("name", "").length() > 0) rec.put("name", u.getString("name"));
                if (u.optString("tenant_id", "").length() > 0) { rec.put("tenant_id", u.getString("tenant_id")); rec.put("is_home", u.optBoolean("is_home")); }
                rec.put("is_resigned", u.optBoolean("is_resigned"));
                // append-only: 明细字段只补空, 不覆盖已有非空(离职清空后旧值得以保留)
                putIfEmpty(rec, "department",  u.optString("department", ""));
                putIfEmpty(rec, "email",       u.optString("email", ""));
                putIfEmpty(rec, "employee_id", u.optString("employee_id", ""));
                putIfEmpty(rec, "position",    u.optString("position", ""));
                putIfEmpty(rec, "leader",      u.optString("leader", ""));
                putIfEmpty(rec, "phone",       u.optString("phone", ""));
                putIfEmpty(rec, "nickname",    u.optString("nickname", ""));
                putIfEmpty(rec, "company",     u.optString("company", ""));
                rec.put("roster_at", now);
                all.put(uid, rec);
            }
            writeAtomic(profilesJson, all.toString(1));
        }
        return updates.size();
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

    // 公司名: field 1 -> field 3 -> field 1 (string)
    static String companyName(byte[] b) {
        int[] f1 = subRange(b, 0, b.length, 1);
        if (f1 == null) return null;
        int[] f13 = subRange(b, f1[0], f1[0] + f1[1], 3);
        if (f13 == null) return null;
        String cn = subStr(b, f13[0], f13[0] + f13[1], 1);
        return cn == null ? null : clean(cn);
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
                return clean(all.toString());
            }
            if (o.has("title"))  return clean(dv(o.optJSONObject("title")));
            if (o.has("text"))   return clean(dv(o.optJSONObject("text")));
            if (o.has("number")) return clean(o.optString("number", ""));
            // 邮箱/好友链接等: {"title":{...},"link":{...}} 已被上面 title 命中; 兜底原样
            return js;
        } catch (Throwable t) { return js; }
    }
    static String dv(JSONObject x) { return x == null ? null : x.optString("default_val", null); }

    // ── 工具 ──
    static byte[] hex(String s) {
        if (s == null) return null;
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
