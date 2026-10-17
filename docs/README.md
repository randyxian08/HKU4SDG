# 数字扶手｜文档导航

更新于 2026-10-17。产品是一个原生 Android App，用户只安装 `guide`。四项演示基线见 [PR #6](https://github.com/randyxian08/HKU4SDG/pull/6)；0.1.1 另开放字体、亮度的手机实验引导，尚未逐机验收。Chrome、Maps 仍限定原演示环境及指定版本。

## 按需要阅读

| 你要做什么 | 从这里开始 | 文档性质 |
|---|---|---|
| 了解产品与安装方式 | [仓库 README](../README.md) | 当前功能、快速运行与适用边界 |
| 在手机安装和尝试 | [手机测试指南](phone-testing.md) | 0.1.1 APK、字体／亮度实验路径、中文截图和未验证部分 |
| 接手开发或运行检查 | [开发接手指南](development.md) | 当前目录、配置、命令和排查 |
| 运行 AI／三语语音并准备比赛说明 | [AI 与三语语音](ai-voice-demo-2026-10-17.md)、[验收](validation/ai-voice-2026-10-17.md) | 0.1.3 TTS 修正、模型用途、启动、真实调用与离线退路 |
| 准备演示与案例讲述 | [可演示案例](demo-cases.md) | 已测场景与演示脚本；不是真人试用结果 |
| 看最新浮窗、亮度与服务诊断 | [0.1.2 引导优化](guide-reliability-2026-10-09.md)、[验证摘要](validation/reliability-2026-10-09.md) | 预览避让、稳定窗口、结束回执、亮度分支和未证实问题 |
| 看此前首页、首次设置与浮窗 | [PR #11 整合记录](ui-integration-2026-10-09.md) | 需求优先的首页、三语设置、完成判断和模拟器证据 |
| 看先前浮窗与第三方应用操作 | [浮窗易用性走查](overlay-usability.md) | 2026-10-02 的 Chrome／Maps、布局和操作证据 |
| 看完整页面与四项任务回归 | [界面与端到端验收](uiux-validation.md) | 全应用三语、任务准入和运行证据 |
| 看首次权限与中断恢复 | [首次设置与中断恢复](setup-recovery.md) | 已实现路径、截图和验收条件 |
| 看 AI 相对本地规则的证据 | [意图识别验收](intent-evaluation.md) | 当前 51 条合成开发集与早期 44 条历史对照 |
| 分配下一轮工作 | [MVP 计划](mvp-plan.md)、[下一阶段顺序](next-sprint.md) | 当前状态与规划；日期较早的节点保留历史依据 |

## 产品与设计依据

- [产品体验方案](product-experience.md)：信息架构、帮助方式和功能优先级。
- [产品质量迭代](product-quality.md)：完成确认、继续调整、帮助选择与本机记录。
- [界面研究与改版依据](design-research.md)：Apple、Android、OpenAI、Anthropic 的资料和本项目选择。
- [火山引擎语音选型](volcengine-voice-research.md)：语音、AI 接入判断及尚未验证部分。
- [产品界面历史](interface.md)：历次运行截图与条件；首页以 2026-10-09 整合记录为准，浮窗与流程以本轮 0.1.2 优化记录为准。

## 使用证据时

每份截图、日志或对照只证明其注明的设备、版本、语言、任务与日期。云端接口返回音频／文字不等于真人语音体验通过；平台无障碍操作检查不等于完整 TalkBack 验收；用户确认完成也不等于已经学会。

当前浮窗检查日志位于 [1 倍字号](validation/overlay-1x-2026-10-02.txt)与 [2 倍字号](validation/overlay-2x-2026-10-02.txt)，合成尝试摘要位于[记录摘要](validation/overlay-attempts-2026-10-02.json)。AI 逐条对照见[2026-10-02 JSON](intent-evaluation-2026-10-02.json)。

## 协作与产品资料

[CONTRIBUTING.md](../CONTRIBUTING.md)规定认领、PR 验证说明与同伴审阅。实现和验证结果保存在仓库；[飞书产品定义](https://my.feishu.cn/wiki/FTF5wKcn0iNra6kG2WpcfiZ2nCg)和[飞书进度与案例](https://my.feishu.cn/wiki/NIqHwHf98iIjqektf8KcHtdanZg)用于产品讨论与进度同步。修改需求、完成条件或支持范围时，同时更新对应实现、文档和证据。
