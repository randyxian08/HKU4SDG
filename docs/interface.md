# 数字扶手｜Android 产品界面与当前验证

目标是一款可安装的 Android 应用。首页直接询问目标，提供输入/说话和四项任务：调大字体、调高亮度、在 Chrome 收藏 GovHK 长者服务网页、在 Google Maps 查看香港中央图书馆地址。任务详情先确认范围，再在已审核的系统设置或第三方应用页面中以圈画和可折叠文字卡指引。用户亲手操作，不由服务代点。三种语言分别用于输入、引导文字／AI 提示和语音播报；首页及部分操作控件仍以中文显示。

## 2026-10-02 更新

已补齐三档帮助、用户确认、继续调整和本机可清除记录；标准字号首屏能完整看到四项任务，2.0 字体时可滚动。新浮层可滚动并避开目标；已登记设置页的目标在屏幕外时先提示滚动。当前截图和实际验收见[产品质量迭代](product-quality.md)。下文保留先前四项任务的定位与云端语音历史证据，其中旧“完成”画面不包含本轮新增的用户确认。

## 先前截图

| 早期两任务首页 | 任务确认 | 三项独立语言设置 | 真实设置第 1 步 | 真实设置第 2 步 | 检测到字体变化 |
|---|---|---|---|---|---|
| [查看早期首页](screenshots/android15-two-task-home.png) | [查看字体任务](screenshots/android15-one-app-task.png) | [查看](screenshots/android15-one-app-languages.png) | [查看](screenshots/android15-real-settings-step1.png) | [查看](screenshots/android15-real-settings-step2.png) | [查看](screenshots/android15-real-settings-done.png) |

这些截图来自 Android 15 / Pixel 7 模拟器。第 1 步圈在系统 `Display size and text` 行，第 2 步圈在 `Font size` 滑块。模拟器字体比例从 1.0 变为 1.5 后显示完成；离开设置到桌面时，圈画和卡片撤下。系统自身的“Font size added to Quick Settings”提示出现时，我们暂时无法确认页面，会撤圈并显示未知页面；提示消失后在已登记页面重新检查。仍需优化这类系统弹窗的恢复体验。

亮度任务也在同一模拟器的真实系统设置里完成：[AI 识别后确认任务](screenshots/android15-brightness-ai-match.png) → [圈出 Brightness level](screenshots/android15-brightness-step1.png) → [圈出系统亮度滑杆](screenshots/android15-brightness-step2.png) → [用户拖动后显示完成](screenshots/android15-brightness-done.png)。这次回归中 `SCREEN_BRIGHTNESS` 从 102 变为 194；系统亮度弹窗滑杆在拖动后短暂隐藏，应用读取到实际亮度变化后才显示完成。亮度路径目前以手动亮度模式、起始亮度未达上限为演示条件。自动亮度、最大值与其他系统皮肤未验收。

第三项是在**真实 Chrome 124** 打开 GovHK 长者服务公开网页：[三任务首页](screenshots/android15-three-task-home.png) → [任务确认](screenshots/android15-chrome-task.png) → [圈出菜单](screenshots/android15-chrome-step1.png) → [圈出书签星标](screenshots/android15-chrome-step2.png) → [再次打开菜单核验](screenshots/android15-chrome-step3.png) → [Chrome 显示 `Edit bookmark` 后完成](screenshots/android15-chrome-complete.png)。用户只安装数字扶手一个 APK，在 Chrome 内亲自点击。切到[不匹配的网页](screenshots/android15-chrome-wrong-page.png)时圈画撤下并显示未知页面。此路径只针对该公开网址、当前 Chrome 版本和模拟器布局；不代表能指导任意浏览器页面，也不读取网页正文。

第四项是在**真实 Google Maps 11.129** 查看香港中央图书馆地址：[四任务首页](screenshots/android15-four-task-home.png) → [任务确认](screenshots/android15-maps-task.png) → [圈出地点卡](screenshots/android15-maps-step1.png) → [展开详情后圈出地址行](screenshots/android15-maps-address.png)。切到[其他地点](screenshots/android15-maps-wrong-place.png)后停止圈画。只对 `Hong Kong Central Library` 的指定英文地点卡和地址行提供引导；不申请设备位置、不提供路线导航，也不能据此推出任意地图地点都可指导。地图所示 `66 Causeway Rd, Causeway Bay` 与[香港公共图书馆官方地址](https://www.hkpl.gov.hk/tc/locations/central-library/central.html)一致，但本次“完成”仅表示地址行出现，不表示用户已记住地址或实际到访。Maps 页面发生更新时须重新验收。

Debug 模拟器版新增录音、结束、转写预览状态。首次云端语音使用有[独立的音频去向提示](screenshots/android15-asr-consent.png)；此前只同意 Android 设备识别的用户也须单独确认。三语合成音频经真实火山 ASR 返回文字；模拟器内麦克风静音时，[失败提示](screenshots/android15-asr-silence.png)允许继续文字或任务卡。没有真人三语麦克风输入通过的证据，尤其不能由合成音频测试推断粤语老人的真实识别率。

当前首页已按[界面研究与改版依据](design-research.md)重排：四个真实任务是主入口，权限是一行状态，文字/语音输入在底部；任务确认页说明操作、风险和已验证的设备。四项任务在首页纵向滚动列表中，第四项需要向上滑动才能完整看到。已在模拟器检查 1.0 与 1.5 字体比例、软键盘避让、任务页状态保留以及可点对象的无障碍角色。截图与代码不能证明其他 Android 品牌/系统语言、真机触控、TalkBack 实际朗读顺序、真人语音识别或粤语 TTS 通过。旧的 [模拟练习截图](screenshots/android15-step.png) 仅留作内部测试历史，不作为产品交付。
