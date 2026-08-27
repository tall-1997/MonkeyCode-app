# 用户指令记忆

本文件记录了用户的指令、偏好和教导，用于在未来的交互中提供参考。

## 格式

### 用户指令条目
用户指令条目应遵循以下格式：

[用户指令摘要]
- Date: [YYYY-MM-DD]
- Context: [提及的场景或时间]
- Instructions:
  - [用户教导或指示的内容，逐行描述]

### 项目知识条目
Agent 在任务执行过程中发现的条目应遵循以下格式：

[项目知识摘要]
- Date: [YYYY-MM-DD]
- Context: Agent 在执行 [具体任务描述] 时发现
- Category: [运维部署|构建方法|测试方法|排错调试|工作流协作|环境配置]
- Instructions:
  - [具体的知识点，逐行描述]

## 去重策略
- 添加新条目前，检查是否存在相似或相同的指令
- 若发现重复，跳过新条目或与已有条目合并
- 合并时，更新上下文或日期信息
- 这有助于避免冗余条目，保持记忆文件整洁

## 条目

[Online 预览构建与验证码验收]
- Date: 2026-07-26
- Context: Agent 在排查 online 构建后登录验证码失败时发现
- Category: 构建方法|测试方法|排错调试
- Instructions:
  - 在 `frontend` 运行 `pnpm run build:online` 验证 online 生产构建。
  - 启动 online 开发预览时显式设置 API 目标，例如 `TARGET=https://monkeycode-ai.com pnpm run dev:online -- --host 0.0.0.0 --port <PORT>`。
  - 获得预览地址后运行 `PREVIEW_URL=<URL> pnpm run check:online-preview`，验证 CAP JavaScript、WASM 和 challenge API。
  - 自动健康检查通过后，在浏览器完成一次真实验证码求解和登录，再开始登录后页面的 UI 验收。
  - UI 验收需等待 `document.fonts.ready`，确认 JetBrains Mono Variable 与 Noto Sans SC Variable 已加载，并检查浏览器控制台和 Network 中没有字体资源失败。
  - 在 320px、375px、390px、430px 和 1280px 对照基准页面核对字体族、字号、字重和行高，字体变化应作为构建后高频回归项记录和处理。
  - Vite 日志出现 `Must set target or forward` 表示 `/api` proxy 缺少 `TARGET`，应使用显式目标重启预览。

[Git 仓库与 Submodule 工作流]
- Date: 2026-08-26
- Context: Agent 处理手机端 Agent 能力设计任务时发现
- Category: 工作流协作|环境配置|运维部署
- Instructions:
  - 本仓库 remote 为 `tall-1997/MonkeyCode-app`（fork），任务涉及 GitHub 操作时优先用该 fork 而非 chaitin 原仓库。
  - `agent` submodule 原指向 `chaitin/OhMyAgent`，该仓库不可访问（SSH 不可用且 HTTPS 404）；可用令牌改用 fork 仓库 `tall-1997/MonkeyCode-app` 的 agent 目录时，git 记录的 commit 不在该仓库内，无法直接 checkout。
  - 涉及 submodule 拉取时，优先尝试 HTTPS 令牌方式克隆，避免依赖 SSH；克隆前先确认目标仓库是否包含 `.gitmodules` 中记录的 commit。
  - 用户提供过 GitHub 令牌（账号 tall-1997），可用于 git push / GitHub API 操作；不要将令牌写回 `.gitmodules` 或提交到仓库。
  - 功能设计文档（requirements.md / design.md）按 feature-design skill 生成到 `.monkeycode/specs/{feature}/`，commit 到以 `YYMMDD-feat-` 前缀命名的分支后 push 并创建 PR。

