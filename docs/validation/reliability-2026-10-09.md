# 0.1.2 验证摘要

日期：2026-10-09。版本：0.1.2 / versionCode 3 / Debug。构建时源码基于 `main` 的 `c905406e0e3d8dcb1709d34c6e8499d2778a71e4`，处于本轮待提交状态。首次回归标识：`c905406-dirty/2026-10-09T11:01:58.559316Z`。补充真实触屏检查与最终测试包标识：`c905406-dirty/2026-10-09T11:19:11.386318Z`；产品源码相同，补充修改仅涉及设备检查。功能与限制见[优化记录](../guide-reliability-2026-10-09.md)。

## 构建与逻辑

一次完整构建通过：`guide` Debug、未签名 Release、开发检查 APK、`demo-target` Debug、36 项 Kotlin 单元测试和两个模块的 lint。无 lint 错误，guide 8 条警告，内部夹具 3 条警告。guide 的警告包括崩溃记录使用同步偏好提交、读取平台能力资源、绘制分配，以及已有依赖／目标版本提示。未签名 Release 不能直接安装或称为正式发布。

9 项代理单元测试通过，使用模拟服务响应；本轮没有新增真实云端 ASR／TTS／意图效果验收。

## 模拟器运行

设备：`HKU4SDG_API35`、Android 15 / API 35、`sdk_gphone64_arm64`、`ranchu`、1080 × 2400、420dpi。应用字号 1.15。英文系统的应用提示为普通话；中文回归使用真实 `zh-Hans-CN` 系统设置页面。各检查恢复应用偏好／记录，改变系统值的检查恢复起始值。

| 检查 | 结果 | 直接证据 |
|---|---|---|
| 服务反馈中断、滚动窗口复用、真实触屏穿透／收起重播、预览避让、诊断、权限帮助和回执 | PASS | [reliability](reliability-2026-10-09.txt) |
| 英文首次设置、输入优先、语言独立、三档帮助、13 秒等待不完成、实际字体改变与确认 | PASS | [integration](integration-v012-2026-10-09.txt) |
| 1.0 字号字体浮窗：操作、至少 48dp、角色、控件避让、保留同次任务 | PASS | [font overlay](overlay-v012-1x-font-2026-10-09.txt) |
| 1.0 字号亮度浮窗：同类操作与目标避让 | PASS | [brightness overlay](overlay-v012-1x-brightness-2026-10-09.txt) |
| 2.0 字号亮度浮窗：收起／恢复、移动、暂停／继续、退出 | PASS | [2x overlay](overlay-v012-2x-brightness-2026-10-09.txt) |
| TalkBack 已启用，平台角色、焦点及操作；1.0 / 2.0 字号 | PASS | [1x](a11y-v012-1x-2026-10-09.txt)、[2x](a11y-v012-2x-2026-10-09.txt) |
| 真实中文设置：字体／亮度入口、滑块、数值改变、确认、未知页撤圈与适配导出白名单 | PASS | [Chinese Settings](phone-v012-zh-2026-10-09.txt) |

最终测试包的 [ReliabilityCheck](reliability-2026-10-09.txt) 和[字体浮窗复核](overlay-v012-final-font-2026-10-09.txt)均通过；后者同时验证测试结束后的服务重绑清理。其余表中记录来自首次回归，产品源码相同。

检查完成后恢复 en-US、系统字号 1.0、手动亮度模式和亮度 102，TalkBack 恢复关闭；原数字扶手权限仍开启，服务已连接。测试前的活动任务通过应用内结束入口结束。Instrumentation 启动／退出会终止目标进程，独立 `uiautomator dump` 会抑制无障碍服务；测试脚本仅重绑数字扶手。工具造成的系统“Crashed services”、解绑或进程退出记录不能当作普通使用崩溃的证据。实际触屏检查使用保留服务的 UiAutomation 会话，避免此干扰。

## 安装包与截图

用户只安装 `guide`；开发检查与内部夹具不随用户安装。测试 APK 的 SHA-256 为 `c3ffe8bc49a61201a5422b9719990a67e4674762fb5da09aba6c5f710066fd8a`，使用开发签名，不是应用商店发行版，也不含宿主机 API Key。源码构建的时间标识和 APK 哈希会改变，不能用版本名相同推断为同一个文件。

截图：英文系统下的[字体预览](../screenshots/android15-oct9-v012-font-preview.png)、[字体结果](../screenshots/android15-oct9-v012-font-result.png)、[2 倍字号收起栏](../screenshots/android15-oct9-v012-overlay-compact-2x.png)、[确认回执](../screenshots/android15-oct9-v012-confirmed-receipt.png)、[未确认回执](../screenshots/android15-oct9-v012-ended-receipt.png)、[亮度选择](../screenshots/android15-oct9-v012-brightness-choice.png)、[服务诊断](../screenshots/android15-oct9-v012-diagnostics.png)、[权限帮助](../screenshots/android15-oct9-v012-setup-help.png)；真实中文设置下的[字体滑块](../screenshots/android15-oct9-v012-font-slider-zh.png)和[亮度滑块](../screenshots/android15-oct9-v012-brightness-slider-zh.png)。

## 仍未证明的部分

- 自动亮度开／关分支：平台能力为 `false`，真实设置没有开关；仅有合成状态及唯一控件匹配测试。已验证禁用选项和说明，不能算自动亮度设备验收。
- 手机录屏中的服务自行关闭：没有同期崩溃／解绑／权限诊断，根因未证实。本轮修正了反馈中断处理并增加诊断，不能宣称彻底修复该现象。
- 本次检查不证明完整 TalkBack 手势与朗读顺序、真人语音体验、目标用户易用性、独立重做或任意第三方应用兼容性。点按激活重播只验证按钮及流程，不是音频质量评价。
- [本轮 GitHub Actions](https://github.com/randyxian08/HKU4SDG/actions/runs/37924030560) 显示失败，但 job 未启动、无编译日志。平台注释提示账号近期付款失败或需提高消费额度；本地通过不能记成 CI 通过。与源代码编译失败分开处理。
