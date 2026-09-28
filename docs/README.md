# Документация document-service

Сервис владеет реквизитами, версиями, snapshots и правилами изменения документа. Контроллер предоставляет внутренний API `/internal/v1`; optimistic concurrency передаётся через version/change token в командах. Вложения изменяют состав версии только через согласованную команду. См. [versioning](../../docs/document-versioning.md) и [API](../../docs/api.md).
