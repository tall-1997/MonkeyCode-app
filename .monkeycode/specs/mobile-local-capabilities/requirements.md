# Requirements Document

## Introduction

本需求是对 `mobile-agent-capabilities` 规格的补充扩展，目标是为 MonkeyCode 手机端（Android）增加与桌面端对等的本地能力，并参考 Eta-HyperOS 项目实现 **Root + LSPosed 双模式执行架构**。核心思路：沙箱模式（无 Root）限制在 App 沙盒内运行，特权模式（Root + LSPosed）越过沙盒直达系统底层，使 AI Agent 能够真实操作手机设备。

桌面端通过 Tauri v2 Rust 壳层管理本地 AI 引擎进程、文件系统、配置存储、浏览器桥接；Eta 通过 Root + LSPosed 实现系统级 Hook、GUI Agent、Shell 终端、个人数据直达。本需求将两者结合，为 MonkeyCode 手机端构建完整的本地执行能力。

## Glossary

- **沙箱模式**：无 Root 权限时的执行模式，所有操作限制在 App 沙盒内，仅能使用 Expo 原生模块提供的基础能力
- **特权模式**：Root + LSPosed 可用时的执行模式，可越过 App 沙盒访问系统底层 API、执行 root shell 命令、读写任意文件、Hook 系统组件
- **特权执行层**：基于 Eta 架构的 Android 原生模块（Kotlin），负责 Root shell 执行、LSPosed Hook 管理、GUI Agent、系统 API 调用
- **本地引擎**：在移动设备上运行的**自研 AI Agent 引擎**（Kotlin AgentRuntime，不依赖上游二进制），负责执行 AI 编码任务，具备 Root 提权操作手机与 PRoot 免 root 沙箱双通道
- **本地工作区**：Android 设备文件系统中的项目目录，沙箱模式为 `/data/local/tmp/monkeycode`，特权模式可自定义
- **离线模式**：设备无网络连接时，仍能使用本地功能进行代码编辑、任务管理和文件操作
- **GUI Agent**：通过截图、无障碍节点、点击、滚动和输入操作手机屏幕的能力
- **系统 Hook**：通过 LSPosed 注入 system_server/SystemUI/厂商助手等进程，实现电源键接管、系统助手入口替换等
- **Alpine Linux 环境**：通过 Root chroot 运行的 Alpine Linux 工具环境，提供 Git、Python、rg、fd 等开发工具

## Execution Modes

### Mode 1: 沙箱模式 (Sandbox)

WHEN 设备无 Root 权限, THE 系统 SHALL 仅使用 App 沙盒内的能力运行：
- expo-file-system 范围内的文件读写
- App 私有目录下的项目存储
- 通过云端 API 的 Git 操作
- 无 shell 终端能力
- 无 GUI Agent 能力
- 无系统 API 访问能力

### Mode 2: 特权模式 (Privileged)

WHEN 设备具备 Root 权限且 LSPosed 已安装, THE 系统 SHALL 暴露完整系统级能力：
- Root shell 命令执行
- 全文件系统读写
- 系统 API 直达（Wi-Fi、音量、闹钟、蓝牙等）
- GUI Agent（截图、无障碍、触摸模拟）
- LSPosed 系统 Hook（电源键接管、厂商助手替换）
- Alpine Linux 工具环境
- 个人数据读取（相册、日历、短信、通知、应用活动等）

## Requirements

### Requirement 1: 双模式执行架构

**User Story:** AS 手机端用户, I want 应用根据设备状态自动选择沙箱或特权模式, so that 在有 Root 的设备上能获得完整能力，无 Root 的设备上也能正常使用基础功能

#### Acceptance Criteria

1. WHEN 应用启动, THE 系统 SHALL 检测 Root 权限和 LSPosed 可用性
2. WHEN Root 和 LSPosed 均可用, THE 系统 SHALL 启用特权模式并暴露所有系统级能力
3. WHEN Root 或 LSPosed 不可用, THE 系统 SHALL 进入沙箱模式，特权能力在 UI 上灰显
4. WHILE 处于特权模式, THE 系统 SHALL 在设置页显示当前权限状态（Root 类型、LSPosed 版本、Magisk/KernelSU/APatch 检测结果）
5. WHEN 用户在特权模式下, THE 系统 SHALL 为每种敏感能力提供独立开关（设备直达、敏感信息读取、终端/文件、GUI 操作）
6. IF 特权模式下权限丢失（如 Magisk 模块被禁用）, THE 系统 SHALL 降级到沙箱模式并通知用户

