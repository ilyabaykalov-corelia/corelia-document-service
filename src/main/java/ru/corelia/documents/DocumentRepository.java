package ru.corelia.documents;

import ru.corelia.auth.AuthContext;

import tools.jackson.databind.JsonNode;

import java.util.List;

/** Порт чтения и изменения карточек. Бизнес-правила принадлежат ядру. */
public interface DocumentRepository {
    List<JsonNode> all(String type, AuthContext auth);

    JsonNode get(String type, String id, AuthContext auth);


}
