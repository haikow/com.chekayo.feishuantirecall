# 防对方已读 复盘：为什么一直在 .so 里碰墙，最后怎么在 Java 层破局

> 目标功能：你读了飞书消息，**对方仍显示「未读」**（桌面 Electron 版靠 JS 层清空 `updateMessagesMeRead` 的 messageIds 实现）。
> 结论：安卓端**不在 native**，在 **Java 层**。折腾了 6 种 native 手段全撞墙，换到 DEX/Java 层一击命中。

---

## 一、为什么一直碰墙：一个错误的前提，带偏了所有努力

**根因是一个想当然的前提**：桌面版是 Electron（JS），数据 SDK 在 JS 层；于是默认「安卓对应的数据 SDK 层 = native `liblark.so`（Rust+tokio）」，所有精力都砸进了 .so。

在这个错误前提下，native 层每一条路都被它的架构特性顶死：

| # | native 手段 | 撞墙原因 |
|---|---|---|
| 1 | IDA 静态分析 liblark（114MB 全分析 IDB） | 锚在 `send_success`——**它是响应/ack 回调，不是请求构造器**；且 Rust async(poll 状态机) 间接派发，callees 静态空，通用 frontier 入队(`my_649`)泛型无独立引用，静态不可达 |
| 2 | sqlite3_step hook 抓开聊天 SQL | 只有全列 SELECT / `UPDATE messages SET me_read` / REPLACE，**没有「取待标已读 id 列表」的窄 SELECT** → 已读 id 在内存 async 层、根本不过 sqlite |
| 3 | 外部 frida attach | 被字节系**反 frida** 掐断 |
| 4 | 算法助手 Pro 进程内 frida-gum 读参数 | 过了反 frida，但 `send_success`(0x5B9A318) 命中的**参数是响应侧 ASCII 日志标签，不是 messageId** |
| 5 | Stalker 前向指令追踪 | **async + 反篡改**不兼容，一开就闪退 |
| 6 | 进程内主动屏蔽 send_success + 双机实测 | 飞书没崩、照常命中，但**对方仍显示已读** → 坐实响应侧（回调触发时回执早发出，拦返回值太晚） |
| 补 | 传输 crypto 普查（找出站明文） | `SSL_write` 被剥符号；`EVP_AEAD_CTX_seal` 等 4 个 BoringSSL 原语 **24s 0 调用** → frontier 加密在 **Rust ring/rustls 内联汇编**，无标准 C 符号可挂，明文一闪即加密 |

**6+1 种手段一致收敛到同一堵墙**：Rust 内联 crypto（无符号）+ async 泛型派发（不可达）。当时甚至写进记忆「封档，别再重试」——**这个结论本身就是被错误前提污染的**。

---

## 二、为什么最后成功了：换一个「层」，而不是换一把「锤子」

转折点是一句提问：**「dex 不行吗，或者 Java 层面的？」**

关键认知纠正：**安卓 app 不是"UI(Java) + 数据(native)"的干净两层**。即使传输、加密、异步调度都在 native Rust，**协议命令对象（protobuf）通常仍在 Java/Kotlin 层组装好，再把字节交给 native 传输**。也就是说——

> 已读命令在**下沉进 native 之前**，是一个 Java 层的 protobuf 对象，**明文、可 hook**。

顺着这个思路，DEX 逆向一击命中：

1. **DEX grep 协议名**（本该第一步就做）：`PUT_READ_MESSAGES`、`UPDATE_MESSAGES_ME_READ`、`PutReadMessagesRequest` 全都能搜到——**桌面版 `updateMessagesMeRead` 的安卓同名同源命令**。
2. **定位主已读路径（在 Java）**：`com.ss.android.lark.im.sdk.service.ImSdkMessageServiceImplV2.readMessageForChannel`（混淆名 `Pm(ReadChannelMessageParam,cb)`）构造请求 → `rustClient.jk(rustclient.api.d(req, adapter, Command.UPDATE_MESSAGES_ME_READ, ...))`。请求即将下沉 native 前，在 Java 里被完整组装。
3. **稳定的 hook 点 = 生成的 pb 类**：`com.bytedance.lark.pb.im.v1.UpdateMessagesMeReadRequest`（Square Wire pb，**类名未混淆、跨版本稳定**），字段 `message_ids:List<String>` / `max_position:Integer` / `fold_ids:List<Long>`。

---

## 三、怎么解决的

- **LSPosed hook `UpdateMessagesMeReadRequest` 的构造函数**（稳定类名，`XposedBridge.hookAllConstructors`）。
- 打开真实未读聊天时，**诊断日志确认命中**：`READ_REQ message_ids=2 fold_ids=0 max_position=317`——主已读路径确实在 Java 层构造这个 pb。
- **清空 `message_ids`(args[0]) + `fold_ids`(args[7])**（忠实移植桌面「清 messageIds」的做法）。
- 开关 `Config.antiread` 控制，默认关；代码见 `AntiRecall.installAntiRead2` / `ReadReqHook`。

> ✅ **双机实测通过**：电脑端发给手机，手机端打开已读，**电脑端仍显示「未读」**。结论：**只清 `message_ids` + `fold_ids` 就够**——`max_position` 不影响已读回执（回执按 message_ids 走），无需再中和。

---

## 四、方法论沉淀（下次别再犯）

1. **别让"跨平台类比"锁死你的层次判断**。桌面在 JS 层 ≠ 安卓也在"native 数据层"。安卓的 Java/Kotlin 层往往仍负责**协议命令组装**，只把传输/加密交给 native。
2. **先 DEX/字符串侦察，再啃 native**。功能的协议名（`UPDATE_MESSAGES_ME_READ`）从一开始就可 grep。**别一上来就钻 114MB 剥符号的 Rust**——那是最贵、最容易迷路的路。
3. **锚请求构造器，不是响应回调**。`send_success` 是 ack；pb 构造函数才是"发出前"的正确拦截点。「命中 ≠ 有用」，要看参数是请求侧还是响应侧。
4. **native 是"传输"，Java 常是"组装"**。传输层加密够不到（Rust ring 无符号）不代表没救——**明文命令对象在下沉前存在于 Java**，hook 组装层即可。
5. **最稳的缝 = 生成的 protobuf 类**。pb 类名（`com.bytedance.lark.pb.*.v1.*`）不混淆、跨版本稳定，是理想 hook 点，远胜混淆过的方法名。
6. **"封档"结论要标注前提**。当年的"不可达/封档"只在"只看 native"的前提下成立；前提一换（看 Java），结论就翻了。写死结论前先问：**我是不是把某一层当成了唯一的层？**

---

*相关：`防已读-逆向结论-v7.69.6.md`（native 侧详细排查）、`防撤回-逆向方法论手册.md`（sqlite/step hook 方法论）。代码：`app/.../AntiRecall.java` 的 `installAntiRead2`。*
