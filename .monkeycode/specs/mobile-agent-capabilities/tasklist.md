# 手机端 Agent 能力实施计划

对应需求 `requirements.md` 与设计 `design.md`。范围：长期记忆、定时/计划任务、浏览器 GUI 操作、云端+本地双模式。涉及 backend（Go）与 mobile（Expo/RN）。

## 后端基础

- [ ] 1. 扩展模型接口类型支持 embedding
  - 在 `backend/consts` 中为 `InterfaceType` 增加 `embedding` 值（对应设计 1.4）
  - 在 `backend/domain/model.go` 校验枚举中加入 `embedding`
  - 新增 `backend/pkg/llm` embedding client：按模型 base_url + key 调用 `/embeddings`，返回归一化向量
  - 编写 embedding client 单元测试

- [ ] 2. 新增 AgentMemory 数据模型与迁移
  - 新增 migration `000026_agent_memory.up/down.sql`：`agent_memories` 表（scope_type/scope_id/content/tags/source_task/source_type/embedding vector(1536)/updated_at/deleted）
  - 编写迁移测试，验证 up/down 可执行

## 长期记忆系统

- [ ] 3. 实现记忆存储仓库
  - 在 `backend/biz/memory/` 新建 `repo`：`agent_memories` 的增删查、向量相似度检索（pgvector `<=>`）、标签过滤、scope 隔离
  - 编写 repo 单元测试

- [ ] 4. 实现记忆业务逻辑
  - 在 `backend/biz/memory/` 新建 `usecase`：创建/搜索/单查/删除/任务沉淀收集/同步（LWW，updated_at 最新者胜）
  - 在 `backend/domain/` 定义 `MemoryUsecase` / `MemoryRepo` 接口与请求响应结构
  - 编写 usecase 单元测试（含 LWW 冲突收敛测试）

- [ ] 5. 实现记忆 API handler 与路由
  - 在 `backend/biz/memory/` 新建 `handler` 并注册路由：`POST/GET /api/v1/memories`、`GET/DELETE /api/v1/memories/{id}`、`POST /api/v1/tasks/{id}/memories/collect`、`POST /api/v1/memories/sync`
  - 在 `backend/biz/register.go` 注册 memory 模块（Provide/Invoke）
  - 编写 handler 路由测试

- [ ] 6. 记忆注入任务上下文
  - 修改 `backend/biz/task/usecase/task.go`：在 `getCodingConfigs` 后检索相关记忆并拼入 `SystemPrompt`（设计 1.3）
  - 检索失败降级为空，不阻塞任务创建
  - 编写注入逻辑单元测试

- [ ] 7. 检查点 - 后端记忆系统测试全部通过
  - 运行 `cd backend && go test ./...`
  - 如有失败请询问用户

## 定时/计划任务

- [ ] 8. 新增 ScheduledTask 数据模型与迁移
  - 新增 migration `000027_scheduled_task.up/down.sql`：`scheduled_tasks` 表（name/content/schedule_type/run_at/cron_expr/mode/task_ids/enabled/next_run_at/last_result）
  - 编写迁移测试

- [ ] 9. 实现计划任务业务逻辑
  - 在 `backend/biz/scheduledtask/` 新建 `usecase`：创建（cron 校验）/列表/更新/删除/启停/手动触发/下次执行时间计算
  - 引入 cron 解析库（检查 go.mod，无则新增 `robfig/cron`）
  - 在 `backend/domain/` 定义接口与结构
  - 编写 usecase 单元测试（含 cron 表达式非法返回 400）

- [ ] 10. 实现计划任务云端调度器
  - 基于 `backend/pkg/delayqueue` 新建调度器：创建/更新时间变更时按 `next_run_at` 入队，到点生成子任务（复用 TaskUsecase.Create），周期任务推进 next_run_at 后重新入队
  - 调度 payload 携带 `run_id` 保证幂等，重复消费只创建一次子任务
  - 编写调度器单元测试（含幂等测试）

- [ ] 11. 实现计划任务 API handler 与路由
  - 新建 handler 并注册：`POST/GET /api/v1/scheduled-tasks`、`PATCH/DELETE /api/v1/scheduled-tasks/{id}`、`POST /api/v1/scheduled-tasks/{id}/trigger`
  - 在 `backend/biz/register.go` 注册模块
  - 编写 handler 路由测试

- [ ] 12. 检查点 - 后端计划任务测试全部通过
  - 运行 `cd backend && go test ./...`
  - 如有失败请询问用户

