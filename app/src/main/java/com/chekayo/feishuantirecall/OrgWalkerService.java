package com.chekayo.feishuantirecall;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * OrgWalkerService —— 飞书组织架构自动巡游(AccessibilityService 版,移植自 PC 端 org_walk.py)。
 *
 * 作用:自动 DFS 走遍【通讯录→组织内联系人】整棵部门树,逐部门进入+滑到底,逼飞书把全公司成员
 * 懒加载进 contact.db;飞书进程内的 native hook(ResignTracker/ProfileBulk)随即快照 -> 全量花名册。
 *
 * 触发:进入组织架构根页(面包屑=公司名、可见"总部"节点)时自动开走一次;走完发 Toast。
 * 依赖飞书控件 id(真机 dump 确认):
 *   avatar_item_title(行标题)/ avatar_item_title_tail(部门的 (N) 尾巴,有=部门无=人)
 *   breadcrumb_label_tv(面包屑)/ recylerview(可滚动列表)
 *
 * 说明:与 native dump 松耦合——本服务只负责"把树走一遍触发懒加载",快照由飞书进程内的
 * ResignTracker 周期 arm 完成(见 Config.resign 开关)。
 */
public class OrgWalkerService extends AccessibilityService {

    static final String PKG = "com.ss.android.lark";
    static final String ID_TITLE = PKG + ":id/avatar_item_title";
    static final String ID_TAIL  = PKG + ":id/avatar_item_title_tail";
    static final String ID_CRUMB = PKG + ":id/breadcrumb_label_tv";
    static final int MAXDEPTH = 6;

    // 停止/重置信号走【显式广播】: 飞书设置面板跑在飞书进程,本服务跑在模块进程(不同 UID);
    // Android 10+ 的 per-app SELinux 分类使得跨 App 读私有目录里的文件(旧的 walker_stop.flag)
    // 被静默拒绝 -> 停止永远读不到。改用广播,飞书进程 sendBroadcast 直达本服务的动态 receiver。
    static final String ACTION_STOP   = "com.chekayo.feishuantirecall.WALK_STOP";
    static final String ACTION_RESUME = "com.chekayo.feishuantirecall.WALK_RESUME";
    static final String ACTION_RESET  = "com.chekayo.feishuantirecall.WALK_RESET";

    volatile boolean walking = false;
    // 暂停态(粘滞): ACTION_STOP 置位后一直为 true,不再自动巡游,直到用户点『继续巡游』(ACTION_RESUME)
    // 或『从头开始』(ACTION_RESET)。断点(visited)在暂停期间保留,继续时从断点接着走。
    volatile boolean stopReq = false;
    long lastWalk = 0;
    Handler bg;
    final HashSet<String> visited = new HashSet<String>();
    BroadcastReceiver ctrlRx;

    @Override protected void onServiceConnected() {
        android.util.Log.i("org.walker", "=== onServiceConnected (pid=" + android.os.Process.myPid() + ") ===");
        HandlerThread ht = new HandlerThread("org-walker");
        ht.start();
        bg = new Handler(ht.getLooper());
        registerCtrlReceiver();
        loadVisited();      // 服务(重)启时恢复上次断点(存在模块自己的目录),之后 visited 常驻内存
        toast("组织巡游服务已连接");
    }

