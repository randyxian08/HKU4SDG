# 数字扶手｜Android 界面设计研究与改版依据

2026-09-28。研究对象是**给不熟悉手机操作的人使用的 Android 指导 App**，不是聊天产品或 iOS App 的复刻。所引用的官方规范用于提炼可验证的设计原则；品牌产品的外观不等于其公开设计规范。

## 原首页的具体问题

原截图中，巨大的「今天想在手机上做什么？」标题、白色输入卡内的「查看可用引导」按钮、深绿的「把字调大」大卡及卡内「查看任务」按钮，连续四次争夺第一视觉焦点。用户看到多个似乎同等重要的入口，但当时只有一个真正可用的任务。深色大卡把说明、能力宣称和动作混在一起，让需要找「下一步」的人先读大量文字。底部的服务状态又被做成大卡，像第二项任务。问题不是字号本身，而是**信息层级与当时的可执行内容不符**。

## 官方资料与落地决定

| 来源 | 可核对的原则 | 数字扶手的决定 |
|---|---|---|
| [Apple HIG：Accessibility](https://developer.apple.com/design/human-interface-guidelines/accessibility?changes=lat_6&language=objc) 与 [Layout](https://developer.apple.com/design/human-interface-guidelines/layout?changes=l_1__4&language=objc) | 任务易理解、控件一致；重要信息先出现；文字放大时仍可用；小字维持足够对比；避免自动消失的限时内容。 | 首页先列可执行任务；任务确认才展开步骤与设备范围。字体比例变化时保留当前页面；引导文字卡默认展开，收起后可再展开。这里借的是可访问性原则，不借 iOS 视觉皮肤。 |
| [Apple UI Design Tips](https://developer.apple.com/design/tips/) | 触控目标至少 44 pt；让控制与其操作对象相邻，避免文字重叠。 | 在 Android 上采用其自身的 48 dp 触控标准；语言入口、任务行、语音键、发送键都做成明确的触控对象。 |
| [Android：布局与导航](https://developer.android.com/design/ui/mobile/guides/layout-and-content/layout-and-nav-patterns)、[Views 无障碍](https://developer.android.com/guide/topics/ui/accessibility/views/apps-views) 与 [Material 3](https://developer.android.com/develop/ui/compose/designsystems/material3) | 熟悉的导航关系；同一层级避免多个高强调动作；互动目标至少 48×48 dp，说明其用途；颜色、字级、形状要形成体系。 | 两项可用任务并列在主要任务区，不造 3–5 项导航栏。品牌绿用于动作、状态用文字表达，装饰标志退出朗读顺序。当前仍是 Android Views 和自定义样式，并未声称已采用 Material 3 组件。 |
| [OpenAI：Apps UI guidelines](https://developers.openai.com/plugins/concepts/ui-guidelines) | 在 ChatGPT 内的 Apps UI 中，强调系统字体、克制的品牌色、稳定的间距与层级、可缩放文字和对比度。 | 把它作为跨产品的排版检查清单：跟随系统字体缩放，减少色块和字号跳跃，固定内边距。此文档适用范围是 **ChatGPT 内的 Apps UI**，不是 Android 原生应用规范。 |
| [Anthropic：Claude Design 官方指南](https://support.claude.com/en/articles/14604416-get-started-with-claude-design) | 先定义受众、目标、布局与设计系统，带截图迭代，并审核对比度和可访问性。 | 用现有运行截图做基线，改版后重新截取真实 APK 画面，在 1.0 和 1.5 字体比例、键盘展开及跨应用状态下复查。此来源是设计工作流说明，**不是 Anthropic 公布的通用 Android HIG**。 |

## 本次界面决策

1. **突出真实任务。** 「把字调大」和「调高屏幕亮度」用白底任务行呈现；「约 1 分钟」描述范围。点击进入任务确认，不在首页先摆全部步骤。
2. **把输入当次路径。** 文字和说话入口固定在屏幕下方，给想描述目标的人使用；不支持的目标明确告知当前支持范围，不伪装成 AI 已理解任意指令。
3. **把权限当状态。** 服务开启/未开启都有文字及设置入口，颜色仅辅助区分。权限不是页面主 CTA。
4. **让任务确认承担后果说明。** 简述要修改真实系统字体、由用户自己点击/拖动、只验证过 Android 15 / Pixel 7 英文设置，底部按钮说清会打开系统设置。
5. **让视觉服务于认知。** 深绿用于品牌和关键动作，白色任务面承载可操作内容，米白背景留白；全页采用同一左右边距与圆角体系。减少原版层层卡片和重复按钮。
6. **让无障碍成为交互的一部分。** 任务和操作入口提供可读名称与 Button 角色，装饰图标从无障碍树排除；字体缩放时保存当前任务页，键盘弹出时保持输入框可见。

## 运行验证与尚未覆盖的条件

当前 [双任务首页截图](screenshots/android15-two-task-home.png) 和 [任务确认截图](screenshots/android15-one-app-task.png) 均来自 Android 15 / Pixel 7 模拟器的真实 APK。已检查 1.0 与 1.5 系统字体比例下首页、任务页布局；字体变化后仍停留在任务确认页；软键盘弹出后输入区在键盘上方；文本输入 `font` 与键盘「前往」可到任务确认；UIAutomator 树中可见任务、语言、服务状态和输入动作的 Button 角色。可点区域最小项为 48 dp，符合 Android 建议。

这些检查不等于完整无障碍验收。下一步在本次演示模拟器上实际开启 TalkBack，逐项核对朗读顺序、焦点返回与语音反馈；继续测试 2.0 字体比例、窄屏、深色模式和粤语语音服务。若有目标用户参与走查，记录其在模拟器上的真实操作。跨应用圈画当前只在已登记的模拟器英文设置页验证；不认识的页面应停下，不能宣称全安卓通用。

## 下轮设计验收

- 让 3–5 位目标用户在**不靠旁人解释**的情况下指出首页可执行任务、语言入口和服务状态；记录每次停顿或误触。
- 在 Android 15 / Pixel 7 演示模拟器逐屏完成「把字调大」，核对圈画没有挡住系统控件，文字卡收起/展开、播报和退出均可找到。
- 对首页、任务确认、语言设置、跨应用文字卡分别用 TalkBack、2.0 字体及窄屏复查；依据失败项再统一组件与设计 token，而不是先加视觉装饰。
