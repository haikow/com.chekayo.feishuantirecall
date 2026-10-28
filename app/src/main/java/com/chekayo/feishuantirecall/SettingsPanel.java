package com.chekayo.feishuantirecall;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 统一设置面板：四页签（核心 / 记录 / 工具 / 关于），覆盖旧版全部功能。
 * 在飞书进程内经 FuckLarkSettings 弹出；在模块桌面入口经 LauncherActivity 全屏展示。
 * 视觉规范见 {@link Ui}。
 */
final class SettingsPanel {
    private SettingsPanel() { }

    /** 在飞书进程内以 AlertDialog 弹出完整面板（打开前刷新当前账号，保证档案/消息路径正确）。 */
    static void show(final Context ctx) {
        try { Config.setFilesDir(ctx.getFilesDir()); } catch (Throwable ignored) { }
        try { AccountPaths.bind(ctx, null); } catch (Throwable ignored) { }
        Config.loadAndAnnounce();
        final boolean tampered = readTampered();
        final LinearLayout root = buildRoot(ctx, /*standalone=*/false, tampered);
        root.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        AlertDialog dialog = new AlertDialog.Builder(ctx)
                .setTitle("模块设置")
                .setView(root)
                .setPositiveButton("完成", null)
                .show();
        Ui.styleDialog(dialog);
        // 首次打开：仅静默查更新；赞赏改为工具页手动入口，不自动弹窗
        if (!DataViews.updateCheckedThisSession) {
            DataViews.updateCheckedThisSession = true;
            DataViews.checkUpdate(ctx, true);
        }
    }

    /** 模块桌面入口：作为 Activity 内容的完整页面（非对话框）。 */
    static View buildStandalonePage(Context ctx) {
        try { Config.setFilesDir(ctx.getFilesDir()); } catch (Throwable ignored) { }
        // 桌面进程探测不到飞书账号时保持 unknown，读档案会回落旧路径/模块副本
        try { if (AccountPaths.currentUid == null || AccountPaths.currentUid.isEmpty()) AccountPaths.bind(ctx, null); } catch (Throwable ignored) { }
        Config.loadAndAnnounce();
        return buildRoot(ctx, /*standalone=*/true, /*tampered=*/false);
    }

