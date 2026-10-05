# corelia-document-service

Владелец document business rules: валидации конфигурационных атрибутов,
capabilities, optimistic locking, idempotency и оркестрации команд документа.
Сервис не владеет чужими базами, S3 и BPMN engine: использует provider SPI и
внутренние API data-, attachment- и workflow-service.

Публикует только `/internal/v1` для gateway и авторизованных сервисов. В
Compose использует native-data и native-permissions provider; конфигурационный
release монтируется read-only. Для запуска требуются mTLS, доступ к data,
attachment и workflow services и `CORELIA_CONFIG_PATH`.

```bash
mvn -pl corelia-document-service -am test
./scripts/up.sh
```

Внешний контракт описан в [API](../docs/api.md), версии — в
[document-versioning](../docs/document-versioning.md).
