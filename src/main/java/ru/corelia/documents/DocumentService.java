package ru.corelia.documents;

import static ru.corelia.support.Json.*;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import ru.corelia.auth.AuthContext;
import ru.corelia.http.ApiException;
import ru.corelia.configuration.DocumentTypeCatalog;
import ru.corelia.support.LogJson;
import ru.corelia.support.FileNames;
import ru.corelia.transport.ServiceClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.*;

/** API карточек документов; история управляется ядром, создание пока делегируется существующему процессу. */
@Service
public class DocumentService {
    private final DocumentTypeCatalog types;
    private final ru.corelia.auth.PermissionChecker permissions;
    private final DocumentVersionService versions;
    private final DocumentRepository repository;
    private final DocumentVersionRepository versionRepository;
    private final ServiceClient services;

    public DocumentService(ru.corelia.auth.PermissionChecker permissions, DocumentTypeCatalog types, DocumentRepository repository, ServiceClient services, DocumentVersionService versions, DocumentVersionRepository versionRepository) {
        this.permissions = permissions;
        this.types = types;
        this.versionRepository = versionRepository;
        this.versions = versions;
        this.repository = repository;
        this.services = services;
    }

    public JsonNode get(String type, String id, AuthContext auth) {
        types.requireType(type);
        var result = copy(versions.get(type, id, null, auth));
        result.put("workflowCompleted", list(types.definition(type).workflow().path("terminalStatuses")).stream()
            .anyMatch(status -> text(status).equals(text(result, "status"))));
        return result;
    }

    public JsonNode searchAll(JsonNode body, AuthContext auth) {
        var filters = copy(body).put("offset", 0).put("limit", 10000);
        var all = new ArrayList<JsonNode>();
        for (String type : types.types()) all.addAll(list(search(type, filters, auth).path("items")));
        all.sort(Comparator.comparing((JsonNode d) -> text(d, "createdAt")).reversed().thenComparing(d -> text(d, "id")));
        int offset = (int)Math.min(all.size(), Math.max(0, number(body, "offset", 0)));
        int limit = (int)Math.min(10000, Math.max(1, number(body, "limit", 1000)));
        return object("items", all.subList(offset, Math.min(all.size(), offset + limit)), "total", all.size());
    }

    public JsonNode search(String type, JsonNode payload, AuthContext auth) {
        types.requireType(type);
        String query = text(payload, "query").toLowerCase(Locale.ROOT),
                status = types.status(type, text(payload, "status"));
        if (!text(payload, "status").isEmpty() && status == null) return object("items", List.of(), "total", 0);
        String from = date(text(payload, "dateFrom")),
                to = date(text(payload, "dateTo")),
                dateField = text(types.definition(type).ui(), "dateField");
        if (dateField.isEmpty() && (!from.isEmpty() || !to.isEmpty()))
            throw new ApiException(400, "Для этого вида не настроен поиск по дате");
        List<String> sorting = list(types.definition(type).ui().path("sortFields")).stream().map(v -> v.asString()).toList();
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
                                    return list(types.definition(type).ui().path("searchFields")).stream().map(v -> v.asString())
                                            .anyMatch(
                                                    field ->
                                                            searchValue(doc.path("attributes").path(field))
                                                                    .toLowerCase(Locale.ROOT)
                                                                    .contains(query));
                                })
                        .sorted(
                                (a, b) -> {
                                    for (String field : sorting) {
                                        int compared =
                                                compareAttribute(b.path("attributes").path(field), a.path("attributes").path(field));
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
        types.requireType(type);
        permissions.require(text(types.definition(type).authorization(), "createPermission"), auth);
        JsonNode attributes = types.validate(type, body.path("attributes"), false);
        String id = UUID.randomUUID().toString();
        var start = object("typeCode", type, "attributes", attributes);
        boolean hasStagedAttachment = body.path("stagedInitialAttachment").isObject();
        if (types.initialAttachmentRequired(type) || hasStagedAttachment) {
            String requestId = text(body, "requestId");
            try { UUID.fromString(requestId); } catch (IllegalArgumentException e) { throw new ApiException(400, "Для создания требуется requestId UUID"); }
            JsonNode file = hasStagedAttachment ? body.path("stagedInitialAttachment") : body.path("initialAttachment");
            if (!file.isObject() || !hasStagedAttachment && text(file, "contentBase64").isEmpty())
                throw new ApiException(400, "Для создания документа требуется вложение");
            id = UUID.nameUUIDFromBytes((auth.login() + ":" + type + ":" + requestId).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
            String key = hash("create:" + id), requestHash = hash(write(object("attributes", attributes, "file", file)));
            JsonNode receipt = versionRepository.receipt(key, auth);
            if (receipt != null) {
                if (!requestHash.equals(text(receipt, "requestHash"))) throw new ApiException(409, "requestId уже использован для других данных");
                return get(type, id, auth);
            }
            JsonNode staged = hasStagedAttachment
                    ? file
                    : services.call("attachment", "/internal/v1/initial-attachments/" + encode(id), "POST", object("attachment", file), auth);
            start.set("initialAttachment", staged);
            start.put("creationKey", key); start.put("creationHash", requestHash);
        }
        start.put("documentId", id);
        JsonNode instance =
                services.call(
                        "workflow",
                        "/internal/v1/processes/start",
                        "POST",
                        start,
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

    public JsonNode createStream(
            String type, String requestId, JsonNode attributes, MultipartFile file, AuthContext auth) {
        types.requireType(type);
        if (file.isEmpty()) throw new ApiException(400, "Для создания документа требуется вложение");
        String id = UUID.nameUUIDFromBytes((auth.login() + ":" + type + ":" + requestId).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        JsonNode staged;
        try (var content = file.getInputStream()) {
            staged = services.callMultipart(
                    "attachment",
                    "/internal/v1/staged-attachments/" + encode(id),
                    "POST",
                    Map.of("requestId", requestId),
                    FileNames.safe(file.getOriginalFilename() == null ? "attachment.bin" : file.getOriginalFilename()),
                    file.getContentType() == null ? "application/octet-stream" : file.getContentType(),
                    content,
                    auth);
        } catch (java.io.IOException error) {
            throw new ApiException(400, "Не удалось прочитать загружаемый файл");
        }
        return create(type, object("attributes", attributes, "requestId", requestId, "stagedInitialAttachment", staged), auth);
    }

    private static String searchValue(JsonNode value) {
        return value.isTextual() || value.isNumber() || value.isBoolean() ? value.asString() : "";
    }
    private static int compareAttribute(JsonNode left, JsonNode right) {
        if (left.isNumber() && right.isNumber())
            return new java.math.BigDecimal(left.asString()).compareTo(new java.math.BigDecimal(right.asString()));
        return searchValue(left).compareTo(searchValue(right));
    }

    private static String hash(String value) {
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
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
