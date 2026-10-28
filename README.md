# FeishuKit

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
| **防撤回** | 自己撤回走飞书正常流程；他人原文保留，灰色虚线跟随实际气泡轮廓，不新增提示行；已撤回消息仅保留查看、复制和下载操作 |
| **防对方已读** | 清空出站已读回执的 message 列表，只看不回＝未读 |
| **单条标记已读** | 长按消息菜单手动将单条消息标记为已读 |
| **后台消息存档** | 从通知回捞后台被撤回消息的原文 |
| **屏蔽消息速览** | 隐藏 AI 总结「消息速览」浮层（整条） |
| **屏蔽相关知识** | 屏蔽消息下的相关知识推荐 |
| **消息时间常驻** | 时间显示与格式化可独立设置 |
| **界面与数据管理** | 设置面板、数据备份、档案分类和账号隔离 |
| **双 API 版本维护** | 同步维护 legacy API82 与 modern API102 构建 |
| **移除聊天水印 / 解除下载与保密限制 / 审计无痕** | 设置面板「解除限制」分组 |
| **被踢群 / 退群提醒** | 保留被移出群的本地记录，退群事件 Toast + 日志 |
| **组织通讯录巡游 + 花名册归档** | 无障碍 DFS 走遍部门触发懒加载后归档 |
| **离职同事资料归档** | 解析 profile blob；档案/离职名单可同步到模块侧查看 |

设置入口：桌面图标 **FeishuKit** · LSPosed 模块详情「启动」· 飞书「我 → 设置 → FeishuKit 设置」。

本分支以飞书 **8.2.6（8020650）** 为适配基准。撤回标记按账号、会话和消息保存，进程重启后可恢复；账号未识别或原文不存在时放行飞书原行为。历史上已经丢失的原文不能因此补回。

已保留的撤回消息仅保留长按复制、复制链接、保存及媒体播放相关操作，仍可点击查看文档、图片和视频。引用、创建话题、转发、表情回复等操作及左滑回复会被拦截，已打开的菜单在消息撤回后也会重新检查。关闭虚线提示不会解除这些限制。表情请求的已读补报也会检查撤回标记，避免无效操作触发补报；正常消息仍保持原有回复后补报规则。气泡描边使用宿主的轮廓路径，跟随连续消息的四角变化。

撤回消息的翻译按钮会停止加载动画并移除点击入口，避免保留卡片一直显示翻译中；撤回到达但列表未重新绑定时也会清理。该处理仅作用于已标记的撤回消息，普通消息及列表复用后的翻译继续使用飞书原有流程。

本次审计修复补齐话题回复页的表情和翻译入口，处理 SQLite 锁竞争重试、旧 WAL 快照以及 native 加载失败时的 Java 兜底。标记索引在后台加载并合并写入；隐藏窗口停止轮询，气泡路径先按宿主原始尺寸取得，再复制内缩。修复依据、回归命令与尚未完成的真机验证见 [撤回功能审计修复说明](docs/recall-audit-fixes.md)。

8.2.6 已更新复制审计入口、截图监听及新增截图回调、图片/视频下载审计服务发现；候选方法需满足完整签名，避免旧混淆类名被复用时误挂。上述入口已在真机确认加载，但不代表所有企业策略、全部消息类型和所有功能组合均经过端到端测试。

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
- Windows 可在项目根目录创建 `build.local.psd1`，配置集中保管的签名文件；该配置已加入 `.gitignore`。之后直接运行 `pwsh ./build.ps1` 即可。路径优先级为 `-Keystore` 参数 → `KEYSTORE` 环境变量 → 本地配置 → 项目内 `debug.keystore`；显式配置的文件缺失时会报错，避免意外换签名。相对路径按项目根目录解析。

  ```powershell
  @{
    Keystore = 'C:\Users\your-name\Keys\com.chekayo.feishuantirecall.keystore'
  }
  ```

  签名别名为 `androiddebugkey`，store/key 密码均为 `android`（与现有构建脚本一致）。要覆盖安装已有 APK，需使用相同签名，且 versionCode 不低于已安装版本。

