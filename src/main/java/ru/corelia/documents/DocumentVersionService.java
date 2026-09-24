package ru.corelia.documents;

import static ru.corelia.support.Json.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import ru.corelia.auth.AuthContext;
import ru.corelia.configuration.DocumentTypeCatalog;
import ru.corelia.http.ApiException;
import ru.corelia.provider.DocumentStore;
import ru.corelia.provider.DocumentVersionStore;
import ru.corelia.provider.model.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Бизнес-правила версий не зависят от хранилища и workflow-платформы. */
@Service
public class DocumentVersionService {
    private final DocumentVersionStore versions;
    private final DocumentStore documents;
    private final List<DocumentPolicy> policies;
    private final DocumentTypeCatalog types;
    public DocumentVersionService(DocumentVersionStore versions, DocumentStore documents, List<DocumentPolicy> policies, DocumentTypeCatalog types) {
        this.versions = versions; this.documents = documents; this.policies = List.copyOf(policies); this.types = types;
    }
    private static Instant now() { return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS).toInstant(ZoneOffset.UTC); }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    private DocumentPolicy policy(String type) { return policies.stream().filter(value -> value.type().equals(type)).findFirst()
            .orElseThrow(() -> new ApiException(400, "Неизвестный вид документа")); }
    private static ObjectNode attributes(Map<String, JsonNode> values) { var result = object(); values.forEach(result::set); return result; }
    private static Map<String, JsonNode> map(JsonNode values) { var result = new LinkedHashMap<String, JsonNode>(); values.properties().forEach(item -> result.put(item.getKey(), item.getValue())); return result; }
    private ObjectNode document(DocumentSnapshot value) {
        var result = object("id", value.id(), "documentId", value.id(), "typeCode", value.typeCode(), "typeName", types.name(value.typeCode()),
                "status", value.status(), "statusLabel", types.label(value.typeCode(), value.status()), "statusTone", types.tone(value.typeCode(), value.status()),
                "version", value.currentVersion(), "changeToken", value.changeToken(), "attributes", attributes(value.attributes()));
        if (!value.createdBy().isEmpty()) result.put("createdBy", value.createdBy()); if (value.createdAt() != null) result.put("createdAt", value.createdAt().toString()); return result;
    }
    private DocumentVersion snapshot(DocumentSnapshot value, int number, ObjectNode attrs, List<AttachmentMetadata> files, AuthContext auth) {
        return new DocumentVersion("", value.id(), number, policy(value.typeCode()).schemaVersion(), map(attrs), "", now(), auth.login(), null, files);
    }
    private static DocumentVersion changed(DocumentVersion value, Instant closedAt, List<AttachmentMetadata> files) {
        return new DocumentVersion(value.id(), value.documentId(), value.number(), value.schemaVersion(), value.attributes(), value.status(), value.createdAt(), value.createdBy(), closedAt, files);
    }
    private DocumentVersionState state(String type, String id, AuthContext auth) {
        for (int attempt = 0; attempt < 4; attempt++) {
            DocumentVersionState value = versions.state(type, id, auth);
            if (value.document().currentVersion() != 0) return value;
            DocumentVersion first = snapshot(value.document(), 1, attributes(value.document().attributes()), value.attachments().stream().filter(AttachmentMetadata::current).toList(), auth);
            String key = digest("initialize:" + id);
            try { versions.commit(new DocumentMutation(id, type, 0, value.document().changeToken(), value.document().attributes(), first, null, null, null, key, key, object("version", 1)), auth); }
            catch (ApiException error) { if (versions.state(type, id, auth).document().currentVersion() == 0) throw error; }
        }
        throw new ApiException(409, "Документ изменяется. Повторите чтение.");
    }
    private ObjectNode view(String type, DocumentVersionState state, DocumentVersion selected) {
        policy(type).checkSchema(selected.schemaVersion()); var result = document(state.document());
        result.set("attributes", attributes(selected.attributes())); result.put("version", selected.number()); result.put("currentVersion", state.document().currentVersion());
        result.put("versionCreatedBy", selected.createdBy()); if (selected.createdAt() != null) result.put("versionCreatedAt", selected.createdAt().toString());
        result.set("attachments", array(selected.attachments().stream().map(DocumentVersionService::publicFile).toList())); return result;
    }
    public JsonNode getById(String id, AuthContext auth) { return get(versions.documentType(id, auth), id, null, auth); }
    public JsonNode get(String type, String id, Integer number, AuthContext auth) {
        DocumentVersionState state = state(type, id, auth); DocumentVersion selected = number == null ? state.currentVersion() : state.versions().stream().filter(value -> value.number() == number).findFirst().orElseThrow(() -> new ApiException(404, "Версия документа не найдена"));
        return view(type, state, selected);
    }
    public JsonNode versions(String type, String id, AuthContext auth) {
        DocumentVersionState state = state(type, id, auth);
        return object("items", state.versions().stream().sorted(Comparator.comparingInt(DocumentVersion::number).reversed()).map(value -> object("version", value.number(), "createdBy", value.createdBy(), "createdAt", timestamp(value.createdAt()), "closedAt", timestamp(value.closedAt()), "current", value.number() == state.document().currentVersion())).toList());
    }
    /** Формирует журнал из неизменяемых снимков, не завися от реализации хранилища. */
    public JsonNode history(String type, String id, AuthContext auth) {
        List<DocumentVersion> snapshots = new ArrayList<>(state(type, id, auth).versions());
        snapshots.sort(Comparator.comparingInt(DocumentVersion::number));
        var items = new ArrayList<JsonNode>();
        for (int index = 0; index < snapshots.size(); index++) {
            DocumentVersion current = snapshots.get(index);
            if (index == 0) { items.add(historyItem(current, "DOCUMENT_CREATED", "created", "", null, null, null)); continue; }
            DocumentVersion previous = snapshots.get(index - 1);
            var fields = new TreeSet<String>(); fields.addAll(previous.attributes().keySet()); fields.addAll(current.attributes().keySet());
            for (String field : fields) {
                JsonNode oldValue = previous.attributes().get(field), newValue = current.attributes().get(field);
                if (Objects.equals(oldValue, newValue)) continue;
                String action = empty(oldValue) ? "ATTRIBUTE_SET" : empty(newValue) ? "ATTRIBUTE_CLEARED" : "ATTRIBUTE_CHANGED";
                items.add(historyItem(current, action, field, label(type, field), oldValue, newValue, null));
            }
            Map<String, AttachmentMetadata> oldFiles = files(previous.attachments()), newFiles = files(current.attachments());
            var attachmentIds = new TreeSet<String>(); attachmentIds.addAll(oldFiles.keySet()); attachmentIds.addAll(newFiles.keySet());
            for (String logicalId : attachmentIds) {
                AttachmentMetadata oldFile = oldFiles.get(logicalId), newFile = newFiles.get(logicalId);
                if (oldFile == null) items.add(historyItem(current, "ATTACHMENT_ADDED", "", "", null, null, attachment(null, newFile)));
                else if (newFile == null) items.add(historyItem(current, "ATTACHMENT_DELETED", "", "", null, null, attachment(oldFile, null)));
                else if (!oldFile.id().equals(newFile.id())) items.add(historyItem(current, "ATTACHMENT_REPLACED", "", "", null, null, attachment(oldFile, newFile)));
            }
        }
        items.sort(Comparator.comparing((JsonNode value) -> value.path("timestamp").asText()).reversed());
        return object("items", items);
    }
    private static boolean empty(JsonNode value) { return value == null || value.isNull() || (value.isTextual() && value.asText().isEmpty()); }
    private String label(String type, String field) { String value = text(types.publicDefinition(type).path("schema").path("properties").path(field), "title"); return value.isEmpty() ? field : value; }
    private static Map<String, AttachmentMetadata> files(List<AttachmentMetadata> values) { var result = new HashMap<String, AttachmentMetadata>(); values.forEach(value -> result.put(value.logicalId(), value)); return result; }
    private static JsonNode attachment(AttachmentMetadata oldFile, AttachmentMetadata newFile) {
        var result = object("attachmentId", newFile != null ? newFile.logicalId() : oldFile.logicalId());
        if (oldFile != null) result.put("oldFileName", oldFile.fileName()); if (newFile != null) result.put("newFileName", newFile.fileName()); return result;
    }
    private static JsonNode historyItem(DocumentVersion version, String action, String field, String fieldLabel, JsonNode oldValue, JsonNode newValue, JsonNode attachment) {
        var result = object("id", version.number() + ":" + action + ":" + field + ":" + (attachment == null ? "" : text(attachment, "attachmentId")), "timestamp", timestamp(version.createdAt()), "userLogin", version.createdBy(), "action", action);
        if (!field.isEmpty()) { result.put("field", field); result.put("fieldLabel", fieldLabel); }
        if (oldValue != null) result.set("oldValue", oldValue); if (newValue != null) result.set("newValue", newValue); if (attachment != null) result.set("attachment", attachment); return result;
    }
    private static String timestamp(Instant value) { return value == null ? "" : value.toString(); }
    private static String requestKey(String id, JsonNode body, AuthContext auth) {
        String requestId = text(body, "requestId"); try { UUID.fromString(requestId); } catch (IllegalArgumentException error) { throw new ApiException(400, "Требуется requestId в формате UUID"); }
        return digest(id + ":" + auth.login() + ":" + requestId);
    }
    private JsonNode replay(String key, String hash, AuthContext auth) {
        IdempotencyReceipt receipt = versions.receipt(key, auth); if (receipt == null) return null;
        if (!hash.equals(receipt.requestHash())) throw new ApiException(409, "requestId уже использован для другой команды"); return receipt.response();
    }
    public JsonNode capabilities(String type, String id, AuthContext auth) {
        DocumentVersionState state = state(type, id, auth); DocumentPolicy rules = policy(type); JsonNode doc = document(state.document()); JsonNode context = rules.authorizationContext(doc, auth); var actions = new ArrayList<String>(); int count = state.currentVersion().attachments().size();
        for (String action : List.of("attributes", "upload", "replace", "delete")) try {
            rules.authorize(doc, action, auth, context); if (!action.equals("attributes")) { if (!action.equals("upload") && count == 0) continue; rules.validateAttachmentCount(count + (action.equals("upload") ? 1 : action.equals("delete") ? -1 : 0)); }
            actions.add(switch (action) { case "attributes" -> "EDIT"; case "upload" -> "ADD_ATTACHMENT"; case "replace" -> "REPLACE_ATTACHMENT"; default -> "DELETE_ATTACHMENT"; });
        } catch (ApiException error) { if (error.status() != 400 && error.status() != 403 && error.status() != 409) throw error; }
        return object("capabilities", actions);
    }
    public JsonNode update(String type, String id, JsonNode body, AuthContext auth) {
        ObjectNode patch = copy(policy(type).validate(body.path("attributes"))); String key = requestKey(id, body, auth), hash = digest(write(object("action", "attributes", "body", body)));
        documents.get(type, id, auth); JsonNode prior = replay(key, hash, auth); if (prior != null) return prior;
        DocumentVersionState state = state(type, id, auth); policy(type).authorize(document(state.document()), "attributes", auth); policy(type).checkSchema(state.currentVersion().schemaVersion());
        if (number(body, "expectedVersion", -1) != state.document().currentVersion() || !text(body, "changeToken").equals(state.document().changeToken())) throw new ApiException(409, "Документ или вложения изменены. Обновите карточку перед сохранением.");
        ObjectNode attrs = attributes(state.currentVersion().attributes()); patch.properties().forEach(item -> attrs.set(item.getKey(), item.getValue())); policy(type).validateSnapshot(attrs);
        boolean differs = !attrs.equals(attributes(state.currentVersion().attributes())); int number = state.document().currentVersion() + (differs ? 1 : 0);
        DocumentVersion created = differs ? snapshot(state.document(), number, attrs, state.currentVersion().attachments(), auth) : null;
        DocumentVersion closed = differs ? changed(state.currentVersion(), now(), state.currentVersion().attachments()) : null;
        JsonNode response = copy(view(type, state, created == null ? state.currentVersion() : created)).put("currentVersion", number).put("changeToken", UUID.randomUUID().toString());
        return commit(state, attrs, created, closed, null, null, key, hash, response, auth);
    }
    private JsonNode commit(DocumentVersionState state, ObjectNode attributes, DocumentVersion created, DocumentVersion changed, AttachmentMetadata file, AttachmentMetadata retired, String key, String hash, JsonNode response, AuthContext auth) {
        try { versions.commit(new DocumentMutation(state.document().id(), state.document().typeCode(), state.document().currentVersion(), state.document().changeToken(), map(attributes), created, changed, file, retired, key, hash, response), auth); }
        catch (ApiException error) {
            JsonNode prior = replay(key, hash, auth); if (prior != null) return prior;
            DocumentVersionState actual = versions.state(state.document().typeCode(), state.document().id(), auth);
            if (actual.document().currentVersion() != state.document().currentVersion()
                    || !Objects.equals(actual.document().changeToken(), state.document().changeToken()))
                throw new ApiException(409, "Документ изменён другим запросом. Обновите карточку.");
            throw error;
        } return response;
    }
    public JsonNode attachment(String type, String id, JsonNode body, AuthContext auth) {
        String action = text(body, "action"); if (!Set.of("upload", "replace", "delete").contains(action)) throw new ApiException(400, "Неизвестная команда вложения");
        ObjectNode canonical = copy(body); if (canonical.path("file").isObject()) ((ObjectNode) canonical.path("file")).remove("uploadedAt"); String key = requestKey(id, body, auth), hash = digest(write(canonical)); documents.get(type, id, auth); JsonNode prior = replay(key, hash, auth); if (prior != null) return prior;
        DocumentVersionState state = state(type, id, auth); policy(type).authorize(document(state.document()), action, auth); policy(type).checkSchema(state.currentVersion().schemaVersion()); List<AttachmentMetadata> manifest = new ArrayList<>(state.currentVersion().attachments()); AttachmentMetadata old = null;
        if (!action.equals("upload")) { old = manifest.stream().filter(file -> text(body, "attachmentId").equals(file.id())).findFirst().orElseThrow(() -> new ApiException(409, "Вложение уже заменено или удалено. Обновите карточку.")); manifest.remove(old); }
        AttachmentMetadata created = null; if (!action.equals("delete")) { created = attachment(body.path("file"), id, old == null ? 1 : old.version() + 1, old == null ? "" : old.logicalId()); manifest.add(created); }
        policy(type).validateAttachmentCount(manifest.size()); int number = state.document().currentVersion() + 1;
        DocumentVersion next = snapshot(state.document(), number, attributes(state.currentVersion().attributes()), manifest, auth);
        DocumentVersion changed = changed(state.currentVersion(), now(), state.currentVersion().attachments()); JsonNode response = created == null ? object("deleted", true) : publicFile(created);
        String retiredId = old == null ? "" : old.id();
        AttachmentMetadata retired = old == null ? null : state.attachments().stream().filter(file -> file.id().equals(retiredId)).findFirst().orElseThrow(() -> new ApiException(502, "Не найдены метаданные вложения"));
        return commit(state, attributes(state.currentVersion().attributes()), next, changed, created, retired, key, hash, response, auth);
    }
    private static AttachmentMetadata attachment(JsonNode file, String documentId, long version, String logicalId) {
        String id = first(file, "attachmentId", "id"); if (!documentId.equals(text(file, "documentId")) || id.isEmpty() || text(file, "storageReference").isEmpty()) throw new ApiException(400, "Неверные метаданные вложения");
        Instant uploadedAt; try { uploadedAt = Instant.parse(text(file, "uploadedAt")); } catch (RuntimeException ignored) { uploadedAt = null; }
        return new AttachmentMetadata(id, logicalId.isEmpty() ? id : logicalId, documentId, text(file, "fileName"), text(file, "contentType"), number(file, "size", 0), version, true, uploadedAt, new StorageReference(text(file, "storageReference")));
    }
    public static JsonNode publicFile(AttachmentMetadata file) {
        var result = object("id", file.id(), "logicalAttachmentId", file.logicalId(), "documentId", file.documentId(), "fileName", file.fileName(), "contentType", file.contentType(), "size", file.size(), "version", file.version(), "current", file.current()); if (file.uploadedAt() != null) result.put("uploadedAt", file.uploadedAt().toString()); return result;
    }
}
