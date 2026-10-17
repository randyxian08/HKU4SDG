# 数字扶手｜开发接手指南

更新于 2026-10-09。本文帮助协作者构建、运行和检查原生 Android MVP。用户只安装 `guide`；`demo-target` 和 `androidTest` APK 都是开发工具。0.1.1 手机实验路径见[手机测试指南](phone-testing.md)；当前 0.1.2 的行为和验收见[引导优化记录](guide-reliability-2026-10-09.md)。

## 1. 获取当前实现

```bash
git clone https://github.com/randyxian08/HKU4SDG.git
cd HKU4SDG
git switch main
```

已有 checkout 时先检查 `git status`，保留自己的未提交工作，再切换到 `main` 并执行 `git pull --ff-only origin main`。当前首页、首次设置与浮窗整合说明见 [PR #11](https://github.com/randyxian08/HKU4SDG/pull/11)；四项任务基线见 [PR #6](https://github.com/randyxian08/HKU4SDG/pull/6)。认领和审阅约定见 [CONTRIBUTING.md](../CONTRIBUTING.md)。

## 2. 环境与演示条件

| 项目 | 当前要求 |
|---|---|
| Java | JDK 17 |
| Android SDK | Platform 35、Build Tools 35、Platform Tools |
| 构建 | 仓库自带 Gradle Wrapper；Android Studio 可用于导入和运行模拟器 |
| 运行目录 | 所有命令在仓库根目录执行 |
| 已审核演示准入 | API 35、`sdk_gphone64_arm64`、`ranchu` 或 `goldfish` 硬件、英文系统 |
| 字体／亮度手机实验准入 | API 26–36、中文或英文系统、系统设置应用已启用；不限制手机型号 |
| 第三方应用 | Chrome `124.0.6367.219`、Maps `11.129.0103` |
| 可选云端代理 | Python、[requirements-voice.txt](../scripts/requirements-voice.txt)、本机开发密钥 |

`minSdk = 26` 表示安装下限。字体／亮度手机实验路径还会逐页核对唯一语义控件，并在未知页面撤圈；准入通过不等于设备验收。Chrome、Maps 继续限定原演示环境。新增设备或应用版本时，需要保存实际页面与失败行为证据，不能只放宽版本判断。

```bash
source scripts/dev-env.sh
java -version
adb devices
```

`dev-env.sh` 默认读取 macOS 的 `$HOME/Library/Android/sdk` 和 JDK 17。其他系统须先把 `ANDROID_HOME`、`JAVA_HOME` 设置为本机已安装位置。`adb devices` 应显示一个状态为 `device` 的目标模拟器；多个目标时先明确操作设备。

## 3. 构建、安装和开始任务

```bash
./gradlew :guide:assembleDebug :guide:testDebugUnitTest :guide:lintDebug
./scripts/install-app.sh
adb shell am start -n org.hku4sdg.handrail/.MainActivity
```

`install-app.sh` 会再次确保 Debug APK 已构建，然后安装 `guide`，不会安装内部夹具。主要产物：

- Debug：`guide/build/outputs/apk/debug/guide-debug.apk`。
- 未签名 Release：`guide/build/outputs/apk/release/guide-release-unsigned.apk`；构建成功不表示已签名、上架或发布。
- 开发检查：`guide/build/outputs/apk/androidTest/debug/guide-debug-androidTest.apk`。

首次使用按以下顺序操作：

1. 完成首次语言与字号选择。首页输入／说出需求，再确认目标；也可以直接选择下方任务快捷入口和帮助方式。
2. 阅读权限用途，进入系统无障碍设置，亲手开启“数字扶手 · 手机引导”。
3. 返回原任务，确认开始；权限开启不会替用户自动操作。
4. 在目标应用中点击／拖动圈出的控件。暂停、未知页或离开任务后，恢复时重新核对页面。
5. 结果出现后检查并确认；未确认退出不记为完成。回执提供返回数字扶手和留在当前页面。

字体任务须从未达上限的字号开始。亮度先选择手动或自动。手动路径若遇自动亮度，会先引导用户关闭，再记录基线、调滑块；起始值应低于上限。自动分支只接受唯一可核对的开关。本次模拟器没有自动亮度功能，禁用该选择；合成测试不能代替自动开关的设备验收。第三方应用必须符合目录中的完整版本；加载时可能先显示页面未识别，已审核页面准备好后会重新检查。

四项演示的场景和失败退路见[可演示案例](demo-cases.md)。

## 4. 代码入口与模块关系

| 位置 | 负责什么 |
|---|---|
| [FirstRun.kt](../guide/src/main/java/org/hku4sdg/handrail/FirstRun.kt) | 首次语言、应用内字号、权限说明与圈画练习；三项独立语言设置 |
| [MainActivity.kt](../guide/src/main/java/org/hku4sdg/handrail/MainActivity.kt) | 首页、任务确认、权限引导、语言设置、语音输入与练习记录 |
| [TaskCatalog.kt](../guide/src/main/java/org/hku4sdg/handrail/TaskCatalog.kt)、[TaskAvailability.kt](../guide/src/main/java/org/hku4sdg/handrail/TaskAvailability.kt) | 已审核任务及设备、语言、安装状态、完整版本准入 |
| [GuideAccessibilityService.kt](../guide/src/main/java/org/hku4sdg/handrail/GuideAccessibilityService.kt) | 实时页面／控件核对、结果检测、中断处理与播报触发 |
| [PhoneSettingsMatcher.kt](../guide/src/main/java/org/hku4sdg/handrail/PhoneSettingsMatcher.kt)、[PhoneGuideCopy.kt](../guide/src/main/java/org/hku4sdg/handrail/PhoneGuideCopy.kt) | 手机字体／亮度的唯一控件识别、实验说明、未知页退路和适配信息标签白名单 |
| [GuideOverlay.kt](../guide/src/main/java/org/hku4sdg/handrail/GuideOverlay.kt)、[OverlayPlacement.kt](../guide/src/main/java/org/hku4sdg/handrail/OverlayPlacement.kt) | 不接收触控的圈画、可操作卡片、收起与固定操作、目标避让 |
| [GuideContent.kt](../guide/src/main/java/org/hku4sdg/handrail/GuideContent.kt)、[SessionState.kt](../guide/src/main/java/org/hku4sdg/handrail/SessionState.kt)、[Practice.kt](../guide/src/main/java/org/hku4sdg/handrail/Practice.kt) | 固定三语步骤、偏好、活动／暂停状态与本机记录 |
| [ServiceDiagnostics.kt](../guide/src/main/java/org/hku4sdg/handrail/ServiceDiagnostics.kt)、[BrightnessFlow.kt](../guide/src/main/java/org/hku4sdg/handrail/BrightnessFlow.kt) | 有界本机生命周期诊断、权限状态、构建标识；亮度目标分支 |
| [Design.kt](../guide/src/main/java/org/hku4sdg/handrail/Design.kt)、`*Copy.kt` | 视觉样式及页面／恢复／帮助文案 |
| [IntentClient.kt](../guide/src/main/java/org/hku4sdg/handrail/IntentClient.kt)、[CloudAsr.kt](../guide/src/main/java/org/hku4sdg/handrail/CloudAsr.kt)、[CloudTts.kt](../guide/src/main/java/org/hku4sdg/handrail/CloudTts.kt) | Debug 模拟器到宿主机的意图、识别和合成请求 |
| [voice_proxy.py](../scripts/voice_proxy.py)、[asr_client.py](../scripts/asr_client.py) | 宿主机调用方舟／火山接口、返回校验及演示请求上限 |
| `guide/src/test/`、`guide/src/androidTest/` | 纯逻辑测试与模拟器平台操作检查 |
| `demo-target/` | 内部受控跨应用／安全测试夹具 |

意图模型只选择任务、澄清或拒绝；Android 端再校验允许列表。已审核页面、圈画位置和结果条件由本地逻辑决定。变化后的坐标不可复用，未知页面不能由模型补造步骤。

## 5. 可选：云端 AI 与三语语音

0.1.3 的推荐启动、真实回归和比赛演示口径见 [AI 与三语语音](ai-voice-demo-2026-10-17.md)。已有本机 `.env` 和 Python 依赖时，可直接运行 `.venv/bin/python scripts/start-ai-demo.py`；它读取配置而不执行 shell 内容。`.venv/bin/python scripts/check-ai-demo.py` 只检查配置，增加 `--live` 才调用真实供应商并可能产生用量。三语合成回归还需 `ffmpeg`。

不配置云端服务也能使用任务卡和跨应用引导。云端桥接只对 Debug 模拟器启用：App 请求 `10.0.2.2:8765`，宿主机代理仅监听 `127.0.0.1:8765`。

没有 `.env` 时先复制模板；已有 `.env` 时跳过复制并直接编辑，避免覆盖。用本机编辑器填写开发密钥，不要放入命令行、APK、文档或 Git：

```bash
cp .env.example .env
python3 -m venv .venv
.venv/bin/pip install -r scripts/requirements-voice.txt
```

`.env` 的两个字段各有用途：`VOLCENGINE_API_KEY` 用于已开通的 TTS/ASR，`ARK_API_KEY` 是仅允许方舟 Mini 模型的独立密钥。保持 `.env` 被 Git 忽略。

在独立终端加载本机配置并运行：

```bash
set -a
source .env
set +a
.venv/bin/python scripts/voice_proxy.py
```

本机 Homebrew Python 若出现证书验证错误，使用已验证的系统证书库启动：

```bash
SSL_CERT_FILE=/etc/ssl/cert.pem .venv/bin/python scripts/voice_proxy.py
```

在另一终端检查：

```bash
curl http://127.0.0.1:8765/health
```

`ready`、`intent_ready`、`asr_ready` 表示对应配置／依赖是否具备，不证明云端额度、网络和每次请求都可用。TTS 接入使用 Vivi 2.0，意图模型为 `doubao-seed-2-0-mini-260428`。

| 请求 | 发送什么 | 每次代理启动的限制 |
|---|---|---|
| TTS | 当前固定引导文字与播报语言 | 最多 100 次、合计 5,000 字符 |
| 意图 | 用户主动提交的目标文字与输入语言 | 最多 60 次，每条最多 160 字 |
| ASR | 用户主动录制的 PCM 与输入语言 | 最多 30 次，每段最多 8 秒 |

识别结果先放入可编辑输入框，用户再主动提交目标。意图接口不接收截图、页面文字或无障碍树；这些配置不是正式云端部署，也不替代控制台账单和服务额度。真实语音音质与识别效果仍需另行验收。

## 6. 运行哪些检查

### 本地构建与纯逻辑检查

涉及代码时，至少满足 [CONTRIBUTING.md](../CONTRIBUTING.md) 的要求：

```bash
source scripts/dev-env.sh
./gradlew :guide:assembleDebug :demo-target:assembleDebug :guide:testDebugUnitTest
```

完整 CI 对应命令：

```bash
./gradlew :guide:assembleDebug :guide:assembleRelease :guide:assembleDebugAndroidTest :demo-target:assembleDebug :guide:testDebugUnitTest :guide:lintDebug :demo-target:lintDebug
python3 -m unittest discover -s scripts -p 'test_voice_proxy.py'
```

CI 配置见 [android.yml](../.github/workflows/android.yml)。代理单元测试使用模拟接口响应，不代表真实语音服务通过。Kotlin 报告位于 `guide/build/reports/tests/testDebugUnitTest/`，lint 报告位于 `guide/build/reports/lint-results-debug.html`。

仅改动 Markdown 文件或 `docs/` 下的文档、截图和验证记录时，push 与 pull request 不触发 Android 构建。源码、Gradle、代理脚本及工作流改动仍触发构建；同一工作流和引用上的新运行会取消旧运行。需要检查纯文档修订时，可在 GitHub Actions 中手动运行 `Android build`。

### 模拟器界面与浮窗

平台操作检查与 CI 构建分开运行。先结束活动引导，安装 Debug 与开发检查 APK；保持数字扶手服务启用。当前证据与条件见[整合记录](ui-integration-2026-10-09.md)。完整页面的焦点／角色检查需要先启用 TalkBack。浮窗检查使用同一 runner：

```bash
adb install -r guide/build/outputs/apk/androidTest/debug/guide-debug-androidTest.apk
python3 scripts/run-device-check.py reliability
python3 scripts/run-device-check.py integration
python3 scripts/run-device-check.py overlay
python3 scripts/run-device-check.py overlay --task brightness
# 真实中文系统页面：
python3 scripts/run-device-check.py phone
# 先在系统设置启用 TalkBack，再检查平台角色与焦点：
python3 scripts/run-device-check.py a11y
```

脚本仅允许模拟器，要求数字扶手原本已启用。Instrumentation 启动会结束旧目标进程；收到等待状态及正常结束后，脚本只重绑数字扶手，保留其他已启用服务。用户中途关闭权限时不重新开启；检查被打断时须先核对设备上的运行状态，再清理或重试。它核对结果正文 `PASS`、无 `FAIL` 及进程退出状态，不能只以 `adb` 退出码判断通过。应用本身不使用此操作恢复权限。`integration`、`overlay`、`reliability` 使用英文系统；`phone` 使用真实中文系统页面。多个目标用 `--serial` 指定。

1.0 字号运行字体任务；2.0 字号须使用亮度任务，字体任务在该比例已达准入上限。用 `adb shell settings put system font_scale 1.0` 或 `2.0` 设置，结束后恢复演示字号。检查会恢复原偏好与练习记录；改变系统值的检查恢复起始数值。结束测试进程后，服务可能需要再次绑定；这不是正常使用中的应用崩溃。原生接口检查不等于完整 TalkBack 手势／朗读顺序或目标用户试用。

### 意图对照

```bash
python3 scripts/evaluate_intent.py
```

默认只跑当前 51 条合成句的本地退路。`--live` 会使用已配置的 `ARK_API_KEY` 直接调用真实模型，可能产生用量；它不经过本机代理的请求计数。已有逐条结果和限制见[意图验收](intent-evaluation.md)，不能把开发集当成盲测或真人准确率。

## 7. 常见问题

| 现象 | 先检查什么 | 怎样继续 |
|---|---|---|
| 找不到 `adb` 或 Java 版本不符 | SDK 路径、JDK 17、是否 source `dev-env.sh` | 修正本机环境后再构建 |
| 首页显示任务不可用 | 系统／语言、应用是否启用、完整版本 | 使用已审核环境；不要为演示放宽目录判断 |
| 无障碍首次开关不可用 | 开启页的“无法开启？查看帮助”、安装来源及系统实际提示 | 由用户进入应用信息，按系统菜单决定是否解除限制，再返回开启 |
| 服务似乎自行关闭 | 设置 → 服务状态与诊断的权限、连接与任务三项状态及构建版本 | 主动复制本机诊断，区分权限关闭、解绑、进程退出和任务暂停；根因未证实前不自动归因 |
| 浮窗没有出现 | 服务是否绑定、是否暂停、当前页面是否可核对 | 回到任务，明确继续；重新识别当前页面 |
| 云端接口不可用 | `/health`、配置／依赖、代理终端及实际网络 | 使用文字或任务卡；TTS 不可用时保留设备播报退路 |
| “页面未识别” | 是否仍在加载，是否进入指定网页／地点 | 已审核页准备好后重新核对；无法确认时撤圈 |
| 浮窗检查 FAIL | 活动任务是否结束、服务是否在启动后重绑、字号／任务条件 | 保留失败日志，修正对应条件后只重跑受影响检查 |

纯文档修改核对事实、相对链接和 `git diff --check`；不以重跑构建代替文档核对。提交截图或日志时标明设备、版本、语言和任务，并排除密钥、真实录音及私人页面。
