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
import ru.corelia.provider.DocumentStore;
import ru.corelia.provider.DocumentVersionStore;
import ru.corelia.provider.model.DocumentSearchRequest;
import ru.corelia.provider.model.DocumentSnapshot;
import ru.corelia.provider.model.DocumentCreation;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.*;

/** API карточек документов; история и создание управляются ядром. */
@Service
public class DocumentService {
    private final DocumentTypeCatalog types;
    private final ru.corelia.provider.PermissionProvider permissions;
    private final DocumentVersionService versions;
    private final DocumentStore store;
    private final DocumentVersionStore versionStore;
    private final ServiceClient services;

    public DocumentService(ru.corelia.provider.PermissionProvider permissions, DocumentTypeCatalog types, DocumentStore store, ServiceClient services, DocumentVersionService versions, DocumentVersionStore versionStore) {
        this.permissions = permissions;
        this.types = types;
        this.versionStore = versionStore;
        this.versions = versions;
        this.store = store;
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
                store.search(new DocumentSearchRequest(type, 0, 10000), auth).items().stream().map(this::publicDocument)
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
        String requestId = text(body, "requestId");
        try { UUID.fromString(requestId); } catch (IllegalArgumentException e) { throw new ApiException(400, "Для создания требуется requestId UUID"); }
        String id = UUID.nameUUIDFromBytes((auth.login() + ":" + type + ":" + requestId).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        String creationKey = hash("create:" + id);
        JsonNode suppliedAttachment = body.path("stagedInitialAttachment").isObject() ? body.path("stagedInitialAttachment") : body.path("initialAttachment");
        String creationHash = hash(write(object("attributes", attributes, "file", suppliedAttachment)));
        var receipt = versionStore.receipt(creationKey, auth);
        if (receipt != null && !creationHash.equals(receipt.requestHash()))
            throw new ApiException(409, "requestId уже использован для других данных");
        if (receipt == null)
            store.create(new DocumentCreation(id, type, map(attributes), types.initialStatus(type),
                    auth.login(), java.time.Instant.now(), null, creationKey, creationHash), auth);

        JsonNode initialAttachment = body.path("initialAttachment");
        if (initialAttachment.isObject())
            services.call("attachment", "/internal/v1/documents/" + encode(type) + "/" + encode(id) + "/attachments", "POST",
                    object("requestId", requestId, "attachments", List.of(initialAttachment)), auth);
        if (types.initialAttachmentRequired(type) && !initialAttachment.isObject()) return get(type, id, auth);

        JsonNode instance = startWorkflow(type, id, attributes, creationKey, creationHash, auth);
        String instanceId = text(instance, "id");
        LogJson.info(
                "Получен ответ о запуске процесса документа",
                object(
                        "documentId", id,
                        "documentType", type,
                        "processInstanceId", instanceId,
                        "state", text(instance, "state")));
        ObjectNode result = copy(get(type, id, auth));
        result.put("processInstanceId", instanceId);
        return result;
    }

    /** Запускает процесс только для уже сохранённого документа, когда выполнены configured prerequisites. */
    public JsonNode startWorkflowWhenReady(String type, String id, AuthContext auth) {
        types.requireType(type);
        var document = store.get(type, id, auth);
        if (types.initialAttachmentRequired(type)
                && versionStore.attachments(id, auth).stream().noneMatch(ru.corelia.provider.model.AttachmentMetadata::current))
            return object("started", false, "documentId", id, "state", "WAITING_FOR_ATTACHMENT");
        return startWorkflow(type, id, attributes(document.attributes()), "", "", auth);
    }

    private JsonNode startWorkflow(
            String type, String id, JsonNode attributes, String creationKey, String creationHash, AuthContext auth) {
        return services.call(
                "workflow",
                "/internal/v1/processes/start",
                "POST",
                object("typeCode", type, "documentId", id, "attributes", attributes,
                        "creationKey", creationKey, "creationHash", creationHash),
                auth);
    }

    private static ObjectNode attributes(java.util.Map<String, JsonNode> values) {
        ObjectNode result = object();
        values.forEach(result::set);
        return result;
    }

    public JsonNode createStream(
            String type, String requestId, JsonNode attributes, MultipartFile file, AuthContext auth) {
        types.requireType(type);
        if (file.isEmpty()) throw new ApiException(400, "Для создания документа требуется вложение");
        String id = UUID.nameUUIDFromBytes((auth.login() + ":" + type + ":" + requestId).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        byte[] content;
        try (var input = file.getInputStream()) {
            content = input.readAllBytes();
        } catch (java.io.IOException error) {
            throw new ApiException(400, "Не удалось прочитать загружаемый файл");
        }
        return create(type, object("attributes", attributes, "requestId", requestId,
                "initialAttachment", object("fileName", FileNames.safe(file.getOriginalFilename() == null ? "attachment.bin" : file.getOriginalFilename()),
                        "contentType", file.getContentType() == null ? "application/octet-stream" : file.getContentType(),
                        "contentBase64", Base64.getEncoder().encodeToString(content))), auth);
    }

    private static String searchValue(JsonNode value) {
        return value.isTextual() || value.isNumber() || value.isBoolean() ? value.asString() : "";
    }

    private static Map<String, JsonNode> map(JsonNode node) {
        var result = new LinkedHashMap<String, JsonNode>();
        node.properties().forEach(entry -> result.put(entry.getKey(), entry.getValue().deepCopy()));
        return result;
    }


    private JsonNode publicDocument(DocumentSnapshot document) {
        var attributes = object();
        document.attributes().forEach(attributes::set);
        var result = object("id", document.id(), "typeCode", document.typeCode(), "typeName", types.name(document.typeCode()),
                "attributes", attributes, "status", document.status(), "statusLabel", types.label(document.typeCode(), document.status()),
                "statusTone", types.tone(document.typeCode(), document.status()));
        if (!document.createdBy().isEmpty()) result.put("createdBy", document.createdBy());
        if (document.createdAt() != null) result.put("createdAt", document.createdAt().toString());
        return result;
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
