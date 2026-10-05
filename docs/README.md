# Документация document-service

Документная команда читает согласованный version state у data owner, проверяет
`expectedVersion`/`changeToken` и передаёт `DocumentMutation` для атомарной
фиксации в data-service. `requestId` используется для сохранённого результата
повтора. При конфликте клиент должен перечитать карточку, а не повторять
устаревшее изменение вслепую.

Файловая операция координирует attachment-service и изменение состава
документа, но не образует distributed transaction с S3. Workflow-service
владеет запуском и task transition. См. [API](../../docs/api.md) и
[provider SPI](../../docs/provider-spi.md).