### Requirement 2: 特权执行层 - Root Shell 与终端

**User Story:** AS 手机端用户, I want 在特权模式下执行 root shell 命令和脚本, so that 可以像桌面端一样进行系统级开发操作

#### Acceptance Criteria

1. WHEN 用户打开终端面板且处于特权模式, THE 系统 SHALL 提供 user 和 root 两种 shell 身份选择
2. WHEN 用户选择 root 身份, THE 系统 SHALL 通过 `su` 启动 root shell 会话
3. WHEN 用户在终端中输入命令, THE 系统 SHALL 在对应身份下执行并实时显示输出
4. WHEN 会话式 shell 运行, THE 系统 SHALL 保持 cwd 和环境变量跨命令
5. WHEN 用户发起异步任务, THE 系统 SHALL 在后台执行并分段读取输出
6. WHILE 终端运行, THE 系统 SHALL 自动探测 Magisk/KernelSU/APatch 提供的 BusyBox 并以 standalone ash 补齐 PATH
7. IF 命令执行时间超过用户设定阈值, THE 系统 SHALL 允许取消执行
8. WHEN 用户取消执行, THE 系统 SHALL 通过独立进程组（setsid）终止命令及其子进程

### Requirement 3: 特权执行层 - 全文件系统访问

**User Story:** AS 手机端用户, I want 在特权模式下访问任意文件系统路径, so that 可以浏览和修改系统文件、应用数据、Magisk 模块等

#### Acceptance Criteria

1. WHEN 用户处于特权模式, THE 系统 SHALL 允许浏览任意文件系统路径（`/`、`/data`、`/system`、`/sdcard` 等）
2. WHEN 用户读取文件, THE 系统 SHALL 通过 Root 权限读取并以代码编辑器展示
3. WHEN 用户写入文件, THE 系统 SHALL 通过 Root 权限写入并原子替换
4. WHEN 用户列出目录, THE 系统 SHALL 递归展示目录树结构，含文件大小、权限、修改时间
5. IF 文件路径包含符号链接, THE 系统 SHALL 解析为真实路径后再操作
6. WHEN 用户引用聊天中的文件, THE 系统 SHALL 只把经过 Root 校验的规范绝对路径写入模型上下文

### Requirement 4: 特权执行层 - 系统 API 直达

**User Story:** AS 手机端用户, I want AI Agent 能直接调用系统 API, so that 可以操作闹钟、媒体、音量、Wi-Fi 等系统功能

#### Acceptance Criteria

1. WHEN Agent 调用设备直达工具, THE 系统 SHALL 通过结构化 Schema 执行系统操作
2. WHEN Agent 操作闹钟, THE 系统 SHALL 通过 `AlarmManager` 或 Settings Provider 创建/修改/删除闹钟
3. WHEN Agent 操作媒体, THE 系统 SHALL 通过 `MediaSessionManager` 控制播放/暂停/下一首/上一首
4. WHEN Agent 操作音量, THE 系统 SHALL 通过 `AudioManager` 调整各通道音量
5. WHEN Agent 操作 Wi-Fi/蓝牙, THE 系统 SHALL 通过系统服务切换开关状态
6. WHEN Agent 查询设备状态, THE 系统 SHALL 返回电池、存储、内存、网络等信息
7. WHEN 工具参数通过 Schema 校验, THE 系统 SHALL 执行操作
8. IF 参数校验失败, THE 系统 SHALL 返回结构化错误，不执行操作

### Requirement 5: 特权执行层 - 个人数据读取

**User Story:** AS 手机端用户, I want AI Agent 能按需读取本机个人数据, so that 可以结合上下文提供个性化服务

#### Acceptance Criteria

