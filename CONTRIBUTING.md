# 协作约定

1. 先开 Issue 描述问题或目标；小型文字修订可以直接提交 Pull Request。
2. 从 `main` 建分支，建议使用 `feat/`、`fix/`、`docs/` 等前缀。
3. Pull Request 说明改动、验证结果和限制。涉及引导流程、三语文案或私人步骤时，写明目标设备、测试语言和审核状态。
4. 合并前至少请一位同伴审阅。不要把未经验证的功能写成已实现，也不要把模拟流程写成真实机构服务。
5. 不上传个人敏感信息、真实用户录音或截图、API 密钥和 `.env` 文件；测试使用虚构数据。

涉及代码的本地提交前运行 `source scripts/dev-env.sh` 和 `./gradlew :guide:assembleDebug :demo-target:assembleDebug :guide:testDebugUnitTest`。跨应用圈画、触控穿透、权限、TTS 和 ASR 需在本次目标演示设备 Android 15 / Pixel 7 模拟器上逐项检查，CI 编译通过不能代替运行验收。纯文档修改核对事实、链接与 `git diff --check`。每个 PR 附上检查设备、Android 版本和相关语言。

产品需求以仓库中的明确任务和[飞书产品文档](https://my.feishu.cn/wiki/FTF5wKcn0iNra6kG2WpcfiZ2nCg)为起点。有冲突时先在 Issue 或 Pull Request 中确认，再修改实现。
