package ru.corelia.documents;

import static ru.corelia.support.Json.*;

import org.springframework.stereotype.Service;

import ru.corelia.auth.AuthContext;
import ru.corelia.http.ApiException;
import ru.corelia.integration.PdsContract;
import ru.corelia.support.LogJson;
import ru.corelia.transport.ServiceClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.*;

/** API карточек ПДС; история управляется ядром, создание пока делегируется существующему процессу. */
@Service
public class DocumentService {
    private final DocumentVersionService versions;
    private final DocumentRepository repository;
    private final ServiceClient services;

    public DocumentService(DocumentRepository repository, ServiceClient services, DocumentVersionService versions) {
        this.versions = versions;
        this.repository = repository;
        this.services = services;
    }

    public JsonNode get(String type, String id, AuthContext auth) {
        PdsContract.requireType(type);
        return versions.get(type, id, null, auth);
    }

    public JsonNode search(String type, JsonNode payload, AuthContext auth) {
        PdsContract.requireType(type);
        String query = text(payload, "query").toLowerCase(Locale.ROOT),
                status = PdsContract.status(text(payload, "status"));
        String from = date(text(payload, "dateFrom")),
                to = date(text(payload, "dateTo")),
                dateField = "contractDate";
        List<String> sorting = List.of("contractDate", "contractNumber");
        List<JsonNode> result =
                repository.all(type, auth).stream()
                        .filter(
                                doc -> {
                                    if (status != null && !status.equals(text(doc, "status")))
                                        return false;
                                    String value = date(text(doc.path("attributes"), dateField));
                                    if (!from.isEmpty() && value.compareTo(from) < 0
                                            || !to.isEmpty() && value.compareTo(to) > 0)
                                        return false;
                                    if (query.isEmpty()
                                            || (text(doc, "id")
                                                            + " "
                                                            + text(doc, "typeName")
                                                            + " "
                                                            + text(doc, "statusLabel"))
                                                    .toLowerCase(Locale.ROOT)
                                                    .contains(query)) return true;
                                    return PdsContract.FIELDS.stream()
                                            .anyMatch(
                                                    field ->
                                                            text(doc.path("attributes"), field)
                                                                    .toLowerCase(Locale.ROOT)
                                                                    .contains(query));
                                })
                        .sorted(
                                (a, b) -> {
                                    for (String field : sorting) {
                                        int compared =
                                                text(b.path("attributes"), field)
                                                        .compareTo(
                                                                text(a.path("attributes"), field));
                                        if (compared != 0) return compared;
                                    }
                                    return text(a, "id").compareTo(text(b, "id"));
                                })
                        .toList();
        int offset = (int) Math.min(result.size(), Math.max(0, number(payload, "offset", 0))),
                limit = (int) Math.min(10000, Math.max(1, number(payload, "limit", 1000)));
        return object(
                "items",
                result.subList(offset, Math.min(result.size(), offset + limit)),
                "total",
                result.size());
    }

    public JsonNode update(String type, String id, JsonNode body, AuthContext auth) {
        return versions.update(type, id, body, auth);
    }

    public JsonNode create(String type, JsonNode body, AuthContext auth) {
        PdsContract.requireType(type);
        JsonNode attributes = PdsContract.validateAttributes(body.path("attributes"), false);
        String id = UUID.randomUUID().toString();
        JsonNode instance =
                services.call(
                        "workflow",
                        "/internal/v1/processes/start",
                        "POST",
                        object("typeCode", type, "documentId", id, "attributes", attributes),
                        auth);
        String instanceId = text(instance, "id");
        LogJson.info(
                "Platform V document process start response",
                object(
                        "documentId", id,
                        "documentType", type,
                        "processInstanceId", instanceId,
                        "state", text(instance, "state")));
        JsonNode variables = instance.path("globalVariables");
        String returned = text(unwrap(variables.path("documentId")));
        if (!returned.isEmpty()) id = returned;
        for (int attempt = 0; attempt < 30; attempt++) {
            try {
                ObjectNode result = copy(get(type, id, auth));
                result.put("processInstanceId", instanceId);
                return result;
            } catch (ApiException error) {
                if (error.status() != 404) throw error;
            }
            if (!instanceId.isEmpty() && attempt % 3 == 0) {
                try {
                    services.call(
                            "workflow",
                            "/internal/v1/processes/" + encode(instanceId),
                            "GET",
                            null,
                            auth);
                } catch (ApiException error) {
                    LogJson.info(
                            "Platform V process instance status is unavailable",
                            object(
                                    "documentId",
                                    id,
                                    "processInstanceId",
                                    instanceId,
                                    "status",
                                    error.status(),
                                    "message",
                                    error.getMessage()));
                    throw error;
                }
            }
            pause(500);
        }
        LogJson.info(
                "Platform V process did not create document in time",
                object("documentId", id, "processInstanceId", instanceId));
        throw new ApiException(
                502,
                "Процесс запущен, но карточка документа "
                        + id
                        + " не появилась в DataSpace; идентификатор процесса: "
                        + instanceId);
    }

    private static String date(String value) {
        if (value.matches("^\\d{2}\\.\\d{2}\\.\\d{4}.*"))
            return value.substring(6, 10)
                    + "-"
                    + value.substring(3, 5)
                    + "-"
                    + value.substring(0, 2);
        return value.length() > 10 ? value.substring(0, 10) : value;
    }

    public static void pause(long milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ApiException(503, "Ожидание платформы прервано");
        }
    }
}