1. WHEN 用户开启"敏感信息读取"开关, THE 系统 SHALL 向 Agent 暴露只读数据检索工具
2. WHEN Agent 检索相册, THE 系统 SHALL 通过 MediaStore Provider 返回指定数量的图片元数据
3. WHEN Agent 检索日历, THE 系统 SHALL 通过 Calendar Provider 返回指定时间范围的事件
4. WHEN Agent 检索短信, THE 系统 SHALL 通过 SMS Provider 返回最近对话
5. WHEN Agent 检索通知历史, THE 系统 SHALL 从本机数据库返回最近 7 天最多 1000 条通知
6. WHEN Agent 读取应用活动, THE 系统 SHALL 通过 UsageStatsManager 返回使用时长统计
7. WHEN 工具结果返回, THE 系统 SHALL 在当前回合结束后从持久会话中移除敏感数据
8. IF 设备上缺少对应数据源, THE 系统 SHALL 返回结构化不可用错误

### Requirement 6: 特权执行层 - GUI Agent

**User Story:** AS 手机端用户, I want AI Agent 能通过截图和无障碍操作手机屏幕, so that 可以操作没有 API 的第三方应用

#### Acceptance Criteria

1. WHEN Agent 调用截图工具, THE 系统 SHALL 通过 `screencap` 或 MediaProjection 截取当前屏幕
2. WHEN Agent 调用无障碍工具, THE 系统 SHALL 通过 AccessibilityService 获取当前界面节点树
3. WHEN Agent 执行点击操作, THE 系统 SHALL 通过 AccessibilityService 或 `input tap` 在指定坐标点击
4. WHEN Agent 执行滚动操作, THE 系统 SHALL 通过 AccessibilityService 或 `input swipe` 滚动
5. WHEN Agent 执行输入操作, THE 系统 SHALL 通过 AccessibilityService 或剪贴板粘贴文本
6. WHILE 前台 GUI 操作执行, THE 系统 SHALL 显示运行浮层与手势反馈
7. WHEN 用户点击停止, THE 系统 SHALL 立即停止 GUI 操作并恢复控制
8. IF 无障碍服务未开启或断连, THE 系统 SHALL 在 GUI 操作前明确失败
9. WHEN 无障碍服务断连, THE 系统 SHALL 在特权模式下自动重绑（最多 3 次，冷却 1 分钟）

### Requirement 7: 特权执行层 - LSPosed 系统 Hook

**User Story:** AS 手机端用户, I want 应用能接管系统助手入口和电源键, so that 可以从系统级入口快速唤起 AI Agent

#### Acceptance Criteria

1. WHEN LSPosed 模块激活, THE 系统 SHALL 注入 system_server 进程
2. WHEN 用户长按电源键, THE 系统 SHALL 根据用户配置将事件路由到 Eta 助手面板或原厂商助手
3. WHEN 用户通过小布/小爱语音唤起, THE 系统 SHALL 在 `Agent` 前缀下接管请求并交给 Agent Runtime
4. WHEN 系统入口无法启动, THE 系统 SHALL 立即回退到原厂商助手
5. WHEN 用户通过 VoiceInteractionService 唤起, THE 系统 SHALL 显示全屏键盘助理浮窗
6. WHEN 用户提交文本, THE 系统 SHALL 将请求交给 Agent Runtime 处理
7. IF Hook 目标签名漂移（系统/App 升级）, THE 系统 SHALL 停止该 Hook 并记录缺失

### Requirement 8: 特权执行层 - Alpine Linux 工具环境

**User Story:** AS 手机端用户, I want 在特权模式下安装 Alpine Linux 工具环境, so that 可以使用 Git、Python、rg、fd 等开发工具

#### Acceptance Criteria

1. WHEN 用户在设置中安装 Linux 环境, THE 系统 SHALL 下载固定版本的 Alpine minirootfs 并校验 SHA-256
2. WHEN 安装完成, THE 系统 SHALL 在 App 私有目录中解压并通过独立 mount namespace + Root chroot 运行
3. WHEN 用户选择 `linux` 终端环境, THE 系统 SHALL 在 `/workspace`（绑定 Eta Android 工作目录）中执行命令
4. WHEN 环境安装, THE 系统 SHALL 预装 Git、Python、rg、fd、curl、jq、SQLite、压缩工具
5. WHEN 用户执行 `apk` 命令, THE 系统 SHALL 允许在 Alpine 环境中安装额外包
6. IF 安装校验失败, THE 系统 SHALL 拒绝启动 Linux 环境并提示重新安装