    /** 注册停止/重置控制广播接收器(飞书设置面板 -> 本服务,跨进程)。 */
    void registerCtrlReceiver() {
        if (ctrlRx != null) return;
        ctrlRx = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                String a = i == null ? null : i.getAction();
                if (ACTION_STOP.equals(a)) {
                    stopReq = true;   // 粘滞暂停: 停当前巡游 + 之后不再自动走
                    android.util.Log.i("org.walker", "收到停止广播 -> 暂停(已完成 " + visited.size() + ")");
                    toast(walking ? "已请求停止,走完当前部门即暂停…" : "巡游已暂停(已完成 " + visited.size() + " 个部门)");
                } else if (ACTION_RESUME.equals(a)) {
                    stopReq = false; lastWalk = 0;   // 解除暂停 + 清冷却,回组织架构页立即从断点续跑
                    android.util.Log.i("org.walker", "收到继续广播 -> 解除暂停(断点 " + visited.size() + ")");
                    toast(visited.isEmpty()
                            ? "已恢复,进【通讯录→组织内联系人】即开始巡游"
                            : "已恢复,进【通讯录→组织内联系人】即从断点续跑(已完成 " + visited.size() + " 个部门)");
                } else if (ACTION_RESET.equals(a)) {
                    stopReq = false; lastWalk = 0; visited.clear();
                    try { new java.io.File(visitedFile()).delete(); } catch (Throwable ignored) {}
                    android.util.Log.i("org.walker", "收到重置广播 -> 清断点,可重走");
                    toast("已清除断点,进【通讯录→组织内联系人】从头开始");
                }
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(ACTION_STOP);
        f.addAction(ACTION_RESUME);
        f.addAction(ACTION_RESET);
        // Android 13(targetSdk 34)动态 receiver 必须显式声明 EXPORTED 才能收到其它 App 的广播
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(ctrlRx, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(ctrlRx, f);
    }

    @Override public boolean onUnbind(Intent intent) {
        try { if (ctrlRx != null) { unregisterReceiver(ctrlRx); ctrlRx = null; } } catch (Throwable ignored) {}
        return super.onUnbind(intent);
    }

    /**
     * 巡游开关。本服务跑在模块进程,读不到飞书进程的 Config.cfgFile;但服务能被拉起
     * 本身就说明用户在系统无障碍里开了它 —— 故这里恒返回 true(开关=系统无障碍开关)。
     * 配合飞书设置面板的「组织巡游」入口跳转到无障碍设置,用户在那里控制开关。
     */
    boolean walkerEnabled() { return true; }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        // 只处理飞书事件(配置里 packageNames 已限,这里再保险)
        String pkg0 = String.valueOf(e.getPackageName());
        if (pkg0 == null || (pkg0.indexOf("lark") < 0 && pkg0.indexOf("larksuite") < 0)) return;
        if (!walkerEnabled()) return;
        if (walking) return;
        // 暂停态(点过停止): 一直不自动巡游,直到用户在设置里点『继续巡游』(ACTION_RESUME)。
        // 这样才不会像以前那样"停了又在通讯录页自动重启";续跑改由显式按钮触发,清晰可控。
        if (stopReq) return;
        if (System.currentTimeMillis() - lastWalk < 60_000) return;   // 冷却 60s

        // getRootInActiveWindow() 在飞书里常返回消息列表窗口(常驻),而非当前部门树页。
        // 改为遍历所有窗口,找含部门树控件(breadcrumb_label_tv / recylerview)的那个。
        // 页面异步加载:WINDOW_STATE_CHANGED 瞬间只渲染前几行 -> 延迟 1.5s 再扫,并重试。
        boolean hasDept = false;
        AccessibilityNodeInfo root = null;
        List<Row> rows = null;
        for (int attempt = 0; attempt < 4 && !hasDept; attempt++) {
            if (attempt > 0) sleep(1500);   // 第一次立即扫,后续每 1.5s 重试(等渲染)
            root = findDeptTreeRoot();
            if (root == null) { android.util.Log.w("org.walker", "没找到部门树窗口(尝试" + attempt + ")"); continue; }
            rows = scanRows(root);
            android.util.Log.i("org.walker", "部门树窗口 rows=" + rows.size() + "(尝试" + attempt + ")");
            for (Row r : rows) if (r.isDept) { hasDept = true; break; }
        }
        if (root == null) return;
        if (rows.isEmpty()) dumpIdsOnce(root);   // 控件 id 不匹配时,把当前页所有 id 抓一次
        if (!hasDept) {
            android.util.Log.w("org.walker", "没扫到部门行(rows=" + rows.size() + ",可能全是人或 tail 未匹配)");
            // 兜底:只要有任意 title 行就当部门树页,开走(scanDepts 内部会滑到底重扫)
            if (rows.isEmpty()) return;
        }
        // 开走
        walking = true; lastWalk = System.currentTimeMillis();
        stopReq = false;    // 新一轮开走: 复位停止标志
        // 断点续跑: visited 常驻内存(服务活着就在),这里【不清空】——停止后重进组织页即接着走。
        // 服务被系统杀/重启后由 onServiceConnected 的 loadVisited() 从模块自己的目录恢复。
        android.util.Log.i("org.walker", ">>> 开始 DFS 巡游(部门数≈" + rows.size() + " 已完成=" + visited.size() + ")");
        bg.post(new Runnable() { @Override public void run() {
            try {
                toast(visited.isEmpty() ? "开始自动归档组织通讯录…" : "续上次巡游,已完成 " + visited.size() + " 个部门…");
                walk(new ArrayList<String>(), 0);
                boolean stopped = stopRequested();
                if (stopped) {
                    saveVisited();   // 暂停: 断点存盘(也常驻内存),点『继续巡游』后从此处接着走
                    toast("巡游已暂停,已完成 " + visited.size() + " 个部门。去设置点『继续巡游』续跑");
                } else {
                    // 正常完成: 全树走完,断点无用了,清掉(下次进组织页若有新部门可重走)
                    visited.clear();
                    try { new java.io.File(visitedFile()).delete(); } catch (Throwable ignored) {}
                    toast("组织通讯录归档完成");
                }
            }
            catch (Throwable t) { toast("巡游异常:" + t); android.util.Log.e("org.walker", "巡游异常", t); }
            finally { walking = false; android.util.Log.i("org.walker", "<<< 巡游结束"); }
        }});
    }