## 安装

1. Root + [LSPosed](https://github.com/LSPosed/LSPosed)（或 Xposed 框架）。
2. 安装 Release 中的 APK（或本地 `build.ps1` / `build.sh` 产物），在 LSPosed 勾选作用域为飞书 / Lark，重启飞书。
3. 打开设置：桌面 **FeishuKit** 图标，或飞书「我 → 设置 → FeishuKit 设置」。
4. 若在工具页点过「隐藏桌面图标」，LSPosed 的启动入口也会失效——到系统设置 → 应用 → FeishuKit → 启用即可恢复。

## 版本适配

飞书 native `liblark.so` 每个版本都可能重排符号、混淆变更（例如 7.71.8 的混淆重排就打崩过 Java 兜底）。定位锚点与更新方法见：

- `防撤回-逆向方法论手册.md`
- `防已读-逆向结论-v7.69.6.md` / `防已读-复盘-从native碰墙到Java破局.md`
- [CONTRIBUTING.md](CONTRIBUTING.md) —— 怎么给新版本重新定位偏移并提 PR

## 双版本发布（legacy / api102）

自 v1.8.9 起维护两条线，**功能一致、包名相同、同一签名**（可互相覆盖安装，数据不丢）：

| 线 | 分支 | 适用框架 | versionCode | 更新检查 |
|---|---|---|---|---|
| **legacy（主线）** | `main` | 传统 LSPosed / XposedBridge API 82 | 正常递增 | `version.json` |
| **api102** | `libxposed-api-102-migration` | 仅 libxposed API 102 新框架（LSPosed v2.2+ modern） | = legacy + 1000 | `version-api102.json` |

发布形态：

- **本仓库**：每个版本一个 Release 挂两个包（`feishukit-<版本>-legacy.apk` / `feishukit-<版本>-api102.apk`）+ 选择说明表。
- **模块市场镜像（Xposed-Modules-Repo）**：保持两个 Release——稳定版单 APK（Latest，市场更新器只认单资产，绝不多包同 Release）+ api102 prerelease。市场永远只向 legacy 用户推 legacy 包。
- api102 线在旧框架上不会生效；拿不准选哪个就装 legacy。
- 待 legacy 用户迁移完成（或主流框架仅支持新 API）后，api102 合回 main 转正。

## 赞助 / Sponsor

飞书更新频繁，跟版本、重新定位偏移、修复 hook 需要持续投入。如果 FeishuKit 帮到了你，欢迎赞助支持我持续维护、适配新版本：

- **爱发电**：https://ifdian.net/a/haikow
- **微信赞赏码**：

<img src="app/src/main/assets/reward.png" width="240" alt="微信赞赏码">

赞助纯属无偿鼓励，与功能无关 —— 本项目所有功能开源免费，**不含任何赞助专属内容**。

## 贡献者 / Contributors

感谢每一位让 FeishuKit 变得更好的贡献者：

| 贡献者 | 贡献 |
|---|---|
| [@SonderZhong](https://github.com/SonderZhong) | 界面与设置体验、数据备份、档案分类、账号隔离、撤回提示、时间常驻、单条标记已读、消息速览与相关知识屏蔽、legacy / API102 双线维护 |
| [@haikow](https://github.com/haikow) | 项目发起与社区维护 |
| [@naiheSH](https://github.com/naiheSH) | 修复屏蔽消息速览误拦搜索页导致的闪退（#5） |
| [@HansYeoh](https://github.com/HansYeoh) | 飞书 8.x 适配：messages 列序运行时自适应 + 双 provider hook（#6） |

> 欢迎提 PR 参与适配与修复， CONTRIBUTING.md 有方法论；所有被合入的贡献都会在这里署名。

## License

[GPL-3.0](LICENSE)（copyleft，防闭源套壳）。
