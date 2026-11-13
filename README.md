# SFA · Wi-Fi 出站模式

> 一个专注于「按 Wi-Fi 自动切换出站模式」的 [sing-box for Android](https://github.com/SagerNet/sing-box-for-android) 分支。
> 在家直连、出门走代理，不用每天手动点开关。

[![Build](https://github.com/JiANG990/sing-box-for-android/actions/workflows/ssid-build.yaml/badge.svg)](https://github.com/JiANG990/sing-box-for-android/actions/workflows/ssid-build.yaml)

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

## 安装

从 [Releases](https://github.com/JiANG990/sing-box-for-android/releases) 下载 `arm64-v8a` APK。

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

代码改动集中在：

- `app/src/main/java/io/nekohasekai/sfa/ssid/SsidModeConfig.kt` — 把 SSID 规则注入 `route.rules` 头部，兼容注释 / 尾逗号；
- `app/src/main/java/io/nekohasekai/sfa/compose/screen/settings/SsidModeScreen.kt` — 规则编辑页；
- `app/src/github/java/io/nekohasekai/sfa/vendor/GitHubUpdateChecker.kt` — 内置更新指向本仓库。

## 构建

推送 `main` 后手动触发，或每天 04:00（Asia/Shanghai）自动检查上游：

```
gh workflow run ssid-build.yaml -R JiANG990/sing-box-for-android -f sb_tag=v1.15.0-alpha.13
```

需要仓库 Secret `LOCAL_PROPERTIES`（base64 编码的签名属性：`KEYSTORE_PASS` / `ALIAS_NAME` / `ALIAS_PASS`）。

## 许可

GPL-3.0，基于 [SagerNet/sing-box-for-android](https://github.com/SagerNet/sing-box-for-android)。
原始版权归 nekohasekai 及 sing-box 贡献者所有；本分支的修改同样以 GPL-3.0 发布。