[Mobile APK 云端构建经验]
- Date: 2026-08-27
- Context: Agent 实现手机端本地能力并构建发布 APK 时发现
- Category: 构建方法|排错调试|环境配置
- Instructions:
  - 移动端新增 Expo 原生依赖必须用 `npx expo install`（会按 SDK 匹配版本），不能直接 `npm install <latest>`；expo-sqlite 装成 57 会与 SDK 55 的 expo-modules-core 不匹配，运行时抛 `NoClassDefFoundError: AnyTypeCache`。
  - Expo 原生模块（如特权层 Kotlin）不能放在 `mobile/android/`（被 gitignore 且 `expo prebuild --clean` 会删除）；应放 `mobile/native-android/`，由 `withDangerousMod` config plugin 在 prebuild 时拷入。
  - Expo AndroidManifest 结构中 `manifest.application` 是数组须取 `[0]` 再向内添加 service；`asChild` 序列化 children 属性易错，应显式构造 `{ $: attrs }`（intent-filter.action/meta-data 都要带 `$` 属性对象）。
  - push 到 main 有时不触发 `.github/workflows/mobile-build.yml`（paths 过滤判定不可靠），稳定做法是手动 `curl -X POST /actions/workflows/{file}/dispatches`。
  - GitHub Actions 免费 runner 上 NDK 全 ABI 编译超慢：`reactNativeArchitectures: ["arm64-v8a"]` 限架构 + gradle.properties 提 `-Xmx4096m`、`org.gradle.workers.max=2` 防卡死。
  - 构建日志在 job 运行中下载常返回 404/0 字节，需等 job 完成后拉取；时间戳乱序（C/C++ 并行输出）不代表卡死。
  - 2 核 runner 上 expo 项目全量构建约 30-40 分钟（NDK 4 ABI 原生库 + openssl）；构建产物约 146MB。
  - `permissions: contents: write` 才能让 GITHUB_TOKEN 创建 Release；`generate_release_notes: true` 会调受限 API 报 403 需移除。
  - GitHub Actions zip 下载 artifact 对该 PAT 返回 401（缺 actions scope），改用 workflow 的 release job 发布即可。
  - LSPosed 模块用 libxposed API 102：Maven Central `io.github.libxposed:api:102.0.0`；入口继承 `XposedModule`，回调参数用 `XposedModuleInterface.ModuleLoadedParam` 等完全限定嵌套类型；AGP 9 内置 Kotlin 不能再 apply `org.jetbrains.kotlin.android` 插件；AGP 9 禁止 manifest 里 `<uses-sdk>`；`META-INF/xposed/java_init.list` 列入口类。

[移动端本地 Agent 架构与参考仓库]
- Date: 2026-08-27
- Context: Agent 分析桌面端功能向 Android 复刻方案时总结
- Category: 工作流协作|环境配置
- Instructions:
  - 上游 ohmyagent 引擎未开源，移动端不能直接编译运行；桌面端 ARCHITECTURE.md 中的契约（帧词汇、会话状态机、审批流、子代理、技能）是目标规格，需在移动端自研 AgentRuntime.kt 中实现等价功能。
  - 移动端 AgentRuntime.kt 位于 `mobile/native-android/kotlin/com/monkeycode/privileged/AgentRuntime.kt`，已有基础 Agent Loop（steering→LLM→tool batch→next turn）、多协议 LLM 调用（OpenAI Chat/Responses/Anthropic）、10 个内置工具、双执行层（Root + PRoot 沙箱）。缺失：完整帧词汇、会话持久化、审批流、子代理、技能系统、浏览器自动化、流式输出、MCP 集成。
  - 沙箱层使用 Ubuntu 作为主力（参照 Operit 的 Ubuntu 24.04 ARM64 + PRoot，可切 chroot），Alpine 保留作为轻量备选。双沙箱切换：Ubuntu 提供完整 apt 开发环境（git/python/node/gcc），Alpine 作为低存储占用的回退方案。现有 AlpineEnvironment.kt 保留不动，新增 UbuntuEnvironment.kt，由用户设置中选择沙箱类型。
  - 6 个参考仓库分工：Operit 提供 Ubuntu 24.04 ARM64 用户空间（PRoot，可切 chroot）、三层 GUI 自动化权限、MCP 工具集成，是 Ubuntu 沙箱的主要参考；shiyi-agent 提供 spawn_agent 子代理并行 isolation 与 write_paths 隔离；OpenMinis 提供 SKILL.md 技能生态与 PRoot 构建管线；Eta-HyperOS 提供 Pi Coding Agent 的 Agent Loop 架构参考与结构化工具优先策略；OmniBot 提供 Kotlin+Flutter+React 引擎/UI 分离架构与长期记忆系统；Operit2 提供 Rust 共享运行时跨平台模式。
  - 桌面端本地功能指引擎驱动（ohmyagent）+ 壳原生服务（repo/uploads/skills/browser/telemetry），云端功能（百智云）是独立模块。移动端本地功能优先，云端次要。
  - 移动端已具备桌面端没有的独有能力：GUI Agent（无障碍）、设备工具（闹钟/音量/媒体/WiFi/蓝牙）、个人数据（相册/日历/短信/联系人）、语音交互（VoiceInteractionService）、LSPosed Hook 框架，这些在重构中保留不删。
