# FeishuKit（fuck lark）

> 给飞书 / Lark 加点"超能力"的逆向工具集 —— Xposed/LSPosed 模块 + Linux 桌面端补丁 + 逆向研究脚本。

**社区维护，best-effort。** 飞书更新频繁，随时可能打崩现有 hook。欢迎为新版本提 PR —— 见 [CONTRIBUTING.md](CONTRIBUTING.md)。

---

## ⚠️ 免责声明（先读）

- 本项目为**纯粹的 FOSS 项目**，整体逻辑完全开源、接受社区公开审计，**不包含任何赞助专属功能、网络提权或后门**。赞助（爱发电）仅作无偿鼓励，与功能解锁无关。
- 本项目**非盈利**，仅供**安全研究、代码学习、调试分析与个人学习**之目的。使用者需自行承担全部风险与法律责任。
- 飞书 / Lark 是**企业办公工具**。在你所在组织的账号上使用对抗性功能（防撤回、防已读、批量归档同事资料等）**可能违反公司制度、劳动合同，乃至《个人信息保护法》**。后果自负。
- **切勿**将本仓库用于抓取、传播他人个人信息。相关研究脚本仅演示技术机制。
- 仓库内**不包含**任何 ByteDance/飞书的二进制、反编译产物或抓取到的真实数据 —— 也请贡献者不要提交这类内容（见 `.gitignore`）。

---

## 功能一览

Android（Xposed/LSPosed 模块，包名 `com.chekayo.feishuantirecall`）：

| 功能 | 说明 |
|---|---|
| **防撤回** | native SQL 层拦截 —— 撤回本质是对同一 id `REPLACE INTO messages` 把 `is_recalled` 置 1、`content` 清空。模块检测到撤回写入后保留原文，原文与"已撤回"提示并存 |
| **撤回消息后台存档** | 撤回消息落本地归档 |
| **防已读回执**（`stealth-read/`）| 拦截出站已读回执 RPC，静默阅读不回执 |
| **被踢群 / 静默退群保留** | 被移出群后保留会话与历史 |
| **组织通讯录巡游 + 花名册归档** | 无障碍 DFS 走遍部门触发懒加载后归档（`OrgWalkerService`）|
| **离职同事资料归档** | 解析 profile blob（`larkresign/ResignTracker`）|

Linux 桌面端（`linux-desktop-unread/`）：Electron `.asar` 补丁 + autopatch systemd 单元（升级后自动重打补丁）。

> ⚠️ 组织通讯录巡游 / 花名册 / 离职资料归档涉及**他人个人信息**，敏感度远高于防撤回。请谨慎，务必只在合规前提下用于自身研究。

---

## 仓库结构

```
app/                    Android Xposed 模块（Java）
native/jni/             native hook（C++，SQL 层拦截 + inline hook）
stubs/                  Xposed API 编译期桩（无需 XposedBridge 源）
frida/                  frida 动态探针（逆向定位用）
stealth-read/           防已读回执研究脚本（frida hunt / 反汇编 / 抓取）
linux-desktop-unread/   Linux 桌面端 .asar 补丁 + autopatch
scripts/                水印嵌入/提取等杂项
build.sh / build.ps1    一键构建（Linux / Windows）
*.md                    各功能逆向方法论与复盘
```

## 构建

需要 JDK 11+、Android SDK（build-tools + platform）、Android NDK。

```bash
./build.sh          # Linux/macOS
# 或 pwsh ./build.ps1   # Windows
```

- 脚本会自动探测 SDK/NDK/JDK；也可用环境变量覆盖（`ANDROID_HOME` / `ANDROID_NDK_HOME` / `JAVA_HOME`）。
- **签名**：缺 keystore 时脚本自动生成本地 `debug.keystore`（不入库）。正式发布请用你自己的 keystore（`KEYSTORE=/path/to/your.jks ./build.sh`）。

## 安装

1. Root + [LSPosed](https://github.com/LSPosed/LSPosed)（或 Xposed 框架）。
2. 安装构建出的 APK，在 LSPosed 里勾选作用域为飞书 / Lark，重启飞书。
3. 各功能开关在模块启动器 UI / 飞书设置页内。

## 版本适配

飞书 native `liblark.so` 每个版本都可能重排符号、混淆变更（例如 7.71.8 的混淆重排就打崩过 Java 兜底）。定位锚点与更新方法见：

- `防撤回-逆向方法论手册.md`
- `防已读-逆向结论-v7.69.6.md` / `防已读-复盘-从native碰墙到Java破局.md`
- [CONTRIBUTING.md](CONTRIBUTING.md) —— 怎么给新版本重新定位偏移并提 PR

## 赞助 / Sponsor

飞书更新频繁，跟版本、重新定位偏移、修复 hook 需要持续投入。如果 FeishuKit 帮到了你，欢迎赞助支持我持续维护、适配新版本：

- **爱发电**：https://ifdian.net/a/haikow
- **微信赞赏码**：

<img src="app/src/main/assets/reward.png" width="240" alt="微信赞赏码">

赞助纯属无偿鼓励，与功能无关 —— 本项目所有功能开源免费，**不含任何赞助专属内容**。

## License

[GPL-3.0](LICENSE)（copyleft，防闭源套壳）。
