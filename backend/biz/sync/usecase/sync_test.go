package usecase

import (
	"context"
	"log/slog"
	"testing"

	"github.com/chaitin/MonkeyCode/backend/domain"
)

func newUsecase() *SyncUsecase {
	return &SyncUsecase{
		logger: slog.New(slog.DiscardHandler),
		states: make(map[string]*syncState),
	}
}

func TestPushStoreAccepted(t *testing.T) {
	u := newUsecase()
	_, err := u.Push(context.Background(), "u1", domain.SyncPushReq{
		Changes: []domain.SyncChange{
			{EntityType: domain.SyncEntityProject, EntityID: "p1", Action: domain.SyncActionUpdate, UpdatedAt: 100},
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	if len(u.states["u1"].entries) != 1 {
		t.Fatalf("期望 1 条记录，得到 %d", len(u.states["u1"].entries))
	}
}

func TestPushLWWNewerWins(t *testing.T) {
	u := newUsecase()
	// 先推旧版本 100
	_, _ = u.Push(context.Background(), "u1", domain.SyncPushReq{
		Changes: []domain.SyncChange{
			{EntityType: domain.SyncEntityTask, EntityID: "t1", Action: domain.SyncActionUpdate, UpdatedAt: 100, Payload: map[string]any{"v": 1}},
		},
	})
	// 再推新版本 200 → 覆盖
	resp, _ := u.Push(context.Background(), "u1", domain.SyncPushReq{
		Changes: []domain.SyncChange{
			{EntityType: domain.SyncEntityTask, EntityID: "t1", Action: domain.SyncActionUpdate, UpdatedAt: 200, Payload: map[string]any{"v": 2}},
		},
	})
	if resp.Accepted != 1 {
		t.Fatalf("期望 accepted=1，得到 %d", resp.Accepted)
	}
	stored := u.states["u1"].entries["task:t1"].change
	if stored.UpdatedAt != 200 {
		t.Fatalf("期望 updatedAt=200，得到 %d", stored.UpdatedAt)
	}
}

func TestPushOlderVersionReturnsConflict(t *testing.T) {
	u := newUsecase()
	// 先推新版本 200
	_, _ = u.Push(context.Background(), "u1", domain.SyncPushReq{
		Changes: []domain.SyncChange{
			{EntityType: domain.SyncEntityTask, EntityID: "t1", Action: domain.SyncActionUpdate, UpdatedAt: 200, Payload: map[string]any{"v": 2}},
		},
	})
	// 再推旧版本 100 → 应返回冲突，不覆盖
	resp, _ := u.Push(context.Background(), "u1", domain.SyncPushReq{
		Changes: []domain.SyncChange{
			{EntityType: domain.SyncEntityTask, EntityID: "t1", Action: domain.SyncActionUpdate, UpdatedAt: 100, Payload: map[string]any{"v": 1}},
		},
	})
	if len(resp.Conflicts) != 1 {
		t.Fatalf("期望 1 个冲突，得到 %d", len(resp.Conflicts))
	}
	stored := u.states["u1"].entries["task:t1"].change
	if stored.UpdatedAt != 200 {
		t.Fatalf("旧版本不应覆盖，期望 preserved updatedAt=200")
	}
}

func TestPullReturnsChangesSince(t *testing.T) {
	u := newUsecase()
	_, _ = u.Push(context.Background(), "u1", domain.SyncPushReq{
		Changes: []domain.SyncChange{
			{EntityType: domain.SyncEntityProject, EntityID: "p1", Action: domain.SyncActionUpdate, UpdatedAt: 100},
			{EntityType: domain.SyncEntityTask, EntityID: "t1", Action: domain.SyncActionUpdate, UpdatedAt: 300},
			{EntityType: domain.SyncEntityMemory, EntityID: "m1", Action: domain.SyncActionCreate, UpdatedAt: 200},
		},
	})
	resp, _ := u.Pull(context.Background(), "u1", domain.SyncPullReq{LastSyncTime: 150})
	// 只应返回 >150 的 t1(300) 与 m1(200)，按时间升序
	if len(resp.Changes) != 2 {
		t.Fatalf("期望 2 条变更，得到 %d", len(resp.Changes))
	}
	if resp.Changes[0].EntityID != "m1" || resp.Changes[1].EntityID != "t1" {
		t.Fatalf("变更应按时间升序返回: %+v", resp.Changes)
	}
}

func TestPushIgnoresEmptyEntity(t *testing.T) {
	u := newUsecase()
	resp, _ := u.Push(context.Background(), "u1", domain.SyncPushReq{
		Changes: []domain.SyncChange{{EntityType: "", EntityID: ""}},
	})
	if resp.Accepted != 0 {
		t.Fatalf("空实体不应被接受")
	}
}