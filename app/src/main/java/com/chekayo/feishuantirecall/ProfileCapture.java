package com.chekayo.feishuantirecall;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.json.JSONObject;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * fuck lark 「开资料页即归档」——V3 资料页是 section 分块渲染, 数据不走 entity.Profile,
 * 所以直接抓页面渲染出的 UI 文本(部门/企业邮箱/直属上级/职务/工号/手机号), 按 label→value 配对,
 * 连同 uid(从 Activity intent 取)存进 profiles.json。你打开过谁的资料就存谁, 离职后归档仍在。
 */
public class ProfileCapture implements IXposedHookLoadPackage {

    static final String PKG_FEISHU = "com.ss.android.lark";
    static final String PKG_LARK = "com.larksuite.suite";   // 国际版应用包名(内部类名仍沿用 com.ss.android.lark.* 前缀)
    static volatile String PKG = PKG_FEISHU;   // 运行时锁定当前目标
    static boolean isLarkFamily(String pkg) { return PKG_FEISHU.equals(pkg) || PKG_LARK.equals(pkg); }
    // UserProfileActivityV3 国际版 dex 实测同样保留, 类名不用改
    static final String ACT = "com.ss.android.lark.profile.func.v3.userprofile.UserProfileActivityV3";
    static volatile File OUT;   // handleLoadPackage 按当前目标包设(/data/data/<应用包名>/files/resign_tracker/)