    @Override public void onInterrupt() {}

    /**
     * 音量键停止: 巡游进行中,按【音量+ 或 音量-】即暂停(比开设置面板方便——巡游时 UI 被服务
     * 接管,手动操作别扭)。仅在 walking 期间拦截音量键(DOWN+UP 全消费,避免半消费导致按键卡住);
     * 非巡游时一律放行,音量正常调节。停止后到设置里点『继续巡游』续跑。
     * 需 accessibility_service_config 里 canRequestFilterKeyEvents=true + flagRequestFilterKeyEvents。
     */
    @Override public boolean onKeyEvent(KeyEvent event) {
        if (event == null) return false;
        int kc = event.getKeyCode();
        if (kc != KeyEvent.KEYCODE_VOLUME_UP && kc != KeyEvent.KEYCODE_VOLUME_DOWN) return false;
        if (!walking) return false;   // 没在巡游: 放行, 音量键正常工作
        if (event.getAction() == KeyEvent.ACTION_DOWN && !stopReq) {
            stopReq = true;
            android.util.Log.i("org.walker", "音量键 -> 暂停巡游(已完成 " + visited.size() + ")");
            toast("已用音量键停止,走完当前部门即暂停。去设置点『继续巡游』续跑");
        }
        return true;   // 巡游期间音量键全部消费(当作"停止"手势)
    }

    /**
     * 找部门树页的根节点。飞书消息列表是常驻窗口,getRootInActiveWindow() 常返回它
     * (而非当前看到的部门树页)。这里遍历所有窗口,找含部门树特征控件(breadcrumb_label_tv
     * 或 avatar_item_title)的那个窗口的 root。
     * 必须在主线程调用(getWindows 要求);onAccessibilityEvent 本就在主线程。
     */
    AccessibilityNodeInfo findDeptTreeRoot() {
        // 优先:活动窗口本身(大多数情况它就是部门页)
        AccessibilityNodeInfo act = getRootInActiveWindow();
        if (act != null && hasDeptTreeMarker(act)) return act;
        // 兜底:遍历所有窗口(飞书常驻消息列表窗口会抢 active)
        java.util.List<android.view.accessibility.AccessibilityWindowInfo> wins = getWindows();
        if (wins != null) {
            for (android.view.accessibility.AccessibilityWindowInfo w : wins) {
                if (w == null) continue;
                AccessibilityNodeInfo r = w.getRoot();
                if (r != null && hasDeptTreeMarker(r)) return r;
            }
        }
        return null;
    }

    /** 该 root 下是否含部门树特征控件(breadcrumb_label_tv 或 avatar_item_title)。 */
    boolean hasDeptTreeMarker(AccessibilityNodeInfo root) {
        // findAccessibilityNodeInfosByViewId 会递归整棵子树,命中即返回,够快
        if (!root.findAccessibilityNodeInfosByViewId(ID_CRUMB).isEmpty()) return true;
        if (!root.findAccessibilityNodeInfosByViewId(ID_TITLE).isEmpty()) return true;
        return false;
    }

