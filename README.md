# ATB · AntiTelemetryBlock

[![Release](https://img.shields.io/github/v/release/403bug/qufz-ng?style=flat-square)](https://github.com/403bug/qufz-ng/releases/latest)
[![LSPosed](https://img.shields.io/badge/LSPosed-API%20102-6f42c1?style=flat-square)](https://github.com/LSPosed/LSPosed)
[![Android](https://img.shields.io/badge/Android-13%2B-3ddc84?style=flat-square&logo=android&logoColor=white)]()

一个**规则驱动**的 LSPosed 模块，用于阻断 ColorOS / OPPO 手机管家「AI 反诈」的云端上报与埋点，同时保留本地诈骗提醒、弹窗、记录和检测服务。

- 模块包名：`com.eta.atb`
- 运行框架：libxposed API 102（LSPosed）
- 下载：[最新 Release](https://github.com/403bug/qufz-ng/releases/latest)
- 与 `yooma1/qufz` 的 `com.eta.afb` 相互独立，可以共存

> 本模块只做遥测阻断，不阻止本地反诈检测。它不是「反诈」工具，也不保证覆盖所有上报通道。

## 功能特性

- **双层拦截**：DCS 上传入口 + `TrackApi` 埋点出口，默认只吞反诈相关事件。
- **规则驱动**：内置默认规则，任意 App 都可以通过 `/data/local/tmp/atb-rules.json` 扩展。
- **多版本兼容**：手机管家 16.10.4 实测；保留 17.1.6 / 17.3.10 / 17.5.4 的 hook 目标。
- **可视化开关**：手机管家 / 安全事件→家人守护 / 系统 DCS / 浏览器分别控制。
- **作用域管理**：显示 5 个目标的 ✓/✗，支持一键把缺失目标加入作用域。
- **结果可验证**：每个目标输出 `status=ACTIVE/PARTIAL/INACTIVE`，不再静默失败。
- **逃生阀**：`/data/local/tmp/atb.disable` 存在时本次注入全部空转。

## 兼容性

| 项目 | 版本 |
|---|---|
| 实测系统 | ColorOS 16 / Android 16 |
| 实测设备 | 一加 12（PJD110） |
| 框架 | LSPosed 2.2.1（7912），libxposed API 102 |
| 手机管家 | 16.10.4 实测；规则兼容 17.1.6 / 17.3.10 / 17.5.4 |
| 模块要求 | Android 13+（`minSdk 33`），libxposed API 101+ |

## 安装

1. 从 [Releases](https://github.com/403bug/qufz-ng/releases/latest) 下载 `ATB-1.3.apk`。
2. 安装：

   ```bash
   adb install -r ATB-1.3.apk
   ```

3. 在 LSPosed 中启用 **「ATB 遥测阻断」**。
4. 作用域至少勾选 **手机管家**（`com.coloros.phonemanager`）。如果要启用家人守护同步规则，再勾选 **安全事件**（`com.coloros.securityguard`）和 **家人守护**（`com.coloros.familyguard`）。
5. 强停目标进程，让模块重新注入：

   ```bash
   adb shell am force-stop com.coloros.phonemanager

   # 仅在启用对应可选规则时
   adb shell am force-stop com.coloros.securityguard
   adb shell am force-stop com.coloros.familyguard
   ```

6. 查看日志：

   ```bash
   adb logcat -s ATB:*
   ```

## 使用

安装后桌面会出现 **「ATB 遥测阻断」** 设置页。所有开关通过 libxposed remote preferences 写入，**修改后必须强停对应 App 才生效**。

| 开关 / 控件 | 默认 | 作用 |
|---|---|---|
| 手机管家：AI 反诈上报 | **开** | `AiVoiceDetectDCSUtils.d/e` + `TrackApi.M/N/L/O` |
| 安全事件 → 家人守护：安全事件同步 | 关 | `k1.d#b/d/f` + `SecurityEventManager#e` |
| 系统 DCS：OPPO 通用遥测上传 | 关 | `UploadJobService` / `FileUploadService` 上传入口 |
| 浏览器：网页反诈上报 | 关 | `xt2.d#d/r`（独立于手机管家通话反诈） |
| 全部停用 | 关 | 本次注入空转 |
| 作用域状态 | 自动 | 显示 5 个目标的 ✓/✗（`XposedService.getScope()`） |
| 把缺失目标加入作用域 | 按钮 | 调用 `XposedService.requestScope()` |

> `getScope()` 只反映框架作用域，不代表功能开关已打开。默认只有手机管家一条链路生效。

### 正常日志

手机管家 16.10.4：

```text
I ATB: module loaded, api=102 framework=LSPosed
I ATB: rules source=defaults
I ATB: attach com.coloros.phonemanager
I ATB: hooked silence AiVoiceDetectDCSUtils#d(String)
I ATB: hooked silence AiVoiceDetectDCSUtils#e(Context)
I ATB: hooked sink TrackApi#M(String,String,Map)
I ATB: hooked sink TrackApi#N(String,String,JSONObject)
I ATB: hooked sink TrackApi#L(String,String)
I ATB: hooked sink TrackApi#O(String,String,JSONObject,TrackApi$d)
I ATB: summary pkg=com.coloros.phonemanager silence=2 sink=4 optionalMiss=1 status=ACTIVE
```

`optionalMiss` 是 17.x 可选规则在当前版本缺失，属于正常情况。`status=INACTIVE` 或 `NO HOOK INSTALLED` 表示规则和当前 APK 对不上，**不要认为已经生效**。

## 规则

### 内置规则

默认只处理手机管家，其他包默认不动作。模块的作用域是 `staticScope=false`，可以随时在 LSPosed 里增减目标。

| 层 | 目标 | 处理 |
|---|---|---|
| DCS 上传 | `AiVoiceDetectDCSUtils.d(String)`（updateDetectInfo）<br>`AiVoiceDetectDCSUtils.e(Context)`（uploadSwitches） | 直接短路 |
| 埋点出口 | `TrackApi.M/N/L/O` | 只吞 `appId == "20092"` 且事件名命中 `anti_fraud*` / `ai_detect*` / `ai_fraud*` / `fraud*` |
| 17.x 兼容 | `AiDetectStatistics` 全部 static void；`AiVoiceDetectDCSUtils.m/n`；`TrackApi.E` / `TrackApi.track` | 按版本自动匹配，缺失只打 INFO |

**不碰** `AiVoiceDetectDCSUtils.a/b/c`：这三个是本地记录（recordDetectTime / record 包名 / release），拦了会影响本地检测和记录。

16.10.4 的 `AiDetectStatistics` 已被混淆成 `dd.s`，默认规则不直接 hook 它，而是靠 `TrackApi` 出口和 DCS 上传两层拦截。

### 为其他 App 加规则

把规则推到 `/data/local/tmp/atb-rules.json`，再强停目标进程：

```bash
adb push my-rules.json /data/local/tmp/atb-rules.json
adb shell am force-stop <目标包名>
```

完整示例见 [`rules/example-extra-app.json`](rules/example-extra-app.json)。覆盖文件的合并规则：**同名包的规则整体替换默认规则，其它默认包保留**；文件不存在或解析失败时回退到内置默认规则。

```json
{
  "apps": {
    "com.example.app": {
      "enabled": true,
      "silence": [
        {
          "class": "com.example.app.telemetry.Uploader",
          "methods": [
            { "name": "upload", "params": ["java.lang.String", "java.util.Map"] }
          ]
        },
        {
          "class": "com.example.app.telemetry.Stats",
          "staticVoid": true,
          "exclude": ["<clinit>", "<init>"]
        }
      ],
      "sinks": [
        {
          "class": "com.example.app.track.Tracker",
          "methods": [
            { "name": "send", "params": ["java.lang.String", "java.lang.String", "java.util.Map"] }
          ],
          "appIdIndex": 0,
          "eventIndex": 1,
          "appIds": ["12345"],
          "eventPrefixes": ["ad_", "track_"],
          "eventExact": []
        }
      ]
    }
  }
}
```

| 字段 | 含义 |
|---|---|
| `silence` | 让匹配到的 **void** 方法直接返回 |
| `staticVoid: true` | 该类所有 `static void` 全部短路；用 `exclude` 排除 |
| `methods[].params` | Java 类型名精确匹配；省略表示同名所有重载 |
| `returnValue` | 非 void 方法短路时的返回值 |
| `sinks` | 拦截出口方法，读参数决定是否吞掉 |
| `appIdIndex` / `eventIndex` | appId / 事件名在参数中的下标；`-1` 表示不校验 appId |
| `appIds` | 允许的 appId 集合；空表示不按 appId 过滤 |
| `eventPrefixes` / `eventExact` | 事件名前缀 / 精确名，大小写不敏感 |
| `blockAll: true` | 不设事件过滤，命中 appId 就吞（慎用） |
| `optional: true` | 目标类/方法缺失时只打 INFO，不算失败（跨版本规则用） |

### 可选规则：家人守护同步

手机管家检测 → SecurityGuard 本地入库 → FamilyGuard 同步 → 云端上传，链路如下：

```text
手机管家检测
  → SecurityGuard SecurityEventProvider（本地入库）
  → SecurityGuard k1.d（FamilyGuardUtil）
  → FamilyGuard FamilyGuardProvider.insertSecurityEvent（security_event 表，uploadStatus=0）
  → SecurityEventManager.e()
  → SecurityEventApi.uploadSecurityEvent
  → /core/security/v1/client/upload（云）
```

| 文件 | 拦什么 | 适用 |
|---|---|---|
| [`rules/optional-securityguard.json`](rules/optional-securityguard.json) | `k1.d#b/d/f`，从源头停 | 推荐；新事件进不了家人守护 |
| [`rules/optional-familyguard.json`](rules/optional-familyguard.json) | `SecurityEventManager#e`，停上传触发 | 已入队的事件也不想上传 |
| [`rules/optional-family-sync.json`](rules/optional-family-sync.json) | 上面两个 App 的规则合并 | 两个都要时直接用这个 |

启用（三选一，推到同一个路径）：

```bash
adb push rules/optional-family-sync.json /data/local/tmp/atb-rules.json
adb shell am force-stop com.coloros.securityguard
adb shell am force-stop com.coloros.familyguard
```

> **注意**：这些规则会停掉 **所有** `type <= 8` 的安全事件向家人守护同步（诈骗、可疑应用、支付风险都包含），不只是反诈。如果你在用家人守护，不要启用。本地事件 DB 和「安全事件」页面不受影响。

### 其他 App 检查结果

| App | 包名 | 结论 |
|---|---|---|
| 手机管家 | `com.coloros.phonemanager` | 主宿主：`aivoicecalldetect` / DCS / `TrackApi`；默认规则已处理 |
| 安全事件 | `com.coloros.securityguard` | 家人守护同步源头；可选规则 `optional-securityguard.json` |
| 家人守护 | `com.coloros.familyguard` | 家人守护上传端；可选规则 `optional-familyguard.json` |
| 系统 DCS | `com.oplus.statistics.rom` | 通用遥测上传；可选开关，默认关、实验性 |
| 浏览器 | `com.heytap.browser` | 独立的网页反诈上报；可选开关，默认关 |

<details>
<summary>展开其余系统 App 的核查结论</summary>

| App | 包名 | 结论 |
|---|---|---|
| 安全中心 | `com.oplus.safecenter` | 没有 `aivoice` / `anti_fraud` / `nearx.TrackApi` 类；只有通用 `com.oplus.statistics`，不是反诈宿主 |
| 号码识别 | `com.ted.number` | 未知号码识别，含 `anti_fraud` 字符串；没有 `TrackApi` / `nearx`，不是手机管家反诈链 |
| 骚扰拦截 | `com.oplus.blacklistapp` | 骚扰/黑名单拦截 App，含 `SecurityEventProvider` 广播和通用 `TrackApi`；不是 AI 通话反诈宿主 |
| 短信 / 通话 UI / 支付保护 | `com.android.mms` / `com.android.incallui` / `com.android.server.telecom` / `com.coloros.securepay` | 会向 SecurityGuard 插入本地安全事件（`insertSecurityEvent`），没有 `TrackApi` / `nearx`；本地记录保留 |
| 国家反诈中心 | `com.hicorenational.antifraud` | 第三方官方反诈 App，有自己的上报通道，不属于 ColorOS 反诈链，默认不动 |
| 其他候选 | `com.oplus.securitypermission` / `com.coloros.remoteguardservice` / `com.darvin.security` | 未发现手机管家反诈链；`securitypermission` 只是查询 SecurityGuard provider |

</details>

全量扫描结论（2026-11-06）：385 个系统包（去掉 overlay/apex；>30MB 的大包单独核查），在已扫描范围内，除 **PhoneManager** 外没有第二个包同时包含 `aivoicecalldetect` / `AiDetectStatistics` / `AiVoiceDetectDCSUtils`。SecurityGuard → FamilyGuard 是唯一的外围安全事件同步链路；其余命中大多是通用 `TrackApi` / `nearx` / DCS 或本地 `insertSecurityEvent`，不是反诈上报。

## 验证

### 静态核对规则

需要 Android SDK 的 `apkanalyzer`：

```bash
python3 tools/verify_rules.py /path/to/PhoneManager.apk rules/default-rules.json
python3 tools/verify_rules.py /path/to/SecurityGuard.apk rules/optional-securityguard.json
python3 tools/verify_rules.py /path/to/FamilyGuard.apk rules/optional-familyguard.json
```

`verify_rules.py` 会自动读取 APK 包名，只核对这个包对应的规则。

### 上机自测

会强停并重新拉起手机管家：

```bash
./tools/selftest.sh            # 只核对装配与 status
./tools/selftest.sh --active   # 额外打开反诈页，统计 hit/blocked 行
```

## 构建

依赖：`javac(11+)`、`aapt2`、`d8`、`zipalign`、`apksigner`、`android.jar`。SDK 会自动探测，可用 `ANDROID_SDK` / `ANDROID_BUILD_TOOLS` / `ANDROID_JAR` 覆盖。

```bash
./build.sh                 # -> out/ATB-1.3.apk
./build.sh release         # -> release/ATB-1.3.apk
```

首次构建会生成 `build/atb-debug.jks`（口令 `android`），仅用于本地安装；正式分发请换自己的密钥（`ATB_KS`）。UI 的 remote preferences 依赖 `libs/service-102.0.0.aar` 和 `libs/interface-102.0.0.aar`，它们会被编译进模块 APK，不是额外 App。

自行构建会生成新的本地密钥，与 Release APK 的签名不同，不能直接覆盖安装；需要覆盖安装时请固定使用同一份 `ATB_KS`。

## 实测记录

<details>
<summary>2026-11-06 · 手机管家 16.10.4 / 家人守护</summary>

- 设备：一加 12 / PJD110 / ColorOS 16 / Android 16
- LSPosed：2.2.1 (7912)，API 102
- 手机管家 16.10.4 默认规则：`silence=2 sink=4 status=ACTIVE`
- 家人守护 + `optional-family-sync`：`SecurityEventManager#e` hooked，`status=ACTIVE`
- SecurityGuard `k1.d` 规则静态 PASS；其入口 Activity 受 `com.oppo.permission.safe.SECURITY` 保护，shell 无法拉起，运行时进程未能触发；正常收到安全事件 / 打开手机管家安全事件页时会自然触发
- 未做真实来电测试；`ai_detect_result_event` / DCS 上传需要实际通话触发
- 测试时设备上仍装有旧的 `com.eta.afb`；16.10.4 上它是 0/3 空转，17.x 上建议只保留 ATB

</details>

<details>
<summary>2026-11-07 · ATB 1.3 / UI + 作用域</summary>

- 设置页可打开，显示「LSPosed 服务已连接」
- `getScope()` 显示 5 个目标全部 ✓
- 打开「安全事件 → 家人守护」后，FamilyGuard 日志 `status=ACTIVE`
- 打开「系统 DCS」后，`UploadJobService#onStartJob/onStartCommand/b/c/f/g` + `FileUploadService#onStartCommand`，`silence=7 status=ACTIVE`
- 浏览器规则静态 PASS、作用域 ✓；`com.heytap.browser` 没有 launcher activity，运行时 hook 待浏览器实际启动时确认
- 测试后恢复默认：手机管家开，家人守护 / DCS / 浏览器关

</details>

## 边界与隐私

- 只拦 Java 层；手机管家改 native 上报或换 SDK 时，需要重新对目标。
- 只对规则里显式列出的包生效；未加规则的应用不会被碰。
- 版本升级后混淆名会变（`dd.s`、`M/N/L/O`、`d/e`），需要用 `apkanalyzer` / `dexdump` 重新核对，再更新 `rules/default-rules.json`。
- 本模块不申请 `INTERNET` 权限，不收集或上传任何数据。
- 本模块是隐私工具，不是「反诈」工具：它不阻止本地诈骗检测，也不保证所有上报通道都被覆盖。
- Releases 中的 APK 使用构建脚本生成的本地调试密钥签名；长期分发请自行签名。

## 相关项目

- [LSPosed](https://github.com/LSPosed/LSPosed) / libxposed API
- `yooma1/qufz` 的 `com.eta.afb`：另一个独立模块，可共存。16.10.4 上它是 0/3 空转，17.x 上建议只保留 ATB。
