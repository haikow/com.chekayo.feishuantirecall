# 飞书 Linux 桌面端补丁：防撤回 + 已读→对方显示未读

针对飞书桌面端（Linux / Electron，`/opt/bytedance/feishu`）的两个增强：

1. **防撤回**：被对方撤回的消息，原文继续显示为「xxx撤回了一条消息**: 原文内容**」。
2. **已读→对方显示未读**：你看过对方消息后，对方界面仍保持「未读」。

一个脚本 `patch_feishu.py` 同时打这两个补丁。

---

## 原理

### ① 防撤回（内容缓存法）
桌面撤回会**抹掉原文**、只留「xxx撤回了一条消息」系统提示——所以不能事后渲染，要**提前缓存**：

- **撤回前**：在会话预览更新 `upsertPreviews` 里，把预览的 `{id, 内容}` 存进 `localStorage.recalledMessageCacheList`。
- **撤回时**：系统提示渲染处按消息 id 取回缓存，拼到提示后面。

> 思路来自同源的 Windows「吾乐吧」补丁（`FeiShuRevokeMsgPatcher`）。其注入 JS 是 .NET UTF-16 字符串，
> 用 `strings -e l` 可抠出。详见 `防撤回-复盘-封档误判到缓存法破局.md`。

### ② 已读→对方显示未读
飞书 JS SDK worker 看过消息后调用 `<logger>.info("updateMessagesMeRead", {...含 messageIds})`
上报已读。补丁在该调用前注入 `t.messageIds=[],` 清空上报列表 → 服务器收不到已读回执。

> 目标是新版实际加载的 **`messenger-next.asar`**（不是遗留的 `messenger.asar`，
> 后者新版飞书根本不加载，可用 `sudo lsof | grep asar` 确认）。

---

## 使用

```bash
# 关闭飞书后执行；改的是 root 拥有的 asar，需 sudo
sudo python3 patch_feishu.py                    # 默认 /opt/bytedance/feishu
sudo python3 patch_feishu.py /opt/bytedance/feishu

# 重启飞书生效
pkill -9 -x feishu ; /opt/bytedance/feishu/feishu &
```

- 首次运行会把原 asar 备份为 `messenger-next.asar.bak`。
- 幂等：以 `.bak` 为纯净基线重打；**已读未读 + 缓存写 + 缓存读**三者任一为 0（版本不兼容）时中止、不改原文件。
- **飞书每次自动更新会覆盖 asar，更新后重跑本脚本即可。**

## 回滚

```bash
sudo cp /opt/bytedance/feishu/webcontent/messenger-next.asar.bak \
        /opt/bytedance/feishu/webcontent/messenger-next.asar
# 重启飞书
```

## 局限

**防撤回**（同 Windows 补丁）：缓存来自会话预览=各聊天**最后一条**，撤回最近消息最稳；
很老的历史消息可能没缓存到；只对**打补丁后新到**的消息有效；图片/表情显示 `[图片]`/`[表情]`。

**防已读**（只堵主入口）：引用回复、给消息贴表情、接收文件仍会向对方泄露「已读」。

## 文件

- `patch_feishu.py` — 补丁脚本（防撤回 + 防已读）
- `patch_feishu_unread.py` — 旧版（仅防已读，保留备查）
- `asar.py` — asar 解包/打包库（源自 BeautifulDiscord，MIT）
- `防撤回-复盘-封档误判到缓存法破局.md` — 为什么"封档"是误判、缓存法怎么破的复盘