    /** 判断当前活动窗口是否为飞书的组织架构页。
     *  不依赖单条事件 className(异步加载时事件 cls 常是 FrameLayout):
     *  ① 活动窗口包名是飞书; ② 页面里出现"组织内联系人/组织架构"特征文本 或 部门行。 */
    boolean isDeptPage(AccessibilityNodeInfo root) {
        if (root == null) return false;
        String wp = String.valueOf(root.getPackageName());
        if (wp == null || (wp.indexOf("lark") < 0 && wp.indexOf("larksuite") < 0)) return false;
        // 扫一遍可见文本,命中组织架构页特征即认(飞书该页标题/面包屑恒含这些词)
        java.util.List<AccessibilityNodeInfo> q = new ArrayList<AccessibilityNodeInfo>(); q.add(root);
        for (int i = 0; i < q.size() && i < 3000; i++) {
            AccessibilityNodeInfo n = q.get(i);
            if (n == null) continue;
            CharSequence tx = n.getText();
            if (tx != null && tx.length() > 0) {
                String s = tx.toString();
                // 标题/面包屑特征:"组织架构""组织内联系人""总部"(根部门);避免误命中聊天名
                if (s.indexOf("组织架构") >= 0 || s.indexOf("组织内联系人") >= 0
                        || s.indexOf("组织结构") >= 0) {
                    android.util.Log.i("org.walker", "isDeptPage=true (文本命中: " + s + ")");
                    return true;
                }
            }
            for (int c = 0; c < n.getChildCount(); c++) q.add(n.getChild(c));
        }
        return false;
    }

    // ── DFS(移植 org_walk.py 的 scan_depts/tap_dept/walk)──
    // 中途停止: 读内存态 stopReq(由飞书设置面板的 ACTION_STOP 广播置位)。见 registerCtrlReceiver。
    /** 用户是否请求了停止巡游(飞书设置面板发 ACTION_STOP 广播)。 */
    boolean stopRequested() { return stopReq; }

    // ── 断点续跑: visited(已走完的部门完整路径)持久化 ──
    // 存到【模块自己的 files 目录】(getFilesDir): 巡游服务=模块进程,能读写自己的目录;
    // 旧版存飞书私有目录被 Android 10+ SELinux 拒读写 -> 断点永远为空(已修)。
    // 清空断点改由飞书设置面板发 ACTION_RESET 广播,服务自己删(见 registerCtrlReceiver)。
    String visitedFile() {
        return new java.io.File(getFilesDir(), "walker_visited.json").getAbsolutePath();
    }
    /** 开走前加载上次已完成的部门(中途停止后下次接着走)。 */
    void loadVisited() {
        visited.clear();
        try {
            java.io.File f = new java.io.File(visitedFile());
            if (!f.exists()) return;
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(
                    new java.io.FileInputStream(f), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) { line = line.trim(); if (!line.isEmpty()) visited.add(line); }
            br.close();
        } catch (Throwable ignored) {}
    }
    /** 巡游结束(完成/停止)时保存已走完的部门。 */
    void saveVisited() {
        try {
            java.io.File f = new java.io.File(visitedFile());
            java.io.FileOutputStream os = new java.io.FileOutputStream(f);
            StringBuilder sb = new StringBuilder();
            for (String k : visited) sb.append(k).append('\n');
            os.write(sb.toString().getBytes("UTF-8")); os.flush(); os.close();
            f.setReadable(true, false);
        } catch (Throwable ignored) {}
    }

    void walk(List<String> path, int depth) {
        if (depth >= MAXDEPTH) return;
        if (stopRequested()) { android.util.Log.i("org.walker", "用户请求停止,中止 DFS at " + join(path)); return; }
        List<String> depts = scanDepts();                    // 滑到底,收集本页子部门(同时触发懒加载)
        android.util.Log.i("org.walker", "walk depth=" + depth + " path=" + join(path) + " 子部门=" + depts);
        for (String name : depts) {
            if (stopRequested()) { android.util.Log.i("org.walker", "用户请求停止,退出循环 at " + name); break; }
            String key = join(path) + "/" + name;
            if (visited.contains(key)) continue;
            if (tapDept(name)) {
                List<String> np = new ArrayList<String>(path); np.add(name);
                walk(np, depth + 1);
                // 关键: 子树【完整走完】(没被打断)才记该部门为 done。若在子树里被停止,
                // 此处必须【不记 key】就退出——否则父部门被误标已完成,续跑会整枝跳过(接不上)。
                if (stopRequested()) { android.util.Log.i("org.walker", "停止中: 子树未走完,不记 " + key + ",直接退出"); return; }
                visited.add(key);
                performGlobalAction(GLOBAL_ACTION_BACK);
                sleep(1200);
            } else {
                android.util.Log.w("org.walker", "未能进入部门: " + name);
            }
        }
    }

