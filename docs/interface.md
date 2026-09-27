# 数字扶手｜Android 产品界面与当前验证

目标是一款可安装的 Android 应用。首页直接询问目标，提供输入/说话和“把字调大”任务；任务详情先确认范围，再在真实系统设置中以圈画和可折叠文字卡指引。用户亲手操作，不由服务代点。三种语言分别用于输入、引导文字和语音播报。

## 当前截图

| 首页 | 任务确认 | 真实设置第 1 步 | 真实设置第 2 步 | 检测到字体变化 |
|---|---|---|---|---|
| [查看](screenshots/android15-one-app-home.png) | [查看](screenshots/android15-one-app-task.png) | [查看](screenshots/android15-real-settings-step1.png) | [查看](screenshots/android15-real-settings-step2.png) | [查看](screenshots/android15-real-settings-done.png) |

这些截图来自 Android 15 / Pixel 7 模拟器。第 1 步圈在系统 `Display size and text` 行，第 2 步圈在 `Font size` 滑块。模拟器字体比例从 1.0 变为 1.5 后显示完成；离开设置到桌面时，圈画和卡片撤下。系统自身的“Font size added to Quick Settings”提示出现时，我们暂时无法确认页面，会撤圈并显示未知页面；提示消失后在已登记页面重新检查。仍需优化这类系统弹窗的恢复体验。

当前首页是第一轮从“宣传页”改成“任务型 App”的实现，视觉尚待目标用户走查和原生 Material 3 组件化。截图与代码不能证明其他 Android 品牌/系统语言、真机触控、TalkBack 顺序、真人语音识别或粤语 TTS 通过。旧的 [模拟练习截图](screenshots/android15-step.png) 仅留作内部测试历史，不作为产品交付。
