package ru.corelia.documents;

import ru.corelia.auth.AuthContext;

import tools.jackson.databind.JsonNode;

import java.util.List;

/** Порт чтения и изменения карточек. Владельцем данных остаётся выбранная платформа. */
public interface DocumentRepository {
    List<JsonNode> all(String type, AuthContext auth);

    JsonNode get(String type, String id, AuthContext auth);

    void update(String type, String id, JsonNode attributes, AuthContext auth);
}
