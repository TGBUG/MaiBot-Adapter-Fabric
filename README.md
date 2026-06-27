# MaiBot-Adapter-Fabric

对接项目[MaiBot-MineCraft-Adapter](https://github.com/TGBUG/MaiBot-MineCraft-Adapter)的Fabric实现

在配置文件中可设置心跳超时与鉴权Token

| 命令                         | 权限需求     | 用途                                 |
|----------------------------|----------|------------------------------------|
| `/maia status`              | 所有人      | 显示 WebSocket 端口和已连接的客户端数量          |
| `/maia config <key>`        | 管理员(等级2) | 读取并显示配置值                           |
| `/maia config <key> <value>` | 管理员(等级2) | 设置并保存配置值                           |
| `/maia reload`               | 管理员(等级2) | 重新加载配置，停止旧的 WS 服务器，并使用新设置启动一个新的服务器 |

关于配置设置的备注：

 - 端口 和 心跳超时 需要 /maia reload 才能生效（它们在 WebSocket 服务器构建时被读取）
 - authToken 会立即对新的 WebSocket 连接生效，无需重启
 - 你可以使用 /maia config authToken clear（或 none，或 ""）来禁用认证

# TODO：
- [x] 适配客户端