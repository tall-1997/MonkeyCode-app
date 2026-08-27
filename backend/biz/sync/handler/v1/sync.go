package v1

import (
	"log/slog"

	"github.com/GoYoko/web"
	"github.com/samber/do"

	"github.com/chaitin/MonkeyCode/backend/domain"
	"github.com/chaitin/MonkeyCode/backend/errcode"
	"github.com/chaitin/MonkeyCode/backend/middleware"
)

// SyncHandler 本地数据同步处理器
type SyncHandler struct {
	usecase domain.SyncUsecase
	logger  *slog.Logger
}

// NewSyncHandler 创建同步处理器
func NewSyncHandler(i *do.Injector) (*SyncHandler, error) {
	w := do.MustInvoke[*web.Web](i)
	auth := do.MustInvoke[*middleware.AuthMiddleware](i)

	h := &SyncHandler{
		usecase: do.MustInvoke[domain.SyncUsecase](i),
		logger:  do.MustInvoke[*slog.Logger](i).With("module", "handler.SyncHandler"),
	}

	g := w.Group("/api/v1/sync")
	g.Use(auth.Auth())
	g.POST("/push", web.BindHandler(h.Push))
	g.POST("/pull", web.BindHandler(h.Pull))

	return h, nil
}

// Push 批量推送本地变更（LWW：local.updated_at 与云端比较，最新者胜）
//
//	@Summary		推送本地数据
//	@Description	批量推送本地变更到云端，采用 Last-Writer-Wins 合并
//	@Tags			【用户】数据同步
//	@Accept			json
//	@Produce		json
//	@Security		MonkeyCodeAIAuth
//	@Param			req	body		domain.SyncPushReq			true	"推送请求（含本地上一次同步时间与变更列表）"
//	@Success		200	{object}	web.Resp{data=domain.SyncPushResp}	"成功"
//	@Failure		401	{object}	web.Resp					"未授权"
//	@Router			/api/v1/sync/push [post]
func (h *SyncHandler) Push(c *web.Context, req domain.SyncPushReq) error {
	user := middleware.GetUser(c)
	resp, err := h.usecase.Push(c.Request().Context(), user.ID.String(), req)
	if err != nil {
		return errcode.ErrDatabaseQuery.Wrap(err)
	}
	return c.Success(resp)
}

// Pull 拉取云端变更（返回自 lastSyncTime 之后的云端更新）
//
//	@Summary		拉取云端数据
//	@Description	拉取自上次同步时间之后的云端变更
//	@Tags			【用户】数据同步
//	@Accept			json
//	@Produce		json
//	@Security		MonkeyCodeAIAuth
//	@Param			req	body		domain.SyncPullReq			true	"拉取请求"
//	@Success		200	{object}	web.Resp{data=domain.SyncPullResp}	"成功"
//	@Failure		401	{object}	web.Resp					"未授权"
//	@Router			/api/v1/sync/pull [post]
func (h *SyncHandler) Pull(c *web.Context, req domain.SyncPullReq) error {
	user := middleware.GetUser(c)
	resp, err := h.usecase.Pull(c.Request().Context(), user.ID.String(), req)
	if err != nil {
		return errcode.ErrDatabaseQuery.Wrap(err)
	}
	return c.Success(resp)
}