## 浏览器 GUI 统一控制层

- [ ] 13. 定义统一浏览器工具协议与类型
  - 在 `backend/domain/` 定义 `browser.go`：`BrowserToolRequest/Response` 结构（open/click/type/scroll/snapshot/assert/navigate + request_id + screenshot/dom_state/assertion）
  - 定义超时（30s）、重试与 URL 白名单安全校验逻辑
  - 编写协议类型与校验单元测试

- [ ] 14. 封装浏览器控制为可下发 skill 资源
  - 在 `backend/biz/agentresource` 侧提供浏览器 skill 的注册/打包路径，使浏览器工具走现有 skill 下发链路注入 agent
  - 编写资源封装单元测试

## 手机端

- [ ] 15. 实现手机端本地记忆存储与检索
  - 在 `mobile/src/` 新增 `memory` 模块：SQLite 表（同构 agent_memories）+ float32 向量 BLOB + 余弦相似度检索（先全表扫描，记忆量大后接 sqlite-vec）
  - 编写 jest 单元测试

- [ ] 16. 实现手机端记忆页面
  - 新增 `mobile/app/memories.tsx`：记忆列表/搜索/删除/手动创建
  - 新增 `mobile/src/components/MemoryCard.tsx`
  - 编写组件渲染测试

- [ ] 17. 实现记忆双端同步
  - 在 `mobile/src/api/client.ts` 增加记忆 CRUD 与 `/memories/sync` 接口调用
  - 实现本地→云端增量同步（游标 + updated_at LWW）与云端→本地拉取
  - 编写同步逻辑单元测试

- [ ] 18. 实现手机端计划任务页与本地调度
  - 新增 `mobile/app/scheduled-tasks.tsx`：创建/编辑/启停/删除/手动触发
  - 新增 `mobile/src/components/ScheduledTaskCard.tsx`
  - 本地调度用 `expo-background-task` 注册后台任务，检查本地表并按点触发
  - 编写组件渲染与调度触发逻辑测试

- [ ] 19. 实现手机端浏览器桥与操作卡片
  - 在 `mobile/src/` 新增浏览器桥：`react-native-webview` + `injectedJavaScript` + `onMessage`，实现 open/click/type/scroll/snapshot/assert
  - `TaskMessageHandler` 支持 `browser-operation` 事件解析；`StreamBlocks.tsx` 增加浏览器操作卡片（截图+摘要+断言）
  - 编写事件解析与桥协议测试

- [ ] 20. 实现任务执行模式与本地 Agent 桥接层
  - `mobile/src/api/types.ts` 与后端 `CreateTaskReq` 对齐 `mode: cloud|local`
  - 新建 `mobile/src/agent/`：CLI 适配层接口（`AgentCLI`：启动/停止/事件流/输入转发），当前绑定 opencode；`LocalAgent` 初始化本地环境、下发任务 payload、将 CLI 事件转译为任务流协议
  - 编写适配层接口与事件转译 mock 测试

- [ ] 21. 实现手机端任务页双模式与展示
  - 修改 `mobile/app/task/[id].tsx`：展示执行模式标识、本地环境状态、记忆注入摘要、浏览器操作卡片
  - `mobile/app/(tabs)/tasks.tsx` 增加执行模式筛选
  - 编写相关组件测试

- [ ] 22. 检查点 - 手机端测试全部通过
  - 运行 `cd mobile && npm test` 与 `cd mobile && npx tsc --noEmit`
  - 如有失败请询问用户

## 集成与收尾

- [ ] 23. 后端集成测试与全量验证
  - `cd backend && go build ./... && go test ./...`
  - 验证新 migration 在测试库可执行
  - 如有失败请询问用户

- [ ] 24. 前后端协议对齐核对
  - 核对 mobile 新增 API 调用与 backend handler 路由/字段一致（memories/scheduled-tasks/browser/mode）
  - 核对 `TaskMessageHandler` 事件类型与后端任务流 `TaskStreamType` 对齐

## 本次实施范围外

- 本地 opencode CLI 的真实二进制下载/解压与在真实设备上的执行验证（agent submodule 不可用，CLI 适配层保留接口与 mock）。
- 云端 VM 内浏览器驱动与真机 WebView 截图像素级验证（依赖运行环境）。
- 前端（Web）端对应的记忆/计划任务/浏览器交互页面。
- pgvector 扩展在真实数据库的安装与性能调优。