### Requirement 9: 本地文件系统与代码编辑

**User Story:** AS 手机端用户, I want 在手机上浏览和编辑本地项目文件, so that 可以在无网络环境下也能进行代码开发

#### Acceptance Criteria

1. WHEN 用户进入文件面板, THE 系统 SHALL 显示当前工作区的文件和目录树结构
2. WHEN 用户点击文件, THE 系统 SHALL 以代码编辑器形式展示文件内容，支持语法高亮和行号显示
3. WHEN 用户修改文件, THE 系统 SHALL 自动保存到本地文件系统
4. WHILE 工作区存在未提交的 Git 变更, THE 系统 SHALL 在文件树中标识变更状态
5. WHEN 用户创建新文件或文件夹, THE 系统 SHALL 在本地工作区中创建对应文件或目录
6. IF 文件大小超过 5MB, THE 系统 SHALL 提示用户文件过大并仅展示前 5MB

### Requirement 10: 本地 Git 操作

**User Story:** AS 手机端用户, I want 在手机上直接进行 Git 操作, so that 可以在本地完成代码版本管理

#### Acceptance Criteria

1. WHEN 用户查看 Git 状态, THE 系统 SHALL 显示变更文件列表及变更类型
2. WHEN 用户暂存文件, THE 系统 SHALL 将选中文件添加到暂存区
3. WHEN 用户提交变更, THE 系统 SHALL 使用用户输入的提交信息创建本地 commit
4. WHEN 用户推送/拉取, THE 系统 SHALL 与远程仓库同步
5. WHEN 用户切换分支, THE 系统 SHALL 列出所有分支并允许切换
6. WHEN 用户查看提交历史, THE 系统 SHALL 展示提交记录
7. IF 合并发生冲突, THE 系统 SHALL 显示冲突文件列表并允许手动解决
8. WHEN 沙箱模式, THE 系统 SHALL 通过 `isomorphic-git` 纯 JS 实现 Git 操作
9. WHEN 特权模式, THE 系统 SHALL 通过 Alpine Linux 环境中的原生 git 执行操作

### Requirement 11: 离线工作模式

**User Story:** AS 手机端用户, I want 在无网络环境下继续使用核心功能, so that 可以随时随地进行开发

#### Acceptance Criteria

1. WHEN 网络断开, THE 系统 SHALL 自动切换到离线模式并显示状态指示
2. WHILE 离线模式, THE 系统 SHALL 允许本地文件浏览、代码编辑、本地终端和特权模式下的所有能力
3. WHILE 离线模式, THE 系统 SHALL 禁用云端功能并灰显
4. WHEN 网络恢复, THE 系统 SHALL 自动切换在线模式并触发同步
5. WHEN 离线时创建任务, THE 系统 SHALL 暂存到本地队列，联网后同步
6. IF 同步失败, THE 系统 SHALL 保留本地数据并展示失败列表

### Requirement 12: 本地 Agent 引擎

**User Story:** AS 手机端用户, I want 在手机上本地运行 AI Agent 引擎, so that 可以在不依赖云端的情况下执行 AI 编码任务

#### Acceptance Criteria

1. WHEN 用户选择本地模式创建任务, THE 系统 SHALL 启动本地自研 Agent 引擎
2. WHEN 引擎启动成功, THE 系统 SHALL 展示引擎状态
3. WHEN 用户发送任务, THE 系统 SHALL 通过 stdio JSON-RPC 传递给引擎
4. WHEN 引擎产生响应, THE 系统 SHALL 实时流式展示
5. IF 引擎崩溃, THE 系统 SHALL 自动重启（退避 1/2/4s，最多 3 次）
6. IF 资源不足, THE 系统 SHALL 阻止启动并提示
7. WHEN 特权模式下 Agent 调用文件工具, THE 系统 SHALL 通过 Root 权限作用于真实文件系统
8. WHEN 沙箱模式下 Agent 调用文件工具, THE 系统 SHALL 限制在 App 沙盒内

