package ru.corelia.documents;

import static ru.corelia.support.Json.*;

import ru.corelia.auth.AuthContext;
import ru.corelia.http.ApiException;
import ru.corelia.configuration.DocumentTypeCatalog;
import ru.corelia.transport.ServiceClient;
import tools.jackson.databind.JsonNode;

public final class ConfiguredDocumentPolicy implements DocumentPolicy {
    private final ServiceClient services;
    private final DocumentTypeCatalog types;
    private final String type;
    private final ru.corelia.provider.PermissionProvider permissions;
    public ConfiguredDocumentPolicy(ServiceClient services, DocumentTypeCatalog types, String type, ru.corelia.provider.PermissionProvider permissions) {
        this.permissions = permissions; this.services = services; this.types = types; this.type = type;
    }
    public String type() { return type; }
    public int schemaVersion() { return types.definition(type).schemaVersion(); }
    public JsonNode validate(JsonNode attributes) { return types.validate(type, attributes, true); }
    public void validateSnapshot(JsonNode attributes) { types.validate(type, attributes, false); }
    public void validateAttachmentCount(int count) {
        JsonNode policy = types.definition(type).attachments();
        if (!policy.path("enabled").asBoolean() || count > policy.path("maxCount").asInt())
            throw new ApiException(400, "Превышен допустимый состав вложений документа");
    }
    public void authorize(JsonNode doc, String action, AuthContext auth) {
        authorize(doc, action, auth, authorizationContext(doc, auth));
    }
    public JsonNode authorizationContext(JsonNode doc, AuthContext auth) {
        return services.call("workflow", "/internal/v1/documents/" + type() + "/" + encode(text(doc, "documentId")) + "/workflow", "GET", null, auth);
    }
    public void authorize(JsonNode doc, String action, AuthContext auth, JsonNode workflow) {
        JsonNode rules = types.definition(type).authorization();
        permissions.require(text(rules, "editPermission"), auth);
        String status = text(doc, "status");
        if (action.equals("upload") && list(rules.path("initialUploadStatuses")).stream().anyMatch(v -> text(v).equals(status)) && auth.login().equals(text(doc, "createdBy"))) return;
        if (list(rules.path("editableStatuses")).stream().noneMatch(v -> text(v).equals(status))) throw new ApiException(409, "Документ недоступен для изменения на текущем шаге");
        if (!auth.login().equals(text(workflow.path("executor"), "login"))
                || !text(rules, "executorRole").equals(text(workflow.path("executor"), "role")))
            throw new ApiException(403, "Документ может изменять назначенный исполнитель");
    }
}
