# 撤回功能审计修复说明

2026-10-17；分支 `api102/recall-self-and-bubble`；适配目标为用户确认的飞书 **8.2.6（8020650）**。本轮修复针对 `7d2d7c6` 的审计反馈，按要求 amend 同一个功能提交，不增加第二个提交。

## 审计项与修复

| 审计项 | 修复方式 | 回归证据 |
| --- | --- | --- |
| P2：SQLITE_BUSY 重试绕过保护 | 保护语句的 SAVEPOINT、元数据写入等失败直接返回 SQLite 错误，禁止再放行破坏性原语句。UPDATE 执行错误时 reset 原 statement，再回滚私有 savepoint；保留绑定参数，调用者可直接重试。自己消息的 REPLACE 保持宿主重试方式。 | 双连接持有写锁；UPDATE/宿主形态 REPLACE；自己/他人；同一 statement 不 reset 重试；检查原文、事务及临时 trigger 清理。 |
| P2：旧 WAL 快照覆盖已提交标记 | 只在 autocommit 且连接没有 busy statement 时加载；在连接互斥锁内检查和读取。延后刷新保持 pending，提交标记集合按账号单调合并，不用本地 generation 排序数据库快照。step 前后均有观察点，覆盖单行 SELECT 随后直接 finalize 的模式。 | 活动 SELECT、另一连接提交标记、读连接 ROLLBACK；标记不会回退，cursor 结束后 pending 能重新加载；事务回滚不会发布未提交标记。 |
| P2：话题页表情条绕过只读守卫 | 拦截 `ReplyInThreadViewModel.addReaction/deleteReaction`，在宿主方法执行前根据第一参数 messageId、当前账号和消息模型检查标记。 | 使用生产安装函数捕获两个入口 hook，验证正常消息执行一次、撤回消息不执行、账号隔离；第二参数 reactionType 不当作消息 ID。 |
| P2：native 失败连带禁用 Java Mapper | `RecallMessages.install` 在 native 启动前执行，各层分别捕获初始化错误。账号 reader 不依赖标记目录或 native 映射成功；Passport 仍在宿主 Application 初始化完成后才调用。 | 使用生产 `installRecall`、模拟宿主 ClassLoader 和 native 加载异常；未手工注入 reader；检查他人内容恢复、自己正常撤回及后续表情 hook 安装。 |
| P2：UI 查询同步刷盘 | 内存索引立即更新；单个后台 worker 加载旧索引、合并新标记，约 100ms 合并写入，保留 flush/fsync/原子替换；失败保留 dirty 并重试。SDK 账号目录检查也转到该 worker。 | 阻塞后台读取时，查询及 500 次添加仍可完成；旧、新标记合并为一次写入；记录磁盘方法执行线程；写入失败后重试。 |
| P2：话题页翻译控件未清理 | 单独跟踪 `ReplyInThreadTranslateBlock` 的动画、标签和翻译容器；清理监听器、停止动画并隐藏控件。迟到的动画启动及捕获旧模型的点击也检查标记，文档/视频正文点击不受影响。 | 8.2.6 DEX 核对入口；Android 控件用例真机通过，覆盖话题 holder、迟到回调及控件复用；宿主话题 bind/click hook 安装成功。 |
| P3：后台常驻轮询 | 根据 attached、窗口可见性及 isShown 决定是否继续；窗口显示、布局和重新 attach 可唤醒。关闭虚线时清除描边并停止仅用于气泡的轮询；可见翻译入口仍按需清理。关闭防撤回时不为普通翻译继续轮询。 | 隐藏但未 detach、显示恢复、关闭提示、关闭防撤回和重新 attach 用例均在真机隔离进程通过。 |
| P3：Path 缓存键碰撞 | 只按 `(0,0,width,height)` 请求宿主路径；复制到模块私有 Path，再变换内缩，不向宿主缓存请求小数边界。 | mdpi 下 101×51 与 100×50 的缓存键、源 Path 不变和副本内缩检查均在真机通过；真实聊天中不对称文字气泡及云文档卡片的轮廓视觉复核通过。 |

## 单列边界及实现取舍

