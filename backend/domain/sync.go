package domain

import "context"

// SyncEntityType 同步实体类型
type SyncEntityType string

const (
	SyncEntityProject SyncEntityType = "project"
	SyncEntityTask    SyncEntityType = "task"
	SyncEntitySession SyncEntityType = "session"
	SyncEntityMemory  SyncEntityType = "memory"
)

// SyncAction 同步动作
type SyncAction string

const (
	SyncActionCreate SyncAction = "create"
	SyncActionUpdate SyncAction = "update"
	SyncActionDelete SyncAction = "delete"
)

// SyncChange 单条同步变更
type SyncChange struct {
	EntityType  SyncEntityType `json:"entity_type"`
	EntityID    string         `json:"entity_id"`
	Action      SyncAction     `json:"action"`
	Payload     map[string]any `json:"payload,omitempty"`
	UpdatedAt   int64          `json:"updated_at"`   // LWW 依据（毫秒时间戳）
	CreatedAt   int64          `json:"created_at"`   // 客户端本地上次变更时间
	ConflictKey string         `json:"conflict_key,omitempty"`
}

// SyncConflict 同步冲突（本地与云端同时修改同一实体）
type SyncConflict struct {
	EntityType    SyncEntityType `json:"entity_type"`
	EntityID      string         `json:"entity_id"`
	LocalVersion  map[string]any `json:"local_version"`
	RemoteVersion map[string]any `json:"remote_version"`
}

// SyncPushReq 批量推送请求
type SyncPushReq struct {
	LastSyncTime int64         `json:"last_sync_time"` // 客户端最后同步时间戳
	Changes      []SyncChange  `json:"changes"`
}

// SyncPushResp 推送响应
type SyncPushResp struct {
	Accepted  int             `json:"accepted"`   // 成功落库数
	Conflicts []SyncConflict  `json:"conflicts"`  // 需要客户端处理的冲突
}

// SyncPullReq 拉取请求
type SyncPullReq struct {
	LastSyncTime int64 `json:"last_sync_time"`
	Limit        int   `json:"limit"` // 0 表示默认 500
}

// SyncPullResp 拉取响应
type SyncPullResp struct {
	Changes     []SyncChange `json:"changes"`
	LastSyncTime int64       `json:"last_sync_time"` // 服务端最新同步游标
	HasMore     bool         `json:"has_more"`
}

// SyncUsecase 数据同步用例接口
type SyncUsecase interface {
	// Push 批量合并本地变更到云端（LWW：updated_at 较新的覆盖；冲突返回双版本）
	Push(ctx context.Context, userID string, req SyncPushReq) (*SyncPushResp, error)
	// Pull 拉取自 lastSyncTime 之后的云端变更
	Pull(ctx context.Context, userID string, req SyncPullReq) (*SyncPullResp, error)
}

// SyncStore 同步数据存储
type SyncStore interface {
	// UpsertChange 按 (user_id, entity_type, entity_id) 合并一条变更
	// 若云端 updated_at >= 本地 updated_at 则忽略（LWW 云端胜），否则写本地版本
	UpsertChange(ctx context.Context, userID string, change SyncChange) (conflict bool, remote any, err error)
	// ListChanges 列出某用户在某时间之后的所有变更
	ListChanges(ctx context.Context, userID string, since int64, limit int) ([]SyncChange, error)
	// LatestSyncTime 返回该用户的最近同步游标
	LatestSyncTime(ctx context.Context, userID string) (int64, error)
}