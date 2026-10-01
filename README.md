# 数字扶手｜HKU4SDG

数字扶手是一款 Android 指导应用：用户说出目标或选择任务，确认后亲手完成操作；应用只在已审核的页面圈出当前目标，并提供可折叠文字和可选语音。遇到无法确认的页面会停止圈画。

**交给用户的是一个 App、一个 APK：`guide`。** 仓库中的 `demo-target` 是早期跨应用机制的内部测试夹具，不属于用户产品，也不是使用数字扶手的前置安装条件。旧模拟练习截图只记录技术测试，不是当前产品演示。

## 目前能实际演示什么

在 Android 15 / Pixel 7 模拟器上有四项真实任务：两项英文系统设置任务“把字调大”“调高屏幕亮度”，在 Chrome 124 收藏 [GovHK 长者服务公开网页](https://www.gov.hk/tc/residents/housing/socialservices/elderly/index.htm)，以及在 Google Maps 11.129 查看[香港中央图书馆](https://www.hkpl.gov.hk/tc/locations/central-library/central.html)地址。前两项分别以系统 `font_scale`、`SCREEN_BRIGHTNESS` 的实际增加检测结果；Chrome 任务以显示 `Edit bookmark` 检测收藏结果；Maps 任务以指定地点的地址行出现检测展示结果，之后均等待用户确认，不提供路线导航。离开已识别页面会撤下圈画；不认识的页面停止指引。模拟器截图见 [产品界面与验证记录](docs/interface.md)。

2026-10-02 已补齐完成后的闭环：检测到结果后等待用户亲自确认，字体和亮度可继续调整；开始前可选完整指引、轻提示或自己先试试。按需模式通过“给我提示”恢复当前一步，换步后重新由用户决定；本机保留最近 20 次可删除的练习记录，区分确认完成与未确认退出。大字体把设置目标挤出视口时，会先提示用户滚动，控件可见后再圈画。验收范围和下一轮产品选择见[产品质量迭代](docs/product-quality.md)。

输入语言、引导文字与 AI 提示、语音播报语言可分别选择普通话、粤语和英语；首页及部分操作控件仍以中文显示。Debug 模拟器版已加入**受约束的真实 AI 意图识别**：用户主动提交的目标文字只可映射到上述四项已审核任务、请求澄清或拒绝；模型不决定跨应用位置和步骤。方舟 Mini 模型在 [44 条固定三语样本](docs/intent-evaluation.md)上为 44/44；这不是面向真实用户的准确率。服务不可用时回退到保守的本地匹配。语音输入的 Debug 模拟器路径已接入火山引擎 ASR，三语合成音频均返回转写；模拟器自身麦克风的真人三语识别与粤语音质尚未验收。本次黑客松以该模拟器为演示设备；当前只适配上述模拟器的已测页面，不应视为任意第三方 App、其他 Android 品牌或版本均可用。

产品定义来自 [飞书产品文档](https://my.feishu.cn/wiki/FTF5wKcn0iNra6kG2WpcfiZ2nCg)。四项可讲述的用户场景及失败路径见[可演示案例](docs/demo-cases.md)；单 App 的信息架构与功能优先级见 [产品体验方案](docs/product-experience.md)；Apple、Android、OpenAI 和 Anthropic 官方资料与本次界面决定见 [界面研究与改版依据](docs/design-research.md)；火山引擎的语音与 AI 接入判断见 [语音选型](docs/volcengine-voice-research.md)；近期功能和比赛节点见 [下一阶段推进顺序](docs/next-sprint.md)；模拟器演示验收见 [MVP 开发计划](docs/mvp-plan.md)。

## 本地运行

需要 Android Studio、Android SDK Platform 35、Build Tools 35、Platform Tools 和 JDK 17。连接已允许 USB 调试的设备或运行模拟器后：

```bash
source scripts/dev-env.sh
./gradlew :guide:assembleDebug :guide:testDebugUnitTest :guide:lintDebug
./scripts/install-app.sh
```

在系统无障碍设置中，用户须手动开启“数字扶手 · 手机引导”。打开数字扶手并确认一项任务，再进入系统设置、Chrome 或 Google Maps。亮度演示需先把系统亮度设在未达最大值、关闭自动亮度的状态。Chrome 演示限定已测公开网页和 Chrome 124 布局；Maps 演示限定香港中央图书馆地点卡和 Maps 11.129 布局。语音输入首次使用时会说明录音去向；拒绝麦克风后仍可用文字和任务卡。

团队如需回归早期模拟流程，可用 `scripts/install-demo.sh` 安装内部测试夹具。这个脚本不用于对外演示或用户安装说明。

## 开发版在线播报

Android 15 模拟器上的 Debug APK 会尝试连接宿主机 `127.0.0.1:8765` 的本地语音代理（模拟器内地址为 `10.0.2.2`）。代理使用已开通的火山引擎 TTS 2.0 / Vivi 2.0；普通话、英语和粤语分别配置语种或粤语模式。服务密钥只在宿主机进程中使用，不放进 APK 或 Git。若代理未运行或接口失败，App 会退回设备 TTS，文字卡照常保留。

本机启动方式：复制 `.env.example` 为被 Git 忽略的 `.env`，只在本机填入 `VOLCENGINE_API_KEY` 和仅允许访问方舟 Mini 模型的 `ARK_API_KEY`。运行 `python3 -m venv .venv && .venv/bin/pip install -r scripts/requirements-voice.txt`，再运行 `set -a; source .env; set +a; SSL_CERT_FILE=/etc/ssl/cert.pem .venv/bin/python scripts/voice_proxy.py`。本机 Homebrew Python 需要指定系统证书库；其他环境若默认 HTTPS 验证正常，可省略 `SSL_CERT_FILE`。检查 `curl http://127.0.0.1:8765/health` 中 `ready`、`intent_ready` 和 `asr_ready`。进入已审核步骤或点击“再听一遍”时，固定引导文字会送到语音接口；用户点“说话”后录音、点“结束”或 8 秒到时，PCM 才经本机代理送到火山 ASR，识别文字先放入可编辑输入框；只有再点箭头提交目标时，目标文字才会送到方舟意图接口。代理只监听宿主机回环地址，Debug 模拟器构建才允许本地 HTTP；正式构建仍使用 Android 设备识别服务。
代理每次启动最多处理 100 次、合计 5,000 字符的演示请求，达到上限即拒绝并由 App 回退到本机播报。这个本机上限不替代控制台的实际免费额度和账单检查。
意图接口另设每次启动最多 60 次请求的上限，ASR 最多 30 次、每次最多 8 秒。意图接口只接收最多 160 字的目标文字和输入语言，返回 `match`、`clarify` 或 `unsupported`；仅四项已审核任务可选。结构化返回还会在本机按允许列表验证，出错时退回保守的本地规则或任务卡。固定三语评测集与真实模型对照见[意图识别验收](docs/intent-evaluation.md)。

## 一起协作

功能、设计、研究和文案讨论可开 Issue。认领前写清目标、预期产物和验收方式；提交 Pull Request 时附上实际验证与未验证部分。约定见 [CONTRIBUTING.md](CONTRIBUTING.md)。不要提交真实用户的录音、截图、身份资料、密钥或服务凭据。
