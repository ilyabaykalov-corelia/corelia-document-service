package ru.corelia.documents;

import ru.corelia.auth.AuthContext;
import tools.jackson.databind.JsonNode;
import java.util.List;

/** Atomic persistence port. Tokens and handles are opaque to the domain service. */
public interface DocumentVersionRepository {
    JsonNode document(String type, String id, AuthContext auth);
    List<JsonNode> versions(String id, AuthContext auth);
    List<JsonNode> attachments(String id, AuthContext auth);
    JsonNode receipt(String key, AuthContext auth);
    void commit(JsonNode document, JsonNode attributes, int version, JsonNode createdVersion,
                JsonNode changedVersion, JsonNode createdFile, JsonNode retiredFile,
                String key, String hash, JsonNode response, AuthContext auth);
}