- mixed UPDATE 不再从 expanded SQL 重放。原 prepared statement 在连接内执行，临时 BEFORE UPDATE trigger 对需保留的行写标记并 `RAISE(IGNORE)`；自己及未知发送人行正常更新。trigger 与标记写入处于同一 savepoint，执行完删除。连接互斥锁覆盖整个过程，其他连接看不到该 TEMP trigger。
- 因此保留原始绑定的长度与类型，包括 TEXT `A\0B`、BLOB、命名参数、重复名称、编号空槽和 WHERE 参数。本轮验证的是合成输入保真性，不据此断言飞书曾发送该 TEXT。
- 没有使用 `sqlite3_transfer_bindings`：目标 APK 的 WCDB2 未导出它。新增的 statement 状态、reset 和连接互斥锁符号已在目标 APK 的两个 SQLite provider 中静态核对。
- 恢复 `adapt_msg_cols()` 调用用于既有列序适配。这是兼容性维护，未将审计中尚无生产证据的列序风险认定为 8.2.6 已发生的数据错误。
- Java `.marks` 是异步索引副本。native 标记仍在数据库事务内持久化；仅 Java 兜底产生的新标记在后台写入完成前强制结束进程，仍有未落盘窗口。冷启动索引加载期间，native 快照和 Java 内存标记逐步就绪。
- 快照采用标记追加语义；没有新增删除历史标记或限制历史大小的策略。未知归属、无原文和不支持的 SQL 形态继续保留原有边界。

## 本轮验证

| 检查 | 结果 |
| --- | --- |
| 完整生产构建（Java、Android arm64 native、DEX、APK） | 通过；使用现有本地签名配置和原包名 |
| `RecallBehaviorTest` | 66 项通过 |
| `RecallAuditRegressionTest` | 26 项通过 |
| 迁移及历史审计回归 | 94 项通过 |
| `native_test.cpp` | 桌面 SQLite 3.50.4：274 项通过，使用全新合成数据库 |
| Android 翻译/可见性/轮廓用例 | 真机隔离 `app_process`：33 项通过，使用 Android View 和合成消息模型 |
| Android 系统 SQLite | 真机 arm64：274 项通过，使用全新合成数据库 |
| 目标 APK 自带 `libwcdb2.so` | 真机 arm64：274 项通过，使用全新合成数据库及 APK 原库 |
| 目标 APK 自带 `libsqlcipher.so` | 真机 arm64：274 项通过，使用全新合成数据库及 APK 原库 |
| 原包名覆盖安装及宿主冷启动 | 覆盖安装成功；飞书 8.2.6 冷启动成功，主界面渲染且进程持续运行 |
| 宿主 hook 加载 | WCDB2 `sqlite3_step`、话题 add/deleteReaction、普通/话题翻译、气泡及操作守卫均确认安装 |
| 真实会话：撤回消息菜单 | 已撤回文字仅“复制”，云文档仅“复制链接”；均无表情条、回复、转发或创建话题入口 |
| 真实会话：未撤回消息对照 | 普通文字仍显示表情条及回复、转发、创建话题等菜单；只检查展示，未提交任何操作 |
| 真实会话：左滑与文档查看 | 已撤回文字、云文档左滑均未激活回复框；文档点击后正文正常加载；卡片无翻译转圈 |
| 真实会话：轮廓与持久化 | 文字气泡左侧近直角、右侧圆角均贴合虚线；云文档卡片轮廓贴合；后台返回及强制停止后冷启动，两条既有样本的原文和标记均保留 |
| `git diff --check` | 通过 |

设备补测于 2026-10-17 完成，使用 Android API 37 的 arm64 真机。Java 回归也重新执行通过（66 + 26 + 94，共 186 项）。三种 SQLite provider 各自独立运行同一组 274 项检查，包含 BUSY 重试、WAL 快照、事务/临时 trigger 清理及绑定参数保真；这表示同一批用例在三种库上通过，不表示新增了 822 个不同场景。Android 控件测试使用隔离进程中的真实 View 和合成 holder/model，不等同于宿主完整话题页。

已使用上游签名和原包名覆盖安装，未卸载模块或清除飞书数据，也未发送测试消息、表情或创建话题。冷启动日志确认 WCDB2 消息库 hook 及新增话题 hook 已安装。首次启动另见 `libsqlcipher.so resolve failed (sym missing)`，该次进程未安装此 provider；随后真实会话持久化测试的冷启动中，日志确认 SQLCipher 和 WCDB2 的 `sqlite3_step` 均安装成功。两次结果表明该失败并非每次出现，未定位根因，不能将后一轮成功视为已修复启动稳定性问题。配置 provider 首次查询也出现不可见日志，随后配置广播桥安装成功；本轮未专项验证全部配置同步功能。

