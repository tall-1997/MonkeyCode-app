# 本地能力栈重构（复用成熟方案）

## 背景

当前移动端本地能力为自研简化实现，功能面窄、可靠性不足。经真实深读六个参考仓库
（commit 级证据已克隆至 /tmp/opencode/refs/），确认业界成熟路线已收敛：

proot + Alpine rootfs（内置/下载）＋ 常驻隐藏 shell 执行信封 ＋ 应用内 Agent 循环。

上游桌面版引擎 OhMyAgent 为私有仓库（git ls-remote 实测 404），无法直接复用；
移动端采取 Kotlin 原生自建循环，帧词汇对齐 desktop/ARCHITECTURE.md 契约 1。

## EARS 需求

### Linux 环境

- WHEN 应用启动本地终端或 Agent 执行命令 THE SYSTEM SHALL 校验 rootfs 最小布局
  （bin/sh 与 etc/os-release 存在）与版本标记一致。
- WHEN rootfs 缺失或损坏 THE SYSTEM SHALL 从 APK assets 原子解压重新部署，
  解压失败时清理半成品目录并重试一次。
- WHEN proot 启动 THE SYSTEM SHALL 按系统分区探测组装 bind mounts
  （/apex /odm /product /system /system_ext /vendor /linkerconfig/* /
  property_contexts /sdcard /storage /dev /proc /sys），并绑定 PREFIX。
- WHEN 用户在设置页触发环境升级 THE SYSTEM SHALL 从官方 dl-cdn 下载新版
  minirootfs 并做 SHA256 校验后原子替换（可选升级，默认使用内置版）。

### 隐藏命令执行（Agent 工具层）

- WHEN Agent 或终端工具执行一次性命令 THE SYSTEM SHALL 复用常驻隐藏 shell
  （proot 内 bash），以 BEGIN/END marker 信封提取 stdout/stderr 与退出码。
- WHEN 同一 executorKey 有未完成命令 THE SYSTEM SHALL 串行排队；不同 key
  使用独立隐藏 shell 实例互不阻塞。
- WHEN 命令超时 THE SYSTEM SHALL 按 PID marker kill 进程组并在结果中标记 timedOut。

### 本地 Agent 引擎（Kotlin 原生）

- WHEN 用户在本地会话发送消息 THE SYSTEM SHALL 以云端模型配置（用户模型列表）
  发起 LLM 循环，interfaceType 支持 openai_chat / openai_responses / anthropic 三协议。
- WHEN 模型返回工具调用 THE SYSTEM SHALL 经工具注册表分派执行并把结果回填上下文，
  直至模型产出最终回复或达到轮数上限。
- WHEN 引擎产生事件 THE SYSTEM SHALL 以 desktop frame.rs 帧词汇向 JS 层推送：
  task-started/ended、agent_message_chunk、tool_call/tool_call_update、plan、
  usage_update、error。
- WHEN 任务含可并行的只读子任务 THE SYSTEM SHALL 支持派发 explore/plan/worker
  三类子代理（工具白名单 + 轮数上限 + 报告裁剪）。

### 页面布局

- WHEN 用户进入本地会话页 THE SYSTEM SHALL 提供模型选择、流式回复渲染、
  工具调用卡片（折叠展开）与停止按钮。
- WHEN 用户查看本地功能入口 THE SYSTEM SHALL 对齐四入口结构：会话/终端/文件/环境管理。
- 云端功能保持与上游 mobile 一致，重构仅涉及新增本地栈。

## 验收标准

1. jest/tsc/kotlinc 编译基线通过。
2. 真机冒烟：内置 Alpine 部署 → 隐藏 shell 执行 apk --version → marker 提取退出码正确。
3. Agent 会话：选择云端模型 → run_terminal 写文件 → file_read 读回 → 帧流渲染完整。
4. 帧词汇命名与 desktop ARCHITECTURE.md 表一一对应（tools 校验单测）。
