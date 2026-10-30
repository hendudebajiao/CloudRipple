# 云漾 · Yunyang

<p align="center"><img src="app/src/main/res/drawable-nodpi/ic_cloud_art.png" width="112" alt="云漾图标" /></p>
<p align="center">让重要消息，轻轻浮现</p>

面向 **Android 17 / ColorOS 17** 的通知流体云工具与 LSPosed 模块。作者：[八角](https://github.com/hendudebajiao) · [酷安](https://www.coolapk.com/u/37544079)。当前版本 **1.8.2（16）**。

勾选来源应用后，云漾通过通知监听读取消息，再以自身身份发布 Live Update，由 ColorOS 原生流体云呈现。配置界面、通知监测和通知展示分工独立；Root 保活直接通过 APK 请求 KernelSU 授权，无须另刷辅助 ZIP。

本模块使用ChatGPT6.1Sol完成，AI使用提示，介意勿用

## 功能

- 搜索并选择允许上岛的应用，列表保留未显示项的勾选状态，点击“保存选择”生效。
- 最新通知占用一个岛位，当前下岛或失效后恢复上一条未抑制、未到期的有效通知。
- 手动下岛、到期或打开来源后隐藏转发；同一通知后续更新保持隐藏，原通知移除后重新发布才可再次上岛。
- 设置停留时长，默认 15 秒；0 为不限时，范围 0～86400 秒。同一通知更新不会刷新期限，手动修改时长会重置现有期限。
- QQ / 微信左侧优先显示消息发送者，其他应用显示应用名。来源图标优先级为通知大图标 / 头像 → 通知小图标 → 应用图标。
- 首页快捷操作可开关流体云推送，关闭时保留通知监测和 Root 保活。
- 收起岛右侧正文超出五字宽可滚动，展开正文正常显示；可关闭滚动或展开面板中的手动下岛按钮。
- 浅蓝色 miuix 风格卡片界面与支持点击、拖动的悬浮底栏，以原生 Java View 实现。
- 首次进入尝试 Root 授权并保存尝试状态；已有授权启用状态的安装不重复初始化。设置页提供“启用 / 刷新 Root 保活”和“停止 Root 保活”，可手动重试授权或停止看护。

## 环境与安装

已主要在 **PKJ110 / ColorOS 17.0.0.104 / Android 17 / KernelSU / LSPosed 2.2.1** 上调试。项目 `minSdk=29` 仅表示可安装下限，不代表旧 Android 具备本项目所需流体云能力。

1. 安装 APK，在 LSPosed 中启用云漾并选择 **系统界面 `com.android.systemui`**。重启 SystemUI 或设备后加载 Hook。
2. 首次进入按 KernelSU 提示授权。通知访问、通知权限及 Live Update 仍需在系统设置开启。
3. 在“应用”页勾选来源并保存，在“设置”页调整时长和显示选项。
4. 用首页“测试上岛”验证系统呈现能力，再使用真实来源通知验证转发。

基础通知转发使用公开 API；来源名称、图标和右侧滚动增强依赖 SystemUI Hook 及当前 ColorOS 视图结构。关闭这些权限或 ROM 更新后，行为可能变化。撤回 Root 授权请在 KernelSU 管理器操作；首次授权拒绝后不在每次启动自动重试，详见 [使用与维护](docs/USAGE.md)。

## 构建

使用 **JDK 21、Android SDK Platform 37、Build Tools 37.0.0、Gradle Wrapper 9.4.1、AGP 9.2.1**。首次构建需要联网下载 SDK 和 Maven/Gradle 依赖。

Android Studio 打开仓库根目录；也可以设置 `ANDROID_HOME` 指向 SDK 后执行：

```powershell
# Windows
.\gradlew.bat :app:assembleDebug :app:assembleRelease
```

```bash
# Linux / macOS
bash gradlew :app:assembleDebug :app:assembleRelease
```

也可在本地 `local.properties` 写 `sdk.dir=/your/android/sdk`，此文件不要提交。

- Debug：`app/build/outputs/apk/debug/app-debug.apk`（开发签名）。
- Release：`app/build/outputs/apk/release/app-release-unsigned.apk`（当前未配置发布签名）。
- libxposed API 通过 Maven `compileOnly("io.github.libxposed:api:102.0.0")` 获取，由 LSPosed 运行时提供；**不需要 libxposed 子模块**。
- [GitHub Actions](.github/workflows/build.yml) 构建并上传产物，不自动发布、不持有签名密钥；真机适配仍需人工验证。

发布正式 APK 时自行使用固定且妥善保管的证书签名；不同 Debug 环境的证书可能不同，不能保证覆盖安装。

## 源码导航

```text
app/
  src/main/java/com/bajiao/notificationisland/
    MainActivity.java             首页、应用选择、设置
    CloudUi.java                  浅蓝主题与悬浮导航交互
    IslandRuntime*.java           配置页到 monitor 的私有 IPC
    IslandNotificationListener.java  来源通知入口
    IslandState.java              单槽位通知栈、抑制集合、图标/名称
    IslandForegroundService.java  按需发布/取消 Live Update
    IslandLifetime.java           到期时间持久化
    IslandOpenActivity.java       打开原通知前先下岛
    IslandOpenedSources.java      识别直接进入来源应用
    RootKeepaliveManager.java     APK 内授权、部署与恢复
    MainModule.java               LSPosed 入口
    IslandMarqueeHook.java        收起岛右侧滚动及左侧内容绑定
  src/main/assets/watchdog-v4.sh   Root 看护脚本
  src/main/resources/META-INF/xposed/  模块入口与作用域
  src/androidTest/                独立测试来源与回归执行器
  src/debug/                      仅开发构建的诊断组件
```

完整说明：[设计思路与流程](docs/ARCHITECTURE.md) · [使用与维护](docs/USAGE.md) · [开源发布步骤与文件清单](docs/PUBLISHING.md) · [隐私说明](PRIVACY.md) · [第三方来源](THIRD_PARTY_NOTICES.md)。

## 测试

```bash
bash gradlew :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e service true com.bajiao.notificationisland.test/com.bajiao.notificationisland.IslandRegressionTest
```

执行器覆盖模型、时长、下岛和实际通知设置等行为；不代表不同 ColorOS 版本的视觉与后台策略均已验证。Instrumentation 会结束目标进程并可能被厂商后台管理中断，只在开发设备执行。

## 许可证

项目自有代码采用 **GNU GPL v3 only（GPL-3.0-only）**，见 [LICENSE](LICENSE)。分发修改版或二进制时应按 GPL 提供对应源码与许可。第三方内容保留原许可证，见 [NOTICE](NOTICE) 与 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

版本变化见 [更新日志](CHANGELOG.md)；测试范围与限制见 [验证记录](docs/VALIDATION.md)。