    /** 滑到底收集本页所有子部门名(顺序去重)。 */
    List<String> scanDepts() {
        List<String> names = new ArrayList<String>();
        scrollTop();
        String lastSig = "";
        for (int guard = 0; guard < 40; guard++) {
            AccessibilityNodeInfo root = deptRoot();
            if (root == null) break;
            for (Row r : scanRows(root)) if (r.isDept && !names.contains(r.title)) names.add(r.title);
            String sig = pageSig(root);
            if (!scrollForward()) break;
            sleep(700);
            AccessibilityNodeInfo r2 = deptRoot();
            String sig2 = r2 == null ? "" : pageSig(r2);
            if (sig2.equals(lastSig) || sig2.equals(sig)) break;   // 到底
            lastSig = sig2;
        }
        return names;
    }

    /** 从顶部找到该部门并点入。 */
    boolean tapDept(String name) {
        scrollTop();
        for (int guard = 0; guard < 40; guard++) {
            AccessibilityNodeInfo root = deptRoot();
            if (root == null) return false;
            for (Row r : scanRows(root)) {
                if (r.isDept && r.title.equals(name)) {
                    AccessibilityNodeInfo click = clickable(r.node);
                    if (click != null && click.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { sleep(1500); return true; }
                }
            }
            if (!scrollForward()) return false;
            sleep(600);
        }
        return false;
    }

    // ── 控件读取 ──
    static class Row { String title; boolean isDept; AccessibilityNodeInfo node; }

    volatile boolean dumpedIds = false;   // dumpIdsOnce 只跑一次(诊断用,避免刷屏)

    /** 诊断:把当前页所有带 resource-id 或 text 的节点打出来,定位真实控件 id(只跑一次)。 */
    void dumpIdsOnce(AccessibilityNodeInfo root) {
        if (dumpedIds) return; dumpedIds = true;
        StringBuilder sb = new StringBuilder("=== page node ids ===\n");
        java.util.List<AccessibilityNodeInfo> q = new ArrayList<AccessibilityNodeInfo>(); q.add(root);
        int shown = 0;
        for (int i = 0; i < q.size() && shown < 120; i++) {
            AccessibilityNodeInfo n = q.get(i);
            if (n == null) continue;
            String id = n.getViewIdResourceName();
            CharSequence tx = n.getText();
            if ((id != null && id.length() > 0) || (tx != null && tx.length() > 0)) {
                // 只记 resource-id 的最后一段 + text 片段
                String shortId = id == null ? "-" : id.substring(id.lastIndexOf('/') + 1);
                String t = tx == null ? "" : tx.toString();
                if (t.length() > 20) t = t.substring(0, 20);
                sb.append("  ").append(shortId).append("  «").append(t).append("»\n");
                shown++;
            }
            for (int c = 0; c < n.getChildCount(); c++) q.add(n.getChild(c));
        }
        sb.append("(total shown=").append(shown).append(")");
        android.util.Log.i("org.walker", sb.toString());
    }

    /** 解析当前页所有行:标题 + 是否部门(同 y 存在 tail)。 */
    List<Row> scanRows(AccessibilityNodeInfo root) {
        List<Row> out = new ArrayList<Row>();
        List<AccessibilityNodeInfo> titles = root.findAccessibilityNodeInfosByViewId(ID_TITLE);
        List<AccessibilityNodeInfo> tails  = root.findAccessibilityNodeInfosByViewId(ID_TAIL);
        int[] ty = new int[tails.size()];
        for (int i = 0; i < tails.size(); i++) { android.graphics.Rect rc = new android.graphics.Rect(); tails.get(i).getBoundsInScreen(rc); ty[i] = rc.centerY(); }
        for (AccessibilityNodeInfo t : titles) {
            CharSequence tx = t.getText();
            if (tx == null || tx.length() == 0) continue;
            android.graphics.Rect rc = new android.graphics.Rect(); t.getBoundsInScreen(rc); int cy = rc.centerY();
            boolean dept = false;
            for (int y : ty) if (Math.abs(cy - y) < 60) { dept = true; break; }
            Row r = new Row(); r.title = tx.toString(); r.isDept = dept; r.node = t; out.add(r);
        }
        return out;
    }

    AccessibilityNodeInfo clickable(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo cur = n;
        for (int i = 0; i < 6 && cur != null; i++) {
            if (cur.isClickable()) return cur;
            cur = cur.getParent();
        }
        return n;
    }

    AccessibilityNodeInfo scrollable(AccessibilityNodeInfo root) {
        // 广度找第一个可滚动节点
        List<AccessibilityNodeInfo> q = new ArrayList<AccessibilityNodeInfo>(); q.add(root);
        for (int i = 0; i < q.size() && i < 4000; i++) {
            AccessibilityNodeInfo n = q.get(i);
            if (n == null) continue;
            if (n.isScrollable()) return n;
            for (int c = 0; c < n.getChildCount(); c++) q.add(n.getChild(c));
        }
        return null;
    }

    boolean scrollForward() {
        AccessibilityNodeInfo root = deptRoot(); if (root == null) return false;
        AccessibilityNodeInfo s = scrollable(root); if (s == null) return false;
        return s.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
    }

    void scrollTop() {
        for (int i = 0; i < 8; i++) {
            AccessibilityNodeInfo root = deptRoot(); if (root == null) break;
            AccessibilityNodeInfo s = scrollable(root); if (s == null) break;
            if (!s.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) break;
            sleep(180);
        }
        sleep(400);
    }

    /**
     * 工作线程版部门树 root 获取。
     * 注意:不能用 CountDownLatch 阻塞切主线程 —— 巡游中 scanDepts/tapDept/scroll 高频调用,
     * 每次 await 会累积成 ANR -> 系统杀进程(实测 Crashed services)。故只用 getRootInActiveWindow()
     * (binder 调用,工作线程安全)。巡游进行时部门页是前台,active window 大概率正确;
     * 若不含部门树标记,返回 null 让上层重试(不阻塞)。
     */
    AccessibilityNodeInfo deptRoot() {
        AccessibilityNodeInfo act = getRootInActiveWindow();
        if (act != null && hasDeptTreeMarker(act)) return act;
        return null;   // active 不对就返回 null,上层循环重试(不阻塞主线程,避免 ANR)
    }

    /** 页面指纹:所有标题拼接,用于判断"滑到底了没变化"。 */
    String pageSig(AccessibilityNodeInfo root) {
        StringBuilder sb = new StringBuilder();
        for (Row r : scanRows(root)) sb.append(r.title).append('|');
        return sb.toString();
    }

    static String join(List<String> p) { StringBuilder sb = new StringBuilder(); for (String s : p) sb.append('/').append(s); return sb.toString(); }
    void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) {} }
    /**
     * 巡游进度提示。Android 12+ 后台/无障碍服务进程的 Toast 被系统静默吞掉(ColorOS 严格执行),
     * 故改用 Notification —— 屏幕顶部横幅 100% 可见。用同一 notify id 持续更新,最后一条停留几秒。
     */
    static final int NOTIF_ID = 0x7717;
    void toast(final String s) {
        android.util.Log.i("org.walker", "提示: " + s);
        new Handler(getMainLooper()).post(new Runnable() { @Override public void run() {
            try { showNotif(s); } catch (Throwable e) {
                android.util.Log.w("org.walker", "通知失败, 回退 Toast: " + e);
                try { Toast.makeText(OrgWalkerService.this, "[组织巡游] " + s, Toast.LENGTH_SHORT).show(); }
                catch (Throwable ignored) {}
            }
        }});
    }

    void showNotif(String msg) {
        String channel = "org_walker";
        android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        // Android 8+ 必须建通道(targetSdk 34)
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            android.app.NotificationChannel ch = nm.getNotificationChannel(channel);
            if (ch == null) {
                ch = new android.app.NotificationChannel(channel, "组织巡游提示",
                        android.app.NotificationManager.IMPORTANCE_HIGH);  // HIGH = 有横幅+响声
                ch.setDescription("自动巡游的开始/完成/停止提示");
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        }
        android.app.Notification.Builder b;
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            b = new android.app.Notification.Builder(this, channel);
        } else {
            b = new android.app.Notification.Builder(this);
        }
        b.setSmallIcon(android.R.drawable.ic_menu_search)   // 系统自带图标(模块无自定义 icon 资源)
         .setContentTitle("🧭 组织巡游")
         .setContentText(msg)
         .setStyle(new android.app.Notification.BigTextStyle().bigText(msg))
         .setWhen(System.currentTimeMillis())
         .setAutoCancel(true)
         .setPriority(android.app.Notification.PRIORITY_HIGH);
        nm.notify(NOTIF_ID, b.build());
    }
}
