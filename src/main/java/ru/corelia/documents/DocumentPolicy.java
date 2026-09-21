package ru.corelia.documents;

import ru.corelia.auth.AuthContext;
import ru.corelia.http.ApiException;
import tools.jackson.databind.JsonNode;

/** Правила вида документа; алгоритм истории не зависит от состава его реквизитов. */
public interface DocumentPolicy {
    String type();
    int schemaVersion();
    JsonNode validate(JsonNode attributes);
    default void validateSnapshot(JsonNode attributes) {}
    default void validateAttachmentCount(int count) {}
    void authorize(JsonNode document, String action, AuthContext auth);
    default JsonNode authorizationContext(JsonNode document, AuthContext auth) { return null; }
    default void authorize(JsonNode document, String action, AuthContext auth, JsonNode context) {
        authorize(document, action, auth);
    }
    default void checkSchema(int version) {
        if (version != schemaVersion())
            throw new ApiException(409, "Версия схемы атрибутов не поддерживается этим видом документа");
    }
}
