# Bifrost · Wi-Fi 出站模式

> Bifrost（彩虹桥）—— 基于 [sing-box](https://github.com/SagerNet/sing-box) 开发的 Android 客户端，专注于「按 Wi-Fi 自动切换出站模式与 DNS」。
> 在家直连、出门走代理，不用每天手动点开关。

[![Build](https://github.com/JiANG990/Bifrost/actions/workflows/ssid-build.yaml/badge.svg)](https://github.com/JiANG990/Bifrost/actions/workflows/ssid-build.yaml)

## 为什么

大多数代理客户端只有一个全局出站模式。回家切「直连」、出门切「规则」，全靠手动。
这个分支把这一步交给 Wi-Fi：

- 连上你指定的 Wi-Fi（比如家里的 Wi-Fi）→ 自动切到「直连」，局域网设备、NAS、投屏、mDNS 全部按本地网络走；
- 离开 / 断开 Wi-Fi → 自动回到配置里的「规则」模式，Tailscale、WireGuard 等出口照常工作；
- 切换是运行时热更新，不重建 VPN、不断流。

## 功能

- **按 SSID 切换出站模式**：每条规则可选「规则 / 全局 / 直连」，默认模式同样可配；UI 内增删改，不写死配置。
- **局域网友好**：组播、广播不进 tun，飞牛 mesh、DLNA、mDNS 的设备发现不再失踪。
- **原生 sing-box 能力**：WireGuard、Tailscale 都是一等 endpoint，配置与官方完全兼容。
- **前台服务常驻**：VPN 运行期间由系统前台服务托管，后台不被冻结，按需运行由客户端自己判断。
- **跟随上游**：每天检查 sing-box 新版本，自动编译并发布；App 内置更新指向本仓库。
- **MCP 控制接口**：内置一个只监听 `127.0.0.1` 的 MCP 服务器，AI 客户端可以读运行状态、连接、日志、配置，也能切换节点、关闭连接、切换配置。

## 安装

从 [Releases](https://github.com/JiANG990/Bifrost/releases) 下载 `arm64-v8a` APK。

> 本分支使用独立签名，与官方 SFA 不兼容：安装前请先导出配置并卸载官方版，否则会提示签名冲突。卸载会清空 App 数据。

首次使用：

1. 导入你的 sing-box 配置（JSON）；
2. 设置 →「按 Wi-Fi 切换出站模式」→ 启用，添加 `你的WiFi=直连`；
3. 确认配置里有 `inbounds` 的 tun 段（否则 App 只跑本地代理，不建 VPN）。

## 按 Wi-Fi 切换出站模式：写法

入口：**设置 → 按 Wi-Fi 切换出站模式**。每条规则一行：`SSID=模式`。

模式只有三个：`rule`（按配置规则）、`global`（全部走「🚀 出国」）、`direct`（全部直连）。
未匹配的 SSID 和蜂窝网络使用页面顶部的「默认出站模式」。

脱敏示例（只展示 SSID 相关部分，不含节点、密钥等真实信息）：

```json
{
  "route": {
    "rules": [
      { "wifi_ssid": ["家里WiFi"], "action": "direct" },
      { "wifi_ssid": ["办公室WiFi"], "action": "route", "outbound": "🚀 出国" }
    ]
  }
}
```

对应 UI 里就是两条：

```
家里WiFi=direct
办公室WiFi=global
```

这些规则会在服务启动 / 重载时插到 `route.rules` 最前面，所以优先于配置里的其它规则；`direct` 不再匹配后续规则。保存后热生效，不重建 VPN。

注意：Android 读 SSID 需要**位置权限**，并保持系统定位开启；同名 2.4G/5G 视为同一个 SSID。注入失败时编辑页会红字提示原因。

## 与官方版的差异

| | 官方 SFA | 本分支 |
|---|---|---|
| 按 Wi-Fi 切模式 | — | ✅ |
| 局域网组播直通 | 需手动排除路由 | ✅ 默认排除 |
| 更新来源 | SagerNet/sing-box | 本仓库 Releases |
| 签名 | 官方 | 独立（不兼容） |
| MCP 控制接口 | — | ✅ 见下文 |

代码改动集中在：

- `app/src/main/java/io/nekohasekai/sfa/ssid/SsidModeConfig.kt` — 把 SSID 规则注入 `route.rules` 头部，兼容注释 / 尾逗号；
- `app/src/main/java/io/nekohasekai/sfa/compose/screen/settings/SsidModeScreen.kt` — 规则编辑页；
- `app/src/github/java/io/nekohasekai/sfa/vendor/GitHubUpdateChecker.kt` — 内置更新指向本仓库。
- `app/src/main/java/io/nekohasekai/sfa/mcp/` — MCP 服务器、状态缓存与工具实现。

## MCP 服务器

入口：**设置 → 应用 → MCP 服务器**。打开开关后，App 在 `127.0.0.1:18080`（端口可改）上提供 JSON-RPC 2.0 端点，供本机的 AI 客户端接入。

- 只绑定回环地址，局域网和外部设备连不上；
- 设置里可填访问令牌，填了就要求 `Authorization: Bearer <token>`，留空则不校验；
- 协议为 MCP `2024-11-05`，传输层是简化的 Streamable HTTP：只处理 `POST`（`/mcp` 或 `/`），不提供 SSE 流；`/health` 返回存活与版本。

可用的工具：

| 工具 | 作用 |
|---|---|
| `status` | 连接状态、核心版本、实时上下行、clash 模式、当前配置 |
| `outbounds` | 所有出站组与成员、最近一次测速延迟 |
| `connections` | 活动连接（目标、命中规则、出站链） |
| `logs` | 最近的核心日志（可按级别过滤，可用 `grep` 按关键词筛选） |
| `profiles` / `get_profile` | 配置列表 / 读取某个配置的 JSON |
| `runtime_config` | 实际交给内核的配置（含 SSID 与网关 DNS 注入） |
| `update_profile` | 写入配置内容、校验 JSON，可选立即重载内核 |
| `device` | 应用、设备、内核与 MCP 服务信息 |
| `dns_query` / `probe` | 解析域名（返回地址与耗时）/ 测试 TCP 连通与延迟 |
| `ssid_mode` | 读取或改写 SSID 映射与网关 DNS 开关 |
| `update_state` | 应用内更新状态：最新版本、缓存的 APK、安装结果 |
| `select_outbound` | 在组内切换节点 |
| `close_connection` / `close_all_connections` | 关闭连接 |
| `set_clash_mode` | 切换 rule / global / direct |
| `url_test` | 触发测速 |
| `service` | 启动、停止、重启内核 |
| `set_profile` | 切换当前配置（之后需重启服务生效） |

客户端配置示例（以支持 HTTP 传输的客户端为例）：

```json
{
  "mcpServers": {
    "sfa": {
      "type": "http",
      "url": "http://127.0.0.1:18080/mcp",
      "headers": { "Authorization": "Bearer 你的令牌" }
    }
  }
}
```

注意：MCP 服务器运行在手机 App 进程内，客户端必须跑在同一台设备上（或通过 `adb forward tcp:18080 tcp:18080` 转发）。

## 构建

推送 `main` 后手动触发，或每天 04:00（Asia/Shanghai）自动检查上游：

```
gh workflow run ssid-build.yaml -R JiANG990/Bifrost -f sb_tag=v1.15.0-alpha.13
```

Release 使用固定签名。构建前需在仓库 **Settings → Secrets and variables → Actions** 中配置：

| Secret | 内容 |
|---|---|
| `KEYSTORE_B64` | `release.keystore` 文件的 Base64 |
| `LOCAL_PROPERTIES` | 下方文本的 Base64 |

```
KEYSTORE_PASS=钥匙库密码
ALIAS_NAME=key alias
ALIAS_PASS=key 密码
```

fork 后请替换为自己的 keystore 与密码；沿用他人签名签出的 APK 无法覆盖安装，也无法通过系统校验。仓库与构建产物中不包含私钥。

## 许可

GPL-3.0，基于 [SagerNet/sing-box-for-android](https://github.com/SagerNet/sing-box-for-android)。
原始版权归 nekohasekai 及 sing-box 贡献者所有；本分支的修改同样以 GPL-3.0 发布。
