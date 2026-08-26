package sync

import (
	"github.com/samber/do"

	v1 "github.com/chaitin/MonkeyCode/backend/biz/sync/handler/v1"
	"github.com/chaitin/MonkeyCode/backend/biz/sync/usecase"
)

// ProvideSync 注册 sync 模块的服务工厂
func ProvideSync(i *do.Injector) {
	do.Provide(i, usecase.NewSyncUsecase)
	do.Provide(i, v1.NewSyncHandler)
}

// InvokeSync 触发 sync 模块的 handler 初始化（注册路由）
func InvokeSync(i *do.Injector) {
	do.MustInvoke[*v1.SyncHandler](i)
}