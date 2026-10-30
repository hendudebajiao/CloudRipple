# 第三方来源

云漾由八角维护，项目自有代码采用 **GPL-3.0-only**。第三方文件保留各自许可证；引用、参考、依赖与源码直接复制分别列出。

| 项目 | 用途 | 许可证 / 处理 |
| --- | --- | --- |
| [notification-island](https://github.com/Little-JohnKhan/notification-island) | 参考“由有权限的中转 App 持有 Live Update”的原理；当前监听、状态和 UI 为本工程实现 | MIT；保留上游许可于 `third-party/licenses/notification-island-MIT.txt` |
| [LSPosed-module-template](https://github.com/organization/LSPosed-module-template) | 现有工程模板、Gradle 与 libxposed 元数据配置的起点 | 已核对该仓库 HEAD `d02d9e8e1c5f67be7c95cade061efdf3c2267f58`：根目录未附独立 LICENSE；本地最初 ZIP 的精确提交未记录，发布前仍需核对模板许可，GPL 不能替代上游授权 |
| [libxposed/api](https://github.com/libxposed/api) | 编译期 API 102；运行时由 LSPosed 提供 | Apache-2.0；Maven `compileOnly`，未打包上游源码或 AAR |
| [Gradle](https://github.com/gradle/gradle) | Wrapper 脚本和 `gradle-wrapper.jar` | Apache-2.0；保留脚本原始版权头 |
| [AndroidX Annotation](https://developer.android.com/jetpack/androidx/releases/annotation) | 编译期注解 | Apache-2.0；`compileOnly` |
| [miuix](https://github.com/miuix-kotlin-multiplatform/miuix) | 参考圆角卡片、色彩层次和胶囊导航的视觉语言 | Apache-2.0；本工程以 Java 原生 View 实现，未引入 Compose/miuix 库 |
| [KernelSU](https://github.com/tiann/KernelSU) | Root 授权运行环境；开发时参考悬浮底栏交互 | GPL-3.0；未复制或分发 KernelSU 管理器及源码 |

Apache-2.0 完整文本保存在 `third-party/licenses/Apache-2.0.txt`。GPL-3.0 完整文本位于根目录 `LICENSE`。

应用图标来自作者提供的云朵铃铛 PNG。发布者需确认其原创或拥有再分发许可；现有记录无法证明图片的创作来源。图片没有独立的第三方授权证明文件。

此文件记录当前可核实的来源，不代表对缺失许可内容的推定授权。
