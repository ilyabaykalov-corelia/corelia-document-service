package ru.corelia.documents;

import static ru.corelia.support.Json.*;

import ru.corelia.auth.AuthContext;
import ru.corelia.http.ApiException;
import ru.corelia.integration.DocumentTypes;
import ru.corelia.transport.ServiceClient;
import tools.jackson.databind.JsonNode;

public final class ConfiguredDocumentPolicy implements DocumentPolicy {
    private final ServiceClient services;
    private final DocumentTypes types;
    private final String type;
    public ConfiguredDocumentPolicy(ServiceClient services, DocumentTypes types, String type) {
        this.services = services; this.types = types; this.type = type;
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
        if (!auth.roles().contains("document_operator") && !auth.roles().contains("app_owner"))
            throw new ApiException(403, "Изменение документа доступно оператору");
        String status = text(doc, "status");
        if (action.equals("upload") && status.equals("CREATED") && auth.login().equals(text(doc, "createdBy"))) return;
        if (!status.equals("IN_WORK")) throw new ApiException(409, "Документ недоступен для изменения на текущем шаге");
        JsonNode workflow = services.call("workflow", "/internal/v1/documents/" + type() + "/" + encode(text(doc, "documentId")) + "/workflow", "GET", null, auth);
        if (!auth.login().equals(text(workflow.path("executor"), "login"))
                || !"document_operator".equals(text(workflow.path("executor"), "role")))
            throw new ApiException(403, "Документ может изменять назначенный оператор");
    }
}