    // label 文本 -> 输出字段
    static String labelKey(String t) {
        if (t == null) return null;
        t = t.trim();
        if (t.equals("部门")) return "department";
        if (t.equals("企业邮箱") || t.equals("邮箱")) return "email";
        if (t.equals("直属上级") || t.equals("上级")) return "leader";
        if (t.equals("职务")) return "position";
        if (t.equals("工号") || t.equals("员工编号") || t.equals("员工 ID") || t.equals("员工ID")) return "employee_id";
        if (t.equals("手机号") || t.equals("手机")) return "phone";
        if (t.equals("城市") || t.equals("所在城市")) return "city";
        return null;
    }
    static final Set<String> CHROME = new HashSet<String>(Arrays.asList(
            "消息", "语音", "视频", "编辑内容", "显示", "备注与描述", "添加描述", "更多", "复制"));

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        if (!isLarkFamily(lpparam.packageName) && !AntiRecall.isLarkApp(lpparam.classLoader)) return;
        PKG = lpparam.packageName;
        OUT = new File("/data/data/" + PKG + "/files/resign_tracker/profiles.json");
        try {
            XposedHelpers.findAndHookMethod(ACT, lpparam.classLoader, "onCreate", Bundle.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    final Activity act = (Activity) param.thisObject;
                    // section 数据异步加载, 排几次延时抓取(幂等, 只在抓到字段时存)
                    Handler h = new Handler(Looper.getMainLooper());
                    for (long d : new long[]{1500, 3000, 5000, 8000}) {
                        h.postDelayed(new Runnable() {
                            @Override public void run() { try { scrape(act); } catch (Throwable t) { XposedBridge.log("[fucklark] scrape err " + t); } }
                        }, d);
                    }
                }
            });
            XposedBridge.log("[fucklark] ProfileCapture hooked UserProfileActivityV3.onCreate");
        } catch (Throwable t) {
            XposedBridge.log("[fucklark] ProfileCapture hook 失败 " + t);
        }
    }

    static void scrape(Activity act) {
        if (!Config.resign) return;
        Window w = act.getWindow();
        if (w == null) return;
        List<String> texts = new ArrayList<String>();
        collect(w.getDecorView(), texts);
        if (texts.isEmpty()) return;

        JSONObject rec = new JSONObject();
        // label→value 配对: 遇到 label, 收集其后到下一个 label 之间的非 chrome 文本
        for (int i = 0; i < texts.size(); i++) {
            String key = labelKey(texts.get(i));
            if (key == null) continue;
            StringBuilder val = new StringBuilder();
            for (int j = i + 1; j < texts.size(); j++) {
                String t = texts.get(j);
                if (labelKey(t) != null) break;
                if (CHROME.contains(t.trim())) continue;
                if (t.trim().isEmpty()) continue;
                if (val.length() > 0) val.append(" / ");
                val.append(t.trim());
                if (!key.equals("department")) break;  // 只有部门允许多值
            }
            if (val.length() > 0) safePut(rec, key, val.toString());
        }
        if (rec.length() == 0) return;   // 没抓到字段, 可能还没渲染

        // uid + name 从 Activity intent 取
        String uid = null, name = null;
        try {
            Intent it = act.getIntent();
            Bundle ex = it != null ? it.getExtras() : null;
            if (ex != null) {
                for (String k : ex.keySet()) {
                    Object v = ex.get(k);
                    String s = v == null ? "" : String.valueOf(v);
                    if (uid == null && s.matches("\\d{15,20}")) uid = s;
                    if (name == null && (k.toLowerCase().contains("name")) && s.length() > 0 && s.length() < 40) name = s;
                }
            }
        } catch (Throwable ignored) {}
        // name 兜底: 页面上第一个非 chrome/非 label 的短文本(通常是姓名)
        if (name == null) {
            for (String t : texts) {
                String tt = t.trim();
                if (tt.isEmpty() || CHROME.contains(tt) || labelKey(tt) != null) continue;
                if (tt.length() <= 20 && !tt.contains("@") && !tt.startsWith("+")) { name = tt; break; }
            }
        }
        String keyId = uid != null ? uid : ("name:" + name);
        if (keyId == null) return;
        safePut(rec, "id", uid);
        safePut(rec, "name", name);

        try {
            int total;
            synchronized (Config.PROFILES_LOCK) {   // 与 ProfileBulk 批量写互斥
                JSONObject all = OUT.exists() ? new JSONObject(read(OUT)) : new JSONObject();
                JSONObject prev = all.optJSONObject(keyId);
                if (prev != null) {                     // 合并, 保留旧非空字段
                    java.util.Iterator<String> it = rec.keys();
                    while (it.hasNext()) { String k = it.next(); prev.put(k, rec.get(k)); }
                    rec = prev;
                }
                rec.put("captured_at", System.currentTimeMillis());
                all.put(keyId, rec);
                write(OUT, all.toString(1));
                total = all.length();
            }
            XposedBridge.log("[fucklark] 归档资料 " + name + " uid=" + uid
                    + " 部门=" + rec.optString("department") + " 邮箱=" + rec.optString("email")
                    + " 职务=" + rec.optString("position") + " 上级=" + rec.optString("leader")
                    + " 累计=" + total);
        } catch (Throwable t) {
            XposedBridge.log("[fucklark] 归档失败 " + t);
        }
    }

    static void collect(View v, List<String> out) {
        if (v == null) return;
        if (v instanceof TextView) {
            CharSequence cs = ((TextView) v).getText();
            String s = cs == null ? "" : cs.toString();
            // 去掉双向排版控制符(‎/‏/‪-‮)等不可见字符
            s = s.replaceAll("[\\u200e\\u200f\\u202a-\\u202e\\u2066-\\u2069]", "").trim();
            if (!s.isEmpty()) out.add(s);
        }
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) collect(vg.getChildAt(i), out);
        }
    }

    static void safePut(JSONObject o, String k, String val) { try { if (val != null && !val.isEmpty()) o.put(k, val); } catch (Throwable ignored) {} }

    static String read(File f) throws Exception {
        FileInputStream is = new FileInputStream(f);
        byte[] b = new byte[(int) f.length()];
        int off = 0, r; while (off < b.length && (r = is.read(b, off, b.length - off)) > 0) off += r;
        is.close(); return new String(b, 0, off, "UTF-8");
    }
    static void write(File f, String s) throws Exception {
        f.getParentFile().mkdirs();
        FileOutputStream os = new FileOutputStream(f);
        os.write(s.getBytes("UTF-8")); os.flush(); os.close();
        f.setReadable(true, false);
    }
}
