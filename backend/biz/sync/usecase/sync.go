package usecase

import (
	"context"
	"log/slog"
	"sync"
	"time"

	"github.com/google/uuid"
	"github.com/samber/do"

	"github.com/chaitin/MonkeyCode/backend/domain"
)

// syncEntry 内存中的一条同步记录
type syncEntry struct {
	change    domain.SyncChange
	createdAt time.Time
}

// syncState 单用户同步状态
type syncState struct {
	lastSyncTime int64
	entries      map[string]*syncEntry // key = entity_type:entity_id
}

// SyncUsecase 内存版同步用例（后续可替换为数据库 Store）
type SyncUsecase struct {
	logger *slog.Logger
	mu     sync.RWMutex
	states map[string]*syncState // key = userID
}

// NewSyncUsecase 创建同步用例
func NewSyncUsecase(i *do.Injector) (*SyncUsecase, error) {
	return &SyncUsecase{
		logger: do.MustInvoke[*slog.Logger](i).With("module", "usecase.SyncUsecase"),
		states: make(map[string]*syncState),
	}, nil
}

func key(entityType domain.SyncEntityType, id string) string {
	return string(entityType) + ":" + id
}

func (u *SyncUsecase) stateFor(userID string) *syncState {
	u.mu.Lock()
	defer u.mu.Unlock()
	if s, ok := u.states[userID]; ok {
		return s
	}
	s := &syncState{entries: make(map[string]*syncEntry)}
	u.states[userID] = s
	return s
}

// Push 合并本地变更，LWW：以 updatedAt 较大者为准
func (u *SyncUsecase) Push(ctx context.Context, userID string, req domain.SyncPushReq) (*domain.SyncPushResp, error) {
	st := u.stateFor(userID)
	resp := &domain.SyncPushResp{Accepted: 0}

	u.mu.Lock()
	defer u.mu.Unlock()

	if req.LastSyncTime > st.lastSyncTime {
		st.lastSyncTime = req.LastSyncTime
	}

	for _, change := range req.Changes {
		if change.EntityID == "" || change.EntityType == "" {
			continue
		}
		k := key(change.EntityType, change.EntityID)
		existing, ok := st.entries[k]
		if ok && existing.change.UpdatedAt > change.UpdatedAt {
			// 云端较新，客户端落后 → 返回冲突供合并
			resp.Conflicts = append(resp.Conflicts, domain.SyncConflict{
				EntityType:    change.EntityType,
				EntityID:      change.EntityID,
				LocalVersion:  change.Payload,
				RemoteVersion: existing.change.Payload,
			})
			continue
		}
		st.entries[k] = &syncEntry{change: change, createdAt: time.Now()}
		resp.Accepted++
	}
	u.logger.Debug("sync push", "user", userID, "accepted", resp.Accepted, "conflicts", len(resp.Conflicts))
	return resp, nil
}

// Pull 返回该用户自 lastSyncTime 之后的所有变更
func (u *SyncUsecase) Pull(ctx context.Context, userID string, req domain.SyncPullReq) (*domain.SyncPullResp, error) {
	st := u.stateFor(userID)
	limit := req.Limit
	if limit <= 0 {
		limit = 500
	}

	u.mu.RLock()
	defer u.mu.RUnlock()

	changes := make([]domain.SyncChange, 0)
	for _, e := range st.entries {
		if e.change.UpdatedAt > req.LastSyncTime {
			changes = append(changes, e.change)
		}
	}
	// 按更新时间升序返回（客户端顺序应用）
	sortByUpdatedAt(changes)

	resp := &domain.SyncPullResp{
		Changes:      changes,
		LastSyncTime: st.lastSyncTime,
		HasMore:      len(changes) > limit,
	}
	if len(changes) > limit {
		resp.Changes = changes[:limit]
	}
	return resp, nil
}

func sortByUpdatedAt(list []domain.SyncChange) {
	for i := 1; i < len(list); i++ {
		for j := i; j > 0 && list[j].UpdatedAt < list[j-1].UpdatedAt; j-- {
			list[j], list[j-1] = list[j-1], list[j]
		}
	}
}

var _ domain.SyncUsecase = (*SyncUsecase)(nil)

// 避免未使用告警
var _ = uuid.New