## 与 Eta-HyperOS 架构的对应关系

| Eta-HyperOS 能力 | MonkeyCode 特权模式实现 |
|------------------|------------------------|
| Root Shell 执行 | 特权执行层 TerminalModule |
| 全文件系统访问 | 特权执行层 FileSystemModule |
| 系统 API 直达 | 特权执行层 DeviceTools |
| 个人数据读取 | 特权执行层 PersonalDataProvider |
| GUI Agent | 特权执行层 GUIAgent |
| LSPosed Hook | 特权执行层 SystemHook |
| 长期记忆 | mobile-agent-capabilities 已覆盖 |
| Skills | 本地技能管理 |
| 内置浏览器 | mobile-agent-capabilities 已覆盖 |
| Alpine Linux 环境 | 特权执行层 AlpineEnvironment |
| Agent Runtime | Agent Loop + 本地引擎 |

## 与 mobile-agent-capabilities 的关系

| 能力域 | mobile-agent-capabilities | mobile-local-capabilities |
|--------|--------------------------|---------------------------|
| 长期记忆系统 | 已覆盖 | 不重复覆盖 |
| 定时/计划任务 | 已覆盖 | 不重复覆盖 |
| 浏览器 GUI 操作 | 已覆盖 | 不重复覆盖 |
| 本地 Agent 桥接 | 已覆盖（CLI 适配层） | 扩展为完整引擎生命周期管理 |
| 双模式执行架构 | 未覆盖 | 新增（需求 1） |
| Root Shell 终端 | 未覆盖 | 新增（需求 2） |
| 全文件系统访问 | 未覆盖 | 新增（需求 3） |
| 系统 API 直达 | 未覆盖 | 新增（需求 4） |
| 个人数据读取 | 未覆盖 | 新增（需求 5） |
| GUI Agent | 未覆盖 | 新增（需求 6） |
| LSPosed 系统 Hook | 未覆盖 | 新增（需求 7） |
| Alpine Linux 环境 | 未覆盖 | 新增（需求 8） |
| 本地文件系统/代码编辑 | 未覆盖 | 新增（需求 9） |
| 本地 Git 操作 | 未覆盖 | 新增（需求 10） |
| 离线工作模式 | 未覆盖 | 新增（需求 11） |
| 本地 Agent 引擎 | 未覆盖 | 新增（需求 12） |

## 技术决策

| 决策项 | 选择 | 说明 |
|--------|------|------|
| 执行模式 | 双模式（沙箱 + 特权） | 自动检测 Root/LSPosed，无 Root 降级沙箱 |
| Android 原生模块 | Kotlin (Eta-style) | 参考 Eta 架构，Eta 为独立 Android App，MonkeyCode 将其核心作为原生模块集成 |
| 终端环境 | Android Shell + Alpine Linux | 终端区分 `android` 和 `linux` 两种环境 |
| 系统集成 | Root + LSPosed | 需要 Root 权限和 libxposed API 102 |
| 本地引擎 | 自研 Kotlin AgentRuntime | 内嵌引擎（无需 arm64 二进制），Root 提权 / PRoot 免 root 双通道 |
| GUI Agent | AccessibilityService + screencap | 参考 Eta 无障碍 + 截图方案 |
| 离线模式 | 代码编辑与任务管理同等重要 | 离线时两者同时支持 |

## 本次实施范围外

- iOS 平台的特权模式（iOS 无 Root/LSPosed 生态，仅支持沙箱模式）
- 在真实设备上的完整 E2E 验证（需解锁 Bootloader 并安装 Magisk + LSPosed 的真实设备）
- 厂商助手适配（ColorOS 小布、HyperOS 小爱需要真机 ROM 特定版本验证）
- Google App 系统化和一圈即搜（与 MonkeyCode 核心功能无关）
- 桌面宠物/系统托盘等纯桌面端 UI 功能
- 桌面端自动更新机制（移动端已有 OTA 更新）