package ru.corelia.documents;

import static ru.corelia.support.Json.*;
import org.springframework.stereotype.Component;
import ru.corelia.auth.AuthContext;
import ru.corelia.http.ApiException;
import ru.corelia.integration.PdsContract;
import ru.corelia.transport.ServiceClient;
import tools.jackson.databind.JsonNode;

@Component
public final class PdsDocumentPolicy implements DocumentPolicy {
    private final ServiceClient services;
    public PdsDocumentPolicy(ServiceClient services) { this.services = services; }
    public String type() { return PdsContract.TYPE; }
    public int schemaVersion() { return 1; }
    public JsonNode validate(JsonNode attributes) { return PdsContract.validateAttributes(attributes, true); }
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
