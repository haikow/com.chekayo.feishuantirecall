# 飞书 asar 自动重打（systemd）

飞书**自动更新会覆盖 asar**、抹掉补丁（防撤回/防已读失效）。这套 systemd 服务在检测到 asar 被覆盖后**自动重打**，省去每次手动。

## 机制
- **`.path` 单元**：监听 `messenger-next.asar` 被修改（飞书更新时）→ 触发重打。
- **`.timer` 单元**：开机 1 分钟后 + 每 30 分钟兜底一次（catch 监听没覆盖到的场景）。
- **`autopatch.sh`**：先查补丁标记（`recalledMessageCacheList` / `t.messageIds=[],`）；**在位则跳过**，**缺失才重打**（幂等、不会空转、不会循环）。
- 以 **root** 运行，直接写 `/opt`，不依赖 sudo。

## 安装 / 卸载
```bash
sudo bash install.sh      # 部署 + 启用 + 立即跑一次
sudo bash uninstall.sh    # 移除(已打的补丁不受影响)
journalctl -u feishu-autopatch.service -f   # 看日志
```

## 关键点
- **重打的是磁盘文件**：更新后自动重打好，但**正在运行的飞书要重启**才加载新补丁（飞书更新一般自己会重启）。
- **基线自愈**：`patch_feishu.py` 会判断当前 asar 有没有补丁——干净版（更新后）就刷新 `.bak` 为新基线再打，避免拿旧基线覆盖新版把飞书搞坏。
- 重打约 40 秒（解包 60MB + 重打包），后台进行，不影响使用。
- 版本不兼容（正则失配）时脚本中止、不改原文件，日志会记录，需按方法论重新锚定。
