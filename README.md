# 数字扶手｜HKU4SDG

数字扶手是一款 Android 指导应用：用户说出目标或选择任务，确认后亲手完成操作；应用只在已审核的页面圈出当前目标，并提供可折叠文字和可选语音。遇到无法确认的页面会停止圈画。

**交给用户的是一个 App、一个 APK：`guide`。** 仓库中的 `demo-target` 是早期跨应用机制的内部测试夹具，不属于用户产品，也不是使用数字扶手的前置安装条件。旧模拟练习截图只记录技术测试，不是当前产品演示。

## 目前能实际演示什么

在 Android 15 / Pixel 7 模拟器的**英文系统设置**中，用户可以从数字扶手打开“把字调大”任务，进入真实 `Display & touch` 页面。应用圈出 `Display size and text`，随后圈出 `Font size` 滑块。用户自己点击和拖动；检测到系统 `font_scale` 增大后才显示完成。离开系统设置会撤下提示；不认识的页面停止圈画。模拟器截图见 [产品界面与验证记录](docs/interface.md)。

输入语言、引导文字和语音播报语言可分别选择普通话、粤语和英语。文字输入的任务匹配目前是有限本地规则，**不是 AI 意图理解**；语音识别和播报依赖设备服务，粤语语音质量尚未验收。本次黑客松以该模拟器为演示设备；当前只适配上述模拟器系统页面，其他 Android 品牌、系统语言与版本不应视为已支持。

产品定义来自 [飞书产品文档](https://my.feishu.cn/wiki/FTF5wKcn0iNra6kG2WpcfiZ2nCg)。单 App 的信息架构与功能优先级见 [产品体验方案](docs/product-experience.md)；Apple、Android、OpenAI 和 Anthropic 官方资料与本次界面决定见 [界面研究与改版依据](docs/design-research.md)；火山引擎的语音与 AI 接入判断见 [语音选型](docs/volcengine-voice-research.md)；近期功能和比赛节点见 [下一阶段推进顺序](docs/next-sprint.md)；模拟器演示验收见 [MVP 开发计划](docs/mvp-plan.md)。

## 本地运行

需要 Android Studio、Android SDK Platform 35、Build Tools 35、Platform Tools 和 JDK 17。连接已允许 USB 调试的设备或运行模拟器后：

```bash
source scripts/dev-env.sh
./gradlew :guide:assembleDebug :guide:testDebugUnitTest :guide:lintDebug
./scripts/install-app.sh
```

在系统无障碍设置中，用户须手动开启“数字扶手 · 手机引导”。打开数字扶手，选择“把字调大”并确认任务，再进入系统设置。语音输入首次使用时另行提示由设备识别服务处理音频；拒绝麦克风后仍可用文字和任务卡。

团队如需回归早期模拟流程，可用 `scripts/install-demo.sh` 安装内部测试夹具。这个脚本不用于对外演示或用户安装说明。

## 一起协作

功能、设计、研究和文案讨论可开 Issue。认领前写清目标、预期产物和验收方式；提交 Pull Request 时附上实际验证与未验证部分。约定见 [CONTRIBUTING.md](CONTRIBUTING.md)。不要提交真实用户的录音、截图、身份资料、密钥或服务凭据。