    // ── 根布局：内容区（weight=1）+ 底部页签 ─────────────────────────
    private static LinearLayout buildRoot(final Context ctx, final boolean standalone, final boolean tampered) {
        final LinearLayout root = Ui.page(ctx);
        root.setPadding(0, 0, 0, 0);

        // 桌面入口：浅色品牌头替代系统黑 ActionBar；飞书进程用 AlertDialog 标题即可
        if (standalone) root.addView(buildHeader(ctx, true));

        final LinearLayout page = new LinearLayout(ctx);
        page.setOrientation(LinearLayout.VERTICAL);
        root.addView(page, new LinearLayout.LayoutParams(-1, 0, 1f));

        final String[] names = { "核心", "记录", "工具", "关于" };
        final TextView[] tabs = new TextView[4];
        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackground(Ui.glass(ctx, 24, true));
        bar.setElevation(Ui.dp(ctx, 5));
        bar.setPadding(0, Ui.dp(ctx, 2), 0, Ui.dp(ctx, 2));

        for (int i = 0; i < names.length; i++) {
            final int index = i;
            TextView tab = new TextView(ctx);
            tabs[i] = tab;
            tab.setText(names[i]);
            tab.setGravity(Gravity.CENTER);
            tab.setTextSize(14);
            tab.setPadding(0, Ui.dp(ctx, 12), 0, Ui.dp(ctx, 12));
            bar.addView(tab, new LinearLayout.LayoutParams(0, -2, 1f));
            tab.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    switchTab(ctx, page, index, standalone, tampered, tabs);
                }
            });
        }
        // 把 bar 放在 root 末尾（weight 内容之上）
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(-1, -2);
        barParams.setMargins(Ui.dp(ctx, 12), Ui.dp(ctx, 8), Ui.dp(ctx, 12), Ui.dp(ctx, 12));
        root.addView(bar, barParams);

        switchTab(ctx, page, 0, standalone, tampered, tabs);
        return root;
    }

    private static void switchTab(Context ctx, LinearLayout page, int index,
                                  boolean standalone, boolean tampered, TextView[] tabs) {
        page.removeAllViews();
        ScrollView sv = new ScrollView(ctx);
        sv.setFillViewport(true);
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 4), Ui.dp(ctx, 12), Ui.dp(ctx, 8));
        if (index == 0) buildCore(ctx, box, standalone, tampered);
        else if (index == 1) buildRecords(ctx, box, standalone);
        else if (index == 2) buildTools(ctx, box, standalone, tampered);
        else buildAbout(ctx, box, tampered);
        sv.addView(box);
        page.addView(sv, new LinearLayout.LayoutParams(-1, -1));
        for (int j = 0; j < tabs.length; j++) {
            tabs[j].setBackground(j == index ? Ui.glass(ctx, 20, true) : null);
            tabs[j].setTextColor(j == index ? Ui.ACCENT : Ui.subColor(ctx));
            tabs[j].setTypeface(null, j == index
                    ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }
    }

    // ── 核心页 ──────────────────────────────────────────────────────
    private static void buildCore(Context ctx, LinearLayout box, boolean standalone, boolean tampered) {
        if (tampered) {
            TextView warn = new TextView(ctx);
            warn.setText("⚠ 本模块签名异常，疑似被篡改/重打包，核心功能已禁用。\n请从作者官方渠道重新下载安装。");
            warn.setTextSize(13);
            warn.setTextColor(Ui.DANGER);
            int p = Ui.dp(ctx, 14);
            warn.setPadding(p, p, p, p);
            LinearLayout card = Ui.card(ctx);
            card.addView(warn);
            box.addView(card);
        }

        // ── 消息与隐私 ──
        box.addView(Ui.section(ctx, "消息与隐私"));
        LinearLayout c1 = Ui.card(ctx);
        c1.addView(Ui.switchRow(ctx, "防撤回", "保留他人撤回的原文；自己的消息正常撤回",
                Config.antirecall, new Ui.OnToggle() {
            @Override public void on(boolean b) {
                Config.set("antirecall", b);
                try { AntiRecall.nativeSetRecall(b); } catch (Throwable ignored) { }
            }
        }));
        // ── 防撤回展示选项 ──
        c1.addView(Ui.dividerRow(ctx));
        c1.addView(Ui.switchRow(ctx, "撤回标识", "保留原消息，按所选模式显示撤回提示或颜色标识",
                Config.showRecallHint, new Ui.OnToggle() {
            @Override public void on(boolean b) { Config.set("showRecallHint", b); }
        }));
        c1.addView(Ui.dividerRow(ctx));
        c1.addView(Ui.navRow(ctx, "撤回显示模式",
                Config.recallDisplayMode == Config.RECALL_MODE_HINT ? "保留原文，仅显示撤回提示" : "保留原文并用气泡颜色标识",
                Ui.ACCENT, new View.OnClickListener() {
            @Override public void onClick(View v) { showRecallDisplayMode(ctx, (android.widget.LinearLayout) v); }
        }));
        c1.addView(Ui.dividerRow(ctx));
        c1.addView(recallBorderColorRow(ctx));
        c1.addView(Ui.dividerRow(ctx));
        c1.addView(Ui.navRow(ctx, "未保留原文时的提示文案",
                "当前：" + Config.recallHintText, Ui.ACCENT,
                new View.OnClickListener() {
            @Override public void onClick(View v) { showRecallHintTextEditor(ctx); }
        }));
        c1.addView(Ui.dividerRow(ctx));
        c1.addView(Ui.switchRow(ctx, "防对方已读", "只看不回＝未读，回复后才标记已读",
                Config.antiread, new Ui.OnToggle() {
            @Override public void on(boolean b) { Config.set("antiread", b); }
        }));
        c1.addView(Ui.dividerRow(ctx));
        c1.addView(Ui.switchRow(ctx, "后台消息存档", "存档并在聊天里还原后台被撤回的消息（需开消息预览）",
                Config.notifarchive, new Ui.OnToggle() {
            @Override public void on(boolean b) { Config.set("notifarchive", b); }
        }));
        c1.addView(Ui.dividerRow(ctx));
        c1.addView(Ui.switchRow(ctx, "屏蔽消息速览",
                "隐藏会话里 AI 总结的「消息速览」浮层（整条含边框）。",
                Config.blockaipeek, new Ui.OnToggle() {
            @Override public void on(boolean b) { Config.set("blockaipeek", b); }
        }));
        c1.addView(Ui.dividerRow(ctx));
        c1.addView(Ui.switchRow(ctx, "屏蔽相关知识",
                "隐藏飞书 AI 在消息下方推荐的「相关知识」卡片",
                Config.blockrelatedknowledge, new Ui.OnToggle() {
            @Override public void on(boolean b) { Config.set("blockrelatedknowledge", b); }
        }));
        c1.addView(Ui.dividerRow(ctx));
        c1.addView(Ui.switchRow(ctx, "消息时间常驻", "始终显示每条消息下方的发送时间，按手机时区显示",
                Config.messageTimeAlways, new Ui.OnToggle() {
            @Override public void on(boolean b) { Config.set("messageTimeAlways", b); }
        }));
        c1.addView(Ui.dividerRow(ctx));
        c1.addView(Ui.switchRow(ctx, "时间格式化", "独立于常驻开关，点击显示时间时也使用自定义格式",
                Config.messageTimeFormatting, new Ui.OnToggle() {
            @Override public void on(boolean b) { Config.set("messageTimeFormatting", b); }
        }));
        c1.addView(Ui.dividerRow(ctx));
        c1.addView(Ui.navRow(ctx, "消息时间格式", Config.messageTimePattern, Ui.ACCENT,
                new View.OnClickListener() {
            @Override public void onClick(View v) { showMessageTimeFormat(ctx); }
        }));
        c1.addView(Ui.dividerRow(ctx));
        c1.addView(Ui.switchRow(ctx, "移除聊天水印", "去掉外部联系人聊天里的平铺水印，改后重进聊天生效",
                Config.dewatermark, new Ui.OnToggle() {
            @Override public void on(boolean b) { Config.set("dewatermark", b); }
        }));
        c1.addView(Ui.dividerRow(ctx));
        c1.addView(Ui.switchRow(ctx, "主页更新提示", "有新版时在消息页顶部显示更新横幅",
                Config.updatebanner, new Ui.OnToggle() {
            @Override public void on(boolean b) { Config.set("updatebanner", b); }
        }));
        box.addView(c1);

        // ── 解除限制（入口，内容在二级）──
        box.addView(Ui.section(ctx, "解除限制"));
        LinearLayout c2 = Ui.card(ctx);
        c2.addView(Ui.navRow(ctx, "下载 / 保密模式 / 截图 / 审计",
                "进入解除限制与审计无痕设置", Ui.ACCENT, new View.OnClickListener() {
            @Override public void onClick(View v) { showUnlockPanel(ctx); }
        }));
        box.addView(c2);

        // ── 群组 ──
        box.addView(Ui.section(ctx, "群组"));
        LinearLayout c3 = Ui.card(ctx);
        c3.addView(Ui.switchRow(ctx, "保留被踢群记录", "被移出群时本地记录不清除并自动导出",
                Config.keepkicked, new Ui.OnToggle() {
            @Override public void on(boolean b) {
                Config.set("keepkicked", b);
                try { AntiRecall.nativeSetKeepKicked(b); } catch (Throwable ignored) { }
            }
        }));
        c3.addView(Ui.dividerRow(ctx));
        c3.addView(Ui.switchRow(ctx, "退群 / 被移出提醒", "别人退群或被移出时 Toast + 持久记录",
                Config.leavenotify, new Ui.OnToggle() {
            @Override public void on(boolean b) {
                Config.set("leavenotify", b);
                try { AntiRecall.nativeSetLeaveNotify(b); } catch (Throwable ignored) { }
            }
        }));
        box.addView(c3);

        // ── 同事 / 组织 ──
        box.addView(Ui.section(ctx, "同事与组织"));
        LinearLayout c4 = Ui.card(ctx);
        c4.addView(Ui.switchRow(ctx, "离职同事统计", "本地快照，累计归档离职名单",
                Config.resign, new Ui.OnToggle() {
            @Override public void on(boolean b) { Config.set("resign", b); }
        }));
        c4.addView(Ui.dividerRow(ctx));

        final boolean walkerOn = walkerBound(ctx);
        c4.addView(Ui.switchRow(ctx, "组织架构自动巡游",
                walkerOn
                        ? "已开启：进【通讯录→组织内联系人】即自动巡游"
                        : "未开启：拨动后到系统无障碍打开「组织巡游」",
                walkerOn, new Ui.OnToggle() {
            @Override public void on(boolean b) {
                try {
                    Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(i);
                } catch (Throwable t) {
                    Toast.makeText(ctx, "打开无障碍设置失败: " + t, Toast.LENGTH_LONG).show();
                }
            }
        }));
        if (walkerOn) {
            View stop = Ui.navRow(ctx, "停止正在进行的巡游", "走完当前部门即退出；也可按音量±停止",
                    Ui.DANGER, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    sendWalker(ctx, "WALK_STOP", "已请求停止，走完当前部门即退出");
                }
            });
            Ui.indent(stop, ctx);
            c4.addView(stop);
            View resume = Ui.navRow(ctx, "继续巡游（从断点续跑）", "解除暂停，再进组织架构页即接着走",
                    Ui.SUCCESS, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    sendWalker(ctx, "WALK_RESUME", "已恢复，进入组织架构页即从断点续跑");
                }
            });
            Ui.indent(resume, ctx);
            c4.addView(resume);
            View reset = Ui.navRow(ctx, "从头开始巡游（清除断点）", "下次进组织架构页重新走遍全树",
                    Ui.muteColor(ctx), new View.OnClickListener() {
                @Override public void onClick(View v) {
                    sendWalker(ctx, "WALK_RESET", "断点已清除，下次从总部重新开始");
                }
            });
            Ui.indent(reset, ctx);
            c4.addView(reset);
        }
        box.addView(c4);

        // ── 诊断 ──
        box.addView(Ui.section(ctx, "诊断"));
        LinearLayout c5 = Ui.card(ctx);
        c5.addView(Ui.switchRow(ctx, "诊断日志", "排查「防撤回不生效」用；关＝完全不写文件",
                Config.diaglog, new Ui.OnToggle() {
            @Override public void on(boolean b) {
                Config.set("diaglog", b);
                try { AntiRecall.nativeSetDiag(b); } catch (Throwable ignored) { }
            }
        }));
        box.addView(c5);

        box.addView(Ui.footer(ctx, "FeishuKit  v" + DataViews.moduleVersion()
                + (standalone ? "  ·  模块桌面入口" : "")));
    }

    private static void showMessageTimeFormat(final Context ctx) {
        final android.widget.EditText input = new android.widget.EditText(ctx);
        input.setSingleLine(true); input.setText(Config.messageTimePattern);
        int pad = Ui.dp(ctx, 16); input.setPadding(pad, pad, pad, pad);
        final TextView preview = new TextView(ctx);
        preview.setTextColor(Ui.subColor(ctx)); preview.setPadding(pad, 0, pad, pad);
        final Runnable update = new Runnable() {
            @Override public void run() {
                String pattern = input.getText().toString();
                preview.setText(MessageTimeFormat.valid(pattern)
                        ? "预览：" + MessageTimeFormat.format(System.currentTimeMillis() / 1000, pattern)
                            + "\n相对日期：{relative} HH:mm:ss（今天、昨天、前天、周一、上周一）\n常用：HH:mm:ss / MM-dd HH:mm / yyyy-MM-dd HH:mm:ss"
                        : "格式无效，请检查字母或引号。示例：yyyy-MM-dd HH:mm:ss");
            }
        };
        input.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { update.run(); }
            @Override public void afterTextChanged(android.text.Editable e) {}
        });
        LinearLayout box = new LinearLayout(ctx); box.setOrientation(LinearLayout.VERTICAL);
        box.addView(input); box.addView(preview);
        box.addView(Ui.navRow(ctx, "选择常用格式", "相对日期、完整日期、仅时间", Ui.ACCENT, new View.OnClickListener() {
            @Override public void onClick(View v) {
                final String[] patterns = { "{relative} HH:mm:ss", "{relative} HH:mm", "yyyy-MM-dd HH:mm:ss", "MM-dd HH:mm", "HH:mm:ss" };
                new AlertDialog.Builder(ctx).setTitle("常用时间格式")
                        .setItems(patterns, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) { input.setText(patterns[which]); }
                }).show();
            }
        }));
        update.run();
        final AlertDialog dialog = new AlertDialog.Builder(ctx).setTitle("消息时间格式")
                .setView(box).setPositiveButton("保存", null).setNegativeButton("取消", null)
                .setNeutralButton("恢复默认", null).show();
        Ui.styleDialog(dialog);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String pattern = input.getText().toString().trim();
                if (!MessageTimeFormat.valid(pattern)) { input.setError("时间格式无效"); return; }
                Config.setStr("messageTimePattern", pattern); dialog.dismiss();
            }
        });
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { input.setText(MessageTimeFormat.DEFAULT); }
        });
    }

    // ── 记录页 ──────────────────────────────────────────────────────
    private static void buildRecords(final Context ctx, LinearLayout box, boolean standalone) {
        // 向飞书请求推送档案/离职名单副本（飞书在运行时约 1s 内到位；本页再次打开可见）
        ArchiveSync.requestPull(ctx);
        box.addView(Ui.section(ctx, "消息记录"));
        LinearLayout c1 = Ui.card(ctx);
        c1.addView(Ui.navRow(ctx, "后台消息存档",
                Config.notifarchive ? "已启用 · " + NotifArchive.count() + " 条" : "未启用",
                Ui.ACCENT, new View.OnClickListener() {
            @Override public void onClick(View v) { DataViews.showNotifArchive(ctx); }
        }));
        c1.addView(Ui.dividerRow(ctx));
        c1.addView(Ui.navRow(ctx, "被踢群聊天记录", "自动导出的群聊存档", Ui.ACCENT,
                new View.OnClickListener() {
            @Override public void onClick(View v) { DataViews.showKickedExports(ctx); }
        }));
        c1.addView(Ui.dividerRow(ctx));
        c1.addView(Ui.navRow(ctx, "退群 / 移除记录", "开启退群提醒后的事件日志", Ui.ACCENT,
                new View.OnClickListener() {
            @Override public void onClick(View v) { DataViews.showLeaveLog(ctx); }
        }));
        c1.addView(Ui.dividerRow(ctx));
        c1.addView(Ui.navRow(ctx, "屏蔽日志", "最近 200 条 · 消息速览 / 相关知识", Ui.ACCENT,
                new View.OnClickListener() {
            @Override public void onClick(View v) { BlockLog.show(ctx); }
        }));
        box.addView(c1);

        box.addView(Ui.section(ctx, "同事档案"));
        LinearLayout c2 = Ui.card(ctx);
        int rc = DataViews.resignCount();
        int pc = DataViews.profilesCount();
        c2.addView(Ui.navRow(ctx, "离职名单",
                rc >= 0 ? rc + " 人" : "暂无数据", Ui.ACCENT,
                new View.OnClickListener() {
            @Override public void onClick(View v) { DataViews.showResignedList(ctx); }
        }));
        c2.addView(Ui.dividerRow(ctx));
        c2.addView(Ui.navRow(ctx, "全员档案",
                pc >= 0 ? pc + " 人" : "暂无数据", Ui.ACCENT,
                new View.OnClickListener() {
            @Override public void onClick(View v) { DataViews.showAllProfiles(ctx); }
        }));
        box.addView(c2);

        if (standalone) {
            TextView hint = new TextView(ctx);
            hint.setText(AccountPaths.label(ctx) + "\n"
                    + "说明：记录与档案按飞书账号隔离；飞书内「设置 → 模块设置」查看更完整。");
            hint.setTextSize(12);
            hint.setTextColor(Ui.muteColor(ctx));
            hint.setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 12), Ui.dp(ctx, 16), 0);
            box.addView(hint);
        }
        box.addView(Ui.footer(ctx, "记录仅存本地，不上传"));
    }

    // ── 工具页 ──────────────────────────────────────────────────────
    private static void buildTools(final Context ctx, LinearLayout box,
                                   final boolean standalone, final boolean tampered) {
        // 数据迁移
        box.addView(Ui.section(ctx, "数据"));
        LinearLayout c1 = Ui.card(ctx);
        c1.addView(Ui.navRow(ctx, "导出模块备份", "配置 + 档案打包为 zip", Ui.SUCCESS,
                new View.OnClickListener() {
            @Override public void onClick(View v) {
                try { DataMigration.chooseExport(ctx); }
                catch (Throwable t) { Toast.makeText(ctx, "导出失败: " + t, Toast.LENGTH_LONG).show(); }
            }
        }));
        c1.addView(Ui.dividerRow(ctx));
        c1.addView(Ui.navRow(ctx, "从备份恢复", "用备份覆盖当前同名数据", Ui.WARNING,
                new View.OnClickListener() {
            @Override public void onClick(View v) {
                new AlertDialog.Builder(ctx)
                        .setTitle("恢复模块数据")
                        .setMessage("将用备份中的配置和档案覆盖当前同名数据。恢复完成后建议重启飞书。")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("选择备份", new android.content.DialogInterface.OnClickListener() {
                            @Override public void onClick(android.content.DialogInterface d, int w) {
                                try { DataMigration.chooseImport(ctx); }
                                catch (Throwable t) { Toast.makeText(ctx, "恢复失败: " + t, Toast.LENGTH_LONG).show(); }
                            }
                        }).show();
            }
        }));
        c1.addView(Ui.dividerRow(ctx));
        c1.addView(Ui.navRow(ctx, "复制配置 JSON", "导出当前开关到剪贴板", Ui.ACCENT,
                new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager)
                            ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("FeishuKit", Config.snapshot()));
                    Toast.makeText(ctx, "配置 JSON 已复制", Toast.LENGTH_SHORT).show();
                } catch (Throwable t) {
                    Toast.makeText(ctx, "复制失败: " + t, Toast.LENGTH_SHORT).show();
                }
            }
        }));
        c1.addView(Ui.dividerRow(ctx));
        c1.addView(Ui.navRow(ctx, "从剪贴板导入配置", "粘贴 JSON 恢复开关", Ui.ACCENT,
                new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager)
                            ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm.getPrimaryClip() == null || cm.getPrimaryClip().getItemCount() == 0)
                        throw new IllegalArgumentException("empty");
                    CharSequence text = cm.getPrimaryClip().getItemAt(0).coerceToText(ctx);
                    Config.applySnapshot(text.toString());
                    Toast.makeText(ctx, "配置已导入", Toast.LENGTH_SHORT).show();
                } catch (Throwable t) {
                    Toast.makeText(ctx, "导入失败：剪贴板无有效 JSON", Toast.LENGTH_SHORT).show();
                }
            }
        }));
        box.addView(c1);

        // 系统
        box.addView(Ui.section(ctx, "系统"));
        LinearLayout c2 = Ui.card(ctx);
        c2.addView(Ui.navRow(ctx, "打开无障碍设置", "用于组织架构巡游", Ui.ACCENT,
                new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(i);
                } catch (Throwable ignored) { }
            }
        }));
        c2.addView(Ui.dividerRow(ctx));
        c2.addView(Ui.navRow(ctx, "版本与配置同步", "查看已安装版本、飞书加载版本及同步状态", Ui.ACCENT,
                new View.OnClickListener() {
            @Override public void onClick(View v) { RuntimeStatus.show(ctx); }
        }));
        c2.addView(Ui.dividerRow(ctx));
        c2.addView(Ui.navRow(ctx, "检查更新", "当前 v" + DataViews.moduleVersion(), Ui.ACCENT,
                new View.OnClickListener() {
            @Override public void onClick(View v) { DataViews.checkUpdate(ctx, false); }
        }));
        c2.addView(Ui.dividerRow(ctx));
        c2.addView(Ui.navRow(ctx, "查看诊断日志", "复制后可发给作者排查", Ui.ACCENT,
                new View.OnClickListener() {
            @Override public void onClick(View v) { DataViews.showDiagLog(ctx); }
        }));
        if (standalone && ctx instanceof android.app.Activity) {
            c2.addView(Ui.dividerRow(ctx));
            c2.addView(Ui.navRow(ctx, "隐藏桌面图标", "隐藏后可在系统设置的应用列表中恢复",
                    Ui.muteColor(ctx), new View.OnClickListener() {
                @Override public void onClick(View v) {
                    new AlertDialog.Builder(ctx)
                            .setTitle("隐藏桌面图标")
                            .setMessage("隐藏后桌面不再显示图标；LSPosed 模块详情里的「启动/设置」也会一并失效。\n\n需要恢复时：系统设置 → 应用 → FeishuKit → 启用，或在应用详情里手动启用主入口。")
                            .setNegativeButton("取消", null)
                            .setPositiveButton("隐藏", new android.content.DialogInterface.OnClickListener() {
                                @Override public void onClick(android.content.DialogInterface d, int w) {
                                    try {
                                        android.content.pm.PackageManager pm = ctx.getPackageManager();
                                        pm.setComponentEnabledSetting(
                                                new android.content.ComponentName(ctx, LauncherActivity.class),
                                                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                                                android.content.pm.PackageManager.DONT_KILL_APP);
                                        Toast.makeText(ctx, "图标已隐藏", Toast.LENGTH_SHORT).show();
                                    } catch (Throwable t) {
                                        Toast.makeText(ctx, "操作失败: " + t, Toast.LENGTH_SHORT).show();
                                    }
                                }
                            }).show();
                }
            }));
        }
        box.addView(c2);

    }

    private static void buildAbout(final Context ctx, LinearLayout box, boolean tampered) {
        box.addView(Ui.section(ctx, "FeishuKit"));
        LinearLayout intro = Ui.card(ctx);
        intro.setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 16), Ui.dp(ctx, 16), Ui.dp(ctx, 16));
        TextView title = new TextView(ctx);
        title.setText("FeishuKit"); title.setTextSize(22); title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(Ui.titleColor(ctx));
        intro.addView(title);
        TextView summary = new TextView(ctx);
        summary.setText("飞书 / Lark 增强工具集\n开源免费 · GPL-3.0\n当前版本：" + DataViews.moduleVersion());
        summary.setTextSize(13); summary.setTextColor(Ui.subColor(ctx));
        summary.setPadding(0, Ui.dp(ctx, 6), 0, 0); intro.addView(summary);
        box.addView(intro);

        box.addView(Ui.section(ctx, "项目链接"));
        LinearLayout links = Ui.card(ctx);
        links.addView(Ui.navRow(ctx, "项目主页", "github.com/haikow/com.chekayo.feishuantirecall", Ui.ACCENT,
                new View.OnClickListener() { @Override public void onClick(View v) {
                    DataViews.openUrl(ctx, "https://github.com/haikow/com.chekayo.feishuantirecall");
                }}));
        links.addView(Ui.dividerRow(ctx));
        links.addView(Ui.navRow(ctx, "讨论群（Telegram）", "t.me/fucklark", Ui.ACCENT,
                new View.OnClickListener() { @Override public void onClick(View v) {
                    DataViews.openUrl(ctx, "https://t.me/fucklark");
                }}));
        if (!tampered) {
            links.addView(Ui.dividerRow(ctx));
            links.addView(Ui.navRow(ctx, "赞赏 FeishuKit", "微信扫码 · 纯属鼓励，与功能无关", 0xFFFF6B6B,
                    new View.OnClickListener() { @Override public void onClick(View v) { DataViews.showReward(ctx); }}));
        }
        box.addView(links);

        box.addView(Ui.section(ctx, "贡献者"));
        LinearLayout contributors = Ui.card(ctx);
        addContributor(ctx, contributors, "SonderZhong", "项目维护者 · 界面、功能数据与双 API 版本维护", "S");
        contributors.addView(Ui.dividerRow(ctx));
        addContributor(ctx, contributors, "haikow", "项目发起与社区维护者", "H");
        contributors.addView(Ui.dividerRow(ctx));
        addContributor(ctx, contributors, "naiheSH", "修复消息速览搜索页闪退问题", "N");
        contributors.addView(Ui.dividerRow(ctx));
        addContributor(ctx, contributors, "HansYeoh", "飞书 8.x 适配与双 provider hook", "H");
        box.addView(contributors);
        box.addView(Ui.footer(ctx, "感谢所有参与适配、测试和反馈的贡献者"));
    }

    private static void addContributor(final Context ctx, LinearLayout parent, final String user,
                                       String contribution, String initial) {
        LinearLayout row = new LinearLayout(ctx);
        row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 10), Ui.dp(ctx, 12), Ui.dp(ctx, 10));
        row.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            DataViews.openUrl(ctx, "https://github.com/" + user);
        }});
        row.addView(ContributorAvatar.create(ctx, user, initial, 44));
        LinearLayout text = new LinearLayout(ctx); text.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(ctx); name.setText("@" + user); name.setTextSize(15);
        name.setTypeface(null, android.graphics.Typeface.BOLD); name.setTextColor(Ui.titleColor(ctx)); text.addView(name);
        TextView role = new TextView(ctx); role.setText(contribution); role.setTextSize(12); role.setTextColor(Ui.subColor(ctx));
        role.setPadding(0, Ui.dp(ctx, 3), 0, 0); text.addView(role);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f); lp.leftMargin = Ui.dp(ctx, 12);
        row.addView(text, lp); parent.addView(row);
    }

    // ── 解除限制二级面板 ────────────────────────────────────────────
    static void showUnlockPanel(final Context ctx) {
        final LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(ctx, 12);
        box.setPadding(p, Ui.dp(ctx, 4), p, Ui.dp(ctx, 4));

        box.addView(Ui.section(ctx, "解除限制"));
        LinearLayout c1 = Ui.card(ctx);
        c1.addView(Ui.switchRow(ctx, "解除下载限制", "加密聊天禁止另存时强制放行",
                Config.downloadunlock, new Ui.OnToggle() {
            @Override public void on(boolean b) { Config.set("downloadunlock", b); }
        }));
        c1.addView(Ui.dividerRow(ctx));
        c1.addView(Ui.switchRow(ctx, "解除保密模式限制", "恢复保密群的复制 / 转发，改后重进聊天生效",
                Config.restrictunlock, new Ui.OnToggle() {
            @Override public void on(boolean b) { Config.set("restrictunlock", b); }
        }));
        c1.addView(Ui.dividerRow(ctx));
        c1.addView(Ui.switchRow(ctx, "强制截图", "剥离 FLAG_SECURE，仅影响飞书进程",
                Config.forcescreenshot, new Ui.OnToggle() {
            @Override public void on(boolean b) { Config.set("forcescreenshot", b); }
        }));
        View dfs = Ui.navRow(ctx, "个别界面仍截不了？",
                "可选装系统级 DisableFlagSecure", Ui.muteColor(ctx), new View.OnClickListener() {
            @Override public void onClick(View v) {
                DataViews.openUrl(ctx, "https://github.com/LSPosed/DisableFlagSecure");
            }
        });
        Ui.indent(dfs, ctx);
        c1.addView(dfs);
        box.addView(c1);

        box.addView(Ui.section(ctx, "审计无痕"));
        LinearLayout c2 = Ui.card(ctx);
        c2.addView(Ui.switchRow(ctx, "全部操作不上报审计", "总闸：一处掐掉所有企业审计上报",
                Config.noauditall, new Ui.OnToggle() {
            @Override public void on(boolean b) { Config.set("noauditall", b); }
        }));
        c2.addView(Ui.dividerRow(ctx));
        c2.addView(Ui.switchRow(ctx, "连截图动作都不检测", "更彻底：截图检测器不启动，改后重启飞书生效",
                Config.screenshotnoaudit, new Ui.OnToggle() {
            @Override public void on(boolean b) { Config.set("screenshotnoaudit", b); }
        }));
        box.addView(c2);

        box.addView(Ui.section(ctx, "下载另存"));
        LinearLayout c3 = Ui.card(ctx);
        c3.addView(Ui.switchRow(ctx, "另存到系统「下载」", "复制一份到公共 Download 目录",
                Config.pubdownload, new Ui.OnToggle() {
            @Override public void on(boolean b) { Config.set("pubdownload", b); }
        }));
        c3.addView(Ui.dividerRow(ctx));
        View sub = Ui.navRow(ctx, "保存子目录", subdirDisplay(), Ui.ACCENT, new View.OnClickListener() {
            @Override public void onClick(View v) {
                final android.widget.EditText et = new android.widget.EditText(ctx);
                et.setHint("留空 = Download 根目录；如：Lark");
                et.setText(Config.pubdownloadSubdir);
                int pad = Ui.dp(ctx, 16);
                et.setPadding(pad, pad, pad, pad);
                new AlertDialog.Builder(ctx)
                        .setTitle("保存子目录名")
                        .setView(et)
                        .setPositiveButton("确定", new android.content.DialogInterface.OnClickListener() {
                            @Override public void onClick(android.content.DialogInterface d, int w) {
                                Config.setStr("pubdownloadSubdir", et.getText().toString());
                                Toast.makeText(ctx, "已设为 " + subdirDisplay(), Toast.LENGTH_SHORT).show();
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }
        });
        c3.addView(sub);
        box.addView(c3);

        box.addView(Ui.footer(ctx, "解除限制均为纯本地行为，不影响对方"));

        ScrollView sv = new ScrollView(ctx);
        sv.addView(box);
        new AlertDialog.Builder(ctx)
                .setTitle("解除限制")
                .setView(sv)
                .setPositiveButton("返回", null)
                .show();
    }

    private static String subdirDisplay() {
        String s = Config.pubdownloadSubdir;
        return (s == null || s.isEmpty()) ? "Download/（根目录）" : "Download/" + s;
    }

    /** 撤回提示文案编辑：支持 {name} 占位发送人。 */
    private static final String[] RECALL_COLOR_NAMES = {"红色", "蓝色", "绿色", "橙色", "灰色"};
    private static final String[] RECALL_COLORS = {"#E5484D", "#3B82F6", "#2CA36B", "#E88732", "#8A9199"};

    private static View recallBorderColorRow(final Context ctx) {
        final LinearLayout row = (LinearLayout) Ui.navRow(ctx, "撤回边框颜色", "当前：" + recallColorName(),
                Ui.ACCENT, null);
        final TextView subtitle = (TextView) ((LinearLayout) row.getChildAt(0)).getChildAt(1);
        final View swatch = new View(ctx);
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(Ui.dp(ctx, 18), Ui.dp(ctx, 18));
        size.rightMargin = Ui.dp(ctx, 12);
        swatch.setLayoutParams(size);
        row.addView(swatch, 0);
        updateRecallColorSwatch(swatch);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showRecallColors(ctx, subtitle, swatch); }
        });
        row.setClickable(true);
        return row;
    }

    private static void showRecallDisplayMode(final Context ctx, final android.widget.LinearLayout row) {
        final String[] labels = {"仅撤回提示", "消息颜色标识"};
        int checked = Config.recallDisplayMode == Config.RECALL_MODE_HINT ? 0 : 1;
        new AlertDialog.Builder(ctx).setTitle("撤回显示模式")
                .setSingleChoiceItems(labels, checked, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface dialog, int which) {
                        Config.setRecallDisplayMode(which == 0 ? Config.RECALL_MODE_HINT : Config.RECALL_MODE_COLOR);
                        TextView subtitle = (TextView) ((LinearLayout) row.getChildAt(0)).getChildAt(1);
                        subtitle.setText(Config.recallDisplayMode == Config.RECALL_MODE_HINT
                                ? "保留原文，仅显示撤回提示" : "保留原文并用气泡颜色标识");
                        RecallBubbleUi.schedule();
                        dialog.dismiss();
                    }
                }).setNegativeButton("取消", null).show();
    }

    private static String recallColorName() {
        for (int i = 0; i < RECALL_COLORS.length; i++)
            if (RECALL_COLORS[i].equals(Config.recallBorderColor)) return RECALL_COLOR_NAMES[i];
        return Config.recallBorderColor;
    }

    private static void updateRecallColorSwatch(View swatch) {
        android.graphics.drawable.GradientDrawable fill = new android.graphics.drawable.GradientDrawable();
        fill.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        fill.setColor(Color.parseColor(Config.sanitizeRecallBorderColor(Config.recallBorderColor)));
        swatch.setBackground(fill);
    }

    private static void showRecallColors(final Context ctx, final TextView subtitle, final View selected) {
        LinearLayout options = new LinearLayout(ctx);
        options.setOrientation(LinearLayout.VERTICAL);
        final AlertDialog dialog = new AlertDialog.Builder(ctx).setTitle("撤回边框颜色")
                .setView(options).setNegativeButton("关闭", null).create();
        for (int i = 0; i < RECALL_COLORS.length; i++) {
            final String color = RECALL_COLORS[i];
            LinearLayout option = new LinearLayout(ctx);
            option.setGravity(Gravity.CENTER_VERTICAL);
            option.setPadding(Ui.dp(ctx, 20), Ui.dp(ctx, 12), Ui.dp(ctx, 20), Ui.dp(ctx, 12));
            View swatch = new View(ctx);
            android.graphics.drawable.GradientDrawable fill = new android.graphics.drawable.GradientDrawable();
            fill.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            fill.setColor(Color.parseColor(color));
            swatch.setBackground(fill);
            LinearLayout.LayoutParams circle = new LinearLayout.LayoutParams(Ui.dp(ctx, 24), Ui.dp(ctx, 24));
            circle.rightMargin = Ui.dp(ctx, 16);
            option.addView(swatch, circle);
            TextView label = new TextView(ctx);
            label.setText(RECALL_COLOR_NAMES[i] + (color.equals(Config.recallBorderColor) ? "  ✓" : ""));
            label.setTextSize(16);
            label.setTextColor(Ui.titleColor(ctx));
            option.addView(label);
            option.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Config.setStr("recallBorderColor", color);
                    subtitle.setText("当前：" + recallColorName());
                    updateRecallColorSwatch(selected);
                    dialog.dismiss();
                }
            });
            options.addView(option);
        }
        dialog.show();
        Ui.styleDialog(dialog);
    }

    static void showRecallHintTextEditor(final Context ctx) {
        final EditText et = new EditText(ctx);
        et.setHint("例如：撤回了一条消息；或 {name} 撤回了消息");
        et.setText(Config.recallHintText);
        int p = Ui.dp(ctx, 16);
        et.setPadding(p, p, p, p);
        TextView tip = new TextView(ctx);
        tip.setText("可含 {name} 或 {sender} 代表发送人；留空恢复默认「撤回了一条消息」。");
        tip.setTextSize(12);
        tip.setTextColor(Ui.subColor(ctx));
        tip.setPadding(p, 0, p, p / 2);
        LinearLayout wrap = new LinearLayout(ctx);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(et);
        wrap.addView(tip);
        new AlertDialog.Builder(ctx)
                .setTitle("撤回提示文案")
                .setView(wrap)
                .setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        Config.setStr("recallHintText", et.getText().toString());
                        Toast.makeText(ctx, "提示文案: " + Config.recallHintText, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNeutralButton("恢复默认", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        Config.setStr("recallHintText", "撤回了一条消息");
                        Toast.makeText(ctx, "已恢复默认提示文案", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // 组织巡游广播（发给模块进程的 OrgWalkerService）
    private static void sendWalker(Context ctx, String action, String toast) {
        try {
            Intent bi = new Intent("com.chekayo.feishuantirecall." + action);
            bi.setPackage("com.chekayo.feishuantirecall");
            ctx.sendBroadcast(bi);
            Toast.makeText(ctx, toast, Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(ctx, "操作失败: " + t, Toast.LENGTH_LONG).show();
        }
    }

    // 组织巡游服务是否已在系统无障碍中开启
    static boolean walkerBound(Context ctx) {
        try {
            String s = Settings.Secure.getString(
                    ctx.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return s != null && s.contains("OrgWalkerService");
        } catch (Throwable t) { return false; }
    }

    /** 飞书进程内读 AntiRecall.TAMPER；桌面进程加载失败时视为未篡改。 */
    private static boolean readTampered() {
        try {
            Class<?> c = Class.forName("com.chekayo.feishuantirecall.AntiRecall");
            return c.getField("TAMPER").getInt(null) == 2;
        } catch (Throwable t) { return false; }
    }

    /** 浅色品牌头：Logo + 标题 + 副标题，替代系统黑 ActionBar。 */
    private static View buildHeader(Context ctx, boolean standalone) {
        LinearLayout wrap = new LinearLayout(ctx);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setBackgroundColor(Color.TRANSPARENT);

        LinearLayout h = new LinearLayout(ctx);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);
        int px = Ui.dp(ctx, 16), py = Ui.dp(ctx, 14);
        h.setPadding(px, py, px, py);

        try {
            int logoId = ctx.getResources().getIdentifier("module_logo", "drawable", ctx.getPackageName());
            if (logoId != 0) {
                android.widget.ImageView logo = new android.widget.ImageView(ctx);
                logo.setImageResource(logoId);
                logo.setAdjustViewBounds(true);
                logo.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
                int sz = Ui.dp(ctx, 36);
                h.addView(logo, new LinearLayout.LayoutParams(sz, sz));
                View gap = new View(ctx);
                h.addView(gap, new LinearLayout.LayoutParams(Ui.dp(ctx, 12), 1));
            }
        } catch (Throwable ignored) { }

        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(ctx);
        title.setText("FeishuKit");
        title.setTextSize(18);
        title.setTextColor(Ui.titleColor(ctx));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        texts.addView(title);
        TextView sub = new TextView(ctx);
        sub.setText(standalone
                ? "飞书增强模块 · 桌面配置"
                : "模块设置 · v" + DataViews.moduleVersion());
        sub.setTextSize(12);
        sub.setTextColor(Ui.subColor(ctx));
        sub.setPadding(0, Ui.dp(ctx, 2), 0, 0);
        texts.addView(sub);
        h.addView(texts);

        wrap.addView(h, new LinearLayout.LayoutParams(-1, -2));
        View line = new View(ctx);
        line.setBackgroundColor(Ui.divider(ctx));
        wrap.addView(line, new LinearLayout.LayoutParams(-1, 1));
        return wrap;
    }

    /** 状态栏高度（无 ActionBar 时也留出安全区）。 */
    private static int statusBarPadding(Context ctx) {
        try {
            int id = ctx.getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (id > 0) return ctx.getResources().getDimensionPixelSize(id);
        } catch (Throwable ignored) { }
        return Ui.dp(ctx, 24);
    }
}
