# 技术设计：本地能力栈重构

## 参考仓库映射（真实源码证据）

| 层 | 目标实现 | 底本（commit + 文件路径） |
|---|---|---|
| rootfs 部署 | AlpineEnvironment.kt 升级 | OmniBot ReTerminal `core/main/.../EmbeddedRuntimeInstaller.kt`(608行) 完整性校验；Eta `AlpineEnvironmentInstaller.kt` SHA256 下载 |
| proot 启动参数 | init-host 参数组装移植 Kotlin | OmniBot ReTerminal `assets/init-host.sh`(233行)：--kill-on-exit/-0/--link2symlink/--sysvipc/-L、stat/vmstat 假 /proc、FIPS 兼容文件 |
| 隐藏执行 | 新建 HiddenShellManager.kt | OperitTerminalCore `TerminalManager.kt`(1404行) marker 信封 + PID 杀进程组；OmniBot `EmbeddedTerminalRuntime.kt` executeHiddenCommand 接口形态(executorKey/timeoutMs/onOutputChunk→HiddenExecResult) |
| Agent 循环 | AgentRuntime.kt 重写 | OmniBot `AgentOrchestrator.kt`(1817行) 轮次与回调结构；shiyi `subagent.dart` 白名单/上下文裁剪 |
| LLM 三协议 | 移植现有 TS 实现 | 工作区 mobile/src/local/engine/config.ts 归一化逻辑 → Kotlin |
| 工具集 | ToolHandler 注册表 | shiyi `app_state.dart:626-883` 16 工具 JSON 定义裁剪；OmniBot handlers 包结构 |
| 子代理 | SubagentDispatcher | shiyi `subagent.dart` explore/plan/worker 定义直接翻译 |
| 帧词汇 | FrameKind 枚举 | desktop ARCHITECTURE.md 契约1 + `src/driver/frame.rs`(320行) |
| rootfs 可选升级 | 环境管理页 | Eta VerifiedArtifactDownloader + AlpineMirror |

## 模块结构（Kotlin, com.monkeycode.privileged）

```
privileged/
├── AlpineEnvironment.kt      # 保留部署逻辑，启动参数对齐 init-host.sh
├── HiddenShellManager.kt     # 新增：常驻隐藏 shell + marker 信封
├── engine/
│   ├── AgentEngine.kt        # 主循环（轮次/工具分派/停止）
│   ├── LlmClient.kt          # 三协议流式客户端
│   ├── FrameEmitter.kt       # frame.rs 词汇 → DeviceEventEmitter
│   ├── ToolRegistry.kt       # 工具注册表 + JSON Schema
│   ├── tools/
│   │   ├── TerminalTool.kt   # run_terminal(经 HiddenShellManager)
│   │   ├── FileTools.kt      # file_read/file_write
│   │   ├── WebTool.kt        # web_search/web_extract(复用 web.js 能力或 curl)
│   │   ├── QuestionTool.kt   # question(回 JS 弹窗)
│   │   └── SpawnAgentTool.kt # 子代理派发
│   └── subagents/Subagents.kt
└── RootfsUpgrade.kt          # 可选升级下载器
```

## 关键设计

### 1. 隐藏 shell 信封协议

每条命令经包装后送入常驻 bash stdin：

```sh
__MC_TOKEN=<uuid>
<command>
__RC=$?
echo "__MC_BEGIN_$__MC_TOKEN:$__RC"
echo "__MC_END_$__MC_TOKEN"
kill -0 <group_pid> 占位…
```

输出提取按 OperitTerminalCore 同构规则：读 stdout 流匹配 token 对，
token 间文本为命令输出，END 行携带退出码。PID marker 在 wrapper 内写
`$$`+后台子进程组，超时由 Kotlin 侧 kill(-pid) 进程组。

### 2. executorKey 复用

Map<executorKey, ShellHandle>。同 key 串行 Mutex，空闲 10 分钟回收。
shell 启动 = AlpineEnvironment.ensureReady() → proot 常驻 bash。

### 3. 帧词汇（对齐 desktop）

task-started / task-ended(success,error) / agent_message_chunk /
agent_thought_chunk / tool_call(id,name,args) / tool_call_update(id,state,output) /
plan(todos) / usage_update(input_tokens,output_tokens) / error(message)。
经 FrameEmitter 单点发 `frames:{sessionId}` 全局 DeviceEventEmitter。

### 4. TS 桥保持薄层

AgentBridge.ts 仅保留 startSession/send/stop/listModels，
轮循环全部下沉 Kotlin；local-agent.tsx 改为纯帧渲染。

### 5. 云端零侵入

上游 (tabs)/project/task 页面与 API 不动；本地栈仅新增路由。

## 分阶段任务

- Phase 1（环境+执行）：HiddenShellManager.kt、AlpineEnvironment 启动参数对齐、单测
- Phase 2（引擎）：LlmClient 三协议、ToolRegistry 五类工具、FrameEmitter、子代理
- Phase 3（体验）：rootfs 升级页、布局四入口对齐、jest/tsc/kotlinc 验证
