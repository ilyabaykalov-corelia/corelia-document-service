package ru.corelia.documents;

import ru.corelia.auth.AuthContext;
import ru.corelia.http.ApiException;
import tools.jackson.databind.JsonNode;

/** Правила вида документа; алгоритм истории не зависит от состава его реквизитов. */
public interface DocumentPolicy {
    String type();
    int schemaVersion();
    JsonNode validate(JsonNode attributes);
    void authorize(JsonNode document, String action, AuthContext auth);
    default void checkSchema(int version) {
        if (version != schemaVersion())
            throw new ApiException(409, "Версия схемы атрибутов не поддерживается этим видом документа");
    }
}