真实会话检查使用用户打开的既有撤回文字和云文档样本，检查后已返回该会话。文字左滑会显示宿主消息时间戳，但未进入回复；后台返回的视觉正常也不等同于直接测量后台轮询次数，停止/恢复调度由前述隔离控件测试覆盖。会话中的历史“你撤回了一条消息”仍正常显示，本轮没有新增自发消息验证即时撤回。

新撤回事件、话题页现有表情条、翻译等待时撤回、视频查看/下载及对端已读状态仍未在此次真实会话中验证；没有直接修改真实消息数据库制造这些场景。本说明不等于第二轮审计通过或发布批准。

回归入口（在仓库根目录执行）：

```powershell
./build.ps1
./tests/recall/run.ps1
./tests/recall/run-native-host.ps1 -SqliteLibrary '<host SQLite DLL>'
./tests/recall/run-translation-ui.ps1 -CompileOnly

# 设备可用时：仅运行隔离进程和合成数据库，不打开飞书数据
./tests/recall/run.ps1 -Serial '<device serial>'
./tests/recall/run-translation-ui.ps1 -Serial '<device serial>'
```

提交仅包含源码、测试与说明；APK、逆向二进制、日志、设备标识和本地签名配置留在忽略路径中。

## 2026-10-18：PR 自动门禁反馈

针对 [PR #11 的门禁评论](https://github.com/haikow/com.chekayo.feishuantirecall/pull/11#issuecomment-6237100750)，将 API102 模块版本升为 `1033 / 1.8.11-api102`，同步 Manifest、AntiRecall 常量、modern `module.prop` 以及 `version-api102.json` 的版本、更新说明和下载链接。legacy `version.json` 不变，不把 API102 更新推送给旧框架用户。

G7 原实现只识别无后缀版本名，且用 legacy latest release 比较所有发布线；现按 PR 目标分支识别版本元数据，只检查对应生产文件中的新增声明，modern 要求四处一致。已发布版本从全部非 draft release 获取，包含 API102 prerelease，按同线最大 versionCode 比较；缺少同线发布记录、缺少声明、重复声明或发布线不明均拒绝。文件清单改为分页获取。新增 `tests/pr_gate/test_versions.py` 覆盖两条发布线及上述拒绝场景。

G3 的 native SQL 过滤是本功能必要实现；G4 的构建变更用于显式选择本地签名及验证产物，门禁脚本和新版本校验器也继续纳入 G4 人工审查范围。G5 的新增执行权限设置仅用于 `tests/recall/run.ps1` 推送的隔离测试程序，测试目录由随机后缀生成，不读取飞书真实消息库。修复 G5 报告生成时的子 shell 变量丢失，使其能显示具体命中行，方便维护者确认用途。

G3/G4/G5 的人工确认要求保留。本次修正不代表自动门禁全绿；维护者需要审查变更，并使用更新后的门禁脚本重跑检查。

本轮版本修正后，完整生产构建和原签名校验通过，APK 内部版本为 `1033 / 1.8.11-api102`；186 项 Java 回归重新执行通过。门禁新增 15 项测试通过，包含真实 Bash 入口的离线分页、CLI 参数兼容及查询失败拒绝测试；GitHub CLI 不支持同时使用 `--slurp` 和 `--jq`，因此分页后由 Python 解析。候选 diff 的离线检查及更新后线上 PR 的 `--no-comment` 只读检查均确认 G1/G2/G6/G7 通过，G3/G4/G5 继续要求人工审查。未重新执行先前的真机会话测试，本轮没有修改 native 过滤或 UI 行为代码。

门禁回归入口：`python -m unittest discover -s tests/pr_gate -v`。Windows 需设置 `PR_GATE_BASH` 为 Git Bash 可执行文件路径以执行全部 shell 集成用例。

PR #11 随后由上游合并并发布 `1033-1.8.11-api102`，合入的功能提交为 `155d441`。CLI 分页兼容修正及 3 项 shell 集成测试在此后作为独立门禁维护变更提交，不重复发布模块。上述线上 G7 通过结果对应发布前的检查；发布后对同一功能版本重跑会因 versionCode 已使用而拒绝，这是递增规则的预期行为。
