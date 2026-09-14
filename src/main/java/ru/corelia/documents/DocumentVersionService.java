package ru.corelia.documents;

import static ru.corelia.support.Json.*;
import org.springframework.stereotype.Service;
import ru.corelia.auth.AuthContext;
import ru.corelia.http.ApiException;
import ru.corelia.integration.PdsContract;
import ru.corelia.transport.ServiceClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Business versions are independent from workflow state and storage revision tokens. */
@Service
public class DocumentVersionService {
    private final DocumentVersionRepository repository;
    private final DocumentRepository documents;
    private final ServiceClient services;
    public DocumentVersionService(DocumentVersionRepository repository, DocumentRepository documents, ServiceClient services) {
        this.repository = repository; this.documents = documents; this.services = services;
    }
    private record State(JsonNode document, JsonNode version, List<JsonNode> versions) {}
    private static String now() { return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS).toString(); }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static ObjectNode attributes(JsonNode value) {
        var result = object();
        for (String field : PdsContract.FIELDS) result.set(field, value.path(field));
        return result;
    }
    private State state(String type, String id, AuthContext auth) {
        for (int attempt = 0; attempt < 4; attempt++) {
            JsonNode doc = repository.document(type, id, auth);
            int number = (int) number(doc, "version", 0);
            if (number == 0) {
                var files = repository.attachments(id, auth).stream().filter(DocumentVersionService::current).toList();
                var first = snapshot(doc, 1, attributes(doc), files, auth);
                String key = digest("initialize:" + id);
                try { repository.commit(doc, object(), 1, first, null, null, null, key, key, object("version", 1), auth); }
                catch (ApiException e) {
                    // Another reader may have initialized v1, or a committed response was lost.
                    if (number(repository.document(type, id, auth), "version", 0) == 0) throw e;
                }
                continue;
            }
            List<JsonNode> versions = repository.versions(id, auth);
            JsonNode after = repository.document(type, id, auth);
            if (!Objects.equals(doc.get("changeToken"), after.get("changeToken"))) continue;
            JsonNode selected = versions.stream().filter(v -> number(v, "version", 0) == number).findFirst()
                .orElseThrow(() -> new ApiException(502, "Не найдена текущая версия документа"));
            return new State(after, selected, versions);
        }
        throw new ApiException(409, "Документ изменяется. Повторите чтение.");
    }
    private static ObjectNode snapshot(JsonNode doc, int version, JsonNode attrs, List<JsonNode> files, AuthContext auth) {
        var result = copy(attrs);
        result.put("document", text(doc, "id")); result.put("documentId", text(doc, "documentId"));
        result.put("version", version); result.put("attachments", write(files));
        result.put("createdBy", auth.login()); result.put("createdAt", now());
        return result;
    }
    private static List<JsonNode> files(JsonNode version) {
        String manifest = text(version, "attachments");
        return manifest.isEmpty() ? List.of() : list(parse(manifest));
    }
    private static boolean current(JsonNode file) { return !file.path("current").isBoolean() || file.path("current").asBoolean(); }
    private static String logical(JsonNode file) { return first(file, "logicalAttachmentId", "attachmentId", "id"); }
    public static JsonNode publicFile(JsonNode file) {
        var result = copy(file);
        result.put("id", first(file, "attachmentId", "id"));
        result.remove("storageReference"); result.remove("attachmentId");
        return result;
    }
    private JsonNode view(String type, String id, State state, JsonNode selected, AuthContext auth) {
        var result = copy(documents.get(type, id, auth));
        result.set("attributes", attributes(selected));
        result.put("version", number(selected, "version", 1));
        result.put("currentVersion", number(state.document, "version", 1));
        result.put("changeToken", text(state.document, "changeToken"));
        result.put("versionCreatedBy", text(selected, "createdBy"));
        result.put("versionCreatedAt", text(selected, "createdAt"));
        result.set("attachments", array(files(selected).stream().map(DocumentVersionService::publicFile).toList()));
        return result;
    }
    public JsonNode get(String type, String id, Integer number, AuthContext auth) {
        State state = state(type, id, auth);
        JsonNode selected = number == null ? state.version : state.versions.stream()
            .filter(v -> number(v, "version", 0) == number).findFirst()
            .orElseThrow(() -> new ApiException(404, "Версия документа не найдена"));
        return view(type, id, state, selected, auth);
    }
    public JsonNode versions(String type, String id, AuthContext auth) {
        State state = state(type, id, auth);
        return object("items", state.versions.stream()
            .sorted(Comparator.comparingLong((JsonNode v) -> number(v, "version", 0)).reversed())
            .map(v -> object("version", number(v, "version", 1), "createdBy", text(v, "createdBy"),
                "createdAt", text(v, "createdAt"), "closedAt", text(v, "closedAt"),
                "current", number(v, "version", 0) == number(state.document, "version", 0))).toList());
    }
    private void authorize(JsonNode doc, String action, AuthContext auth) {
        if (!auth.roles().contains("document_operator") && !auth.roles().contains("app_owner"))
            throw new ApiException(403, "Изменение документа доступно оператору");
        String status = text(doc, "approvalStatus");
        if (action.equals("upload") && status.equals("CREATED") && auth.login().equals(text(doc, "createdBy"))) return;
        if (!status.equals("IN_WORK")) throw new ApiException(409, "Документ недоступен для изменения на текущем шаге");
        JsonNode workflow = services.call("workflow", "/internal/v1/documents/PDS_CONTRACT/" + encode(text(doc, "documentId")) + "/workflow", "GET", null, auth);
        if (!auth.login().equals(text(workflow.path("executor"), "login"))
                || !"document_operator".equals(text(workflow.path("executor"), "role")))
            throw new ApiException(403, "Документ может изменять назначенный оператор");
    }
    private static String requestKey(String id, JsonNode body, AuthContext auth) {
        String requestId = text(body, "requestId");
        try { UUID.fromString(requestId); } catch (IllegalArgumentException e) { throw new ApiException(400, "Требуется requestId в формате UUID"); }
        return digest(id + ":" + auth.login() + ":" + requestId);
    }
    private JsonNode replay(String key, String hash, AuthContext auth) {
        JsonNode receipt = repository.receipt(key, auth);
        if (receipt == null) return null;
        if (!hash.equals(text(receipt, "requestHash"))) throw new ApiException(409, "requestId уже использован для другой команды");
        return parse(text(receipt, "response"));
    }
    public JsonNode update(String type, String id, JsonNode body, AuthContext auth) {
        JsonNode patch = PdsContract.validateAttributes(body.path("attributes"), true);
        String key = requestKey(id, body, auth), hash = digest(write(object("action", "attributes", "body", body)));
        repository.document(type, id, auth); // Access must still be checked on replay.
        JsonNode prior = replay(key, hash, auth); if (prior != null) return prior;
        State state = state(type, id, auth);
        authorize(state.document, "attributes", auth);
        if (number(body, "expectedVersion", -1) != number(state.document, "version", 0)
                || !text(body, "changeToken").equals(text(state.document, "changeToken")))
            throw new ApiException(409, "Документ или вложения изменены. Обновите карточку перед сохранением.");
        var attrs = attributes(state.version); patch.properties().forEach(e -> attrs.set(e.getKey(), e.getValue()));
        boolean changed = !attrs.equals(attributes(state.version));
        int version = (int) number(state.document, "version", 1) + (changed ? 1 : 0);
        JsonNode created = changed ? snapshot(state.document, version, attrs, files(state.version), auth) : null;
        JsonNode closed = changed ? object("id", text(state.version, "id"), "closedAt", now()) : null;
        var response = copy(view(type, id, state, created == null ? state.version : created, auth));
        response.put("currentVersion", version);
        // Response token must match the committed token; the adapter preserves this value.
        response.put("changeToken", UUID.randomUUID().toString());
        return commit(state, attrs, version, created, closed, null, null, key, hash, response, auth);
    }
    private JsonNode commit(State state, JsonNode attrs, int version, JsonNode created, JsonNode changed,
                        JsonNode file, JsonNode retired, String key, String hash, JsonNode response, AuthContext auth) {
        try { repository.commit(state.document, attrs, version, created, changed, file, retired, key, hash, response, auth); }
        catch (ApiException e) { JsonNode prior = replay(key, hash, auth); if (prior == null) throw e; return prior; }
        return response;
    }
    /** Called only over the authenticated internal API after a file has been staged in DAM. */
    public JsonNode attachment(String type, String id, JsonNode body, AuthContext auth) {
        String action = text(body, "action");
        if (!Set.of("upload", "replace", "delete").contains(action)) throw new ApiException(400, "Неизвестная команда вложения");
        var canonical = copy(body);
        if (canonical.path("file").isObject()) ((ObjectNode) canonical.path("file")).remove("uploadedAt");
        String key = requestKey(id, body, auth), hash = digest(write(canonical));
        repository.document(type, id, auth);
        JsonNode prior = replay(key, hash, auth); if (prior != null) return prior;
        State state = state(type, id, auth); authorize(state.document, action, auth);
        List<JsonNode> manifest = new ArrayList<>(files(state.version));
        JsonNode old = null;
        if (!action.equals("upload")) {
            old = manifest.stream().filter(f -> text(body, "attachmentId").equals(first(f, "attachmentId", "id"))).findFirst()
                .orElseThrow(() -> new ApiException(409, "Вложение уже заменено или удалено. Обновите карточку."));
            manifest.remove(old);
        }
        JsonNode created = null;
        if (!action.equals("delete")) {
            var file = copy(body.path("file"));
            if (!text(file, "documentId").equals(id) || text(file, "attachmentId").isEmpty()
                    || !text(file, "storageReference").startsWith("platform-v-dam:documents/" + id + "/"))
                throw new ApiException(400, "Неверные метаданные вложения");
            file.put("logicalAttachmentId", old == null ? text(file, "attachmentId") : logical(old));
            file.put("version", old == null ? 1 : number(old, "version", 1) + 1);
            file.put("current", true); created = file; manifest.add(file);
        }
        var changed = object("id", text(state.version, "id"), "attachments", write(manifest));
        JsonNode response = created == null ? object("deleted", true) : publicFile(created);
        // Manifests store public attachment IDs. Find the current storage handle before retirement.
        JsonNode retired = old == null ? null : repository.attachments(id, auth).stream()
            .filter(f -> text(body, "attachmentId").equals(first(f, "attachmentId", "id"))).findFirst()
            .orElseThrow(() -> new ApiException(502, "Не найдены метаданные вложения"));
        return commit(state, object(), (int) number(state.document, "version", 1), null, changed, created, retired, key, hash, response, auth);
    }
}
