package ru.corelia.documents;

import static ru.corelia.support.Json.*;
import org.springframework.stereotype.Component;
import ru.corelia.auth.AuthContext;
import ru.corelia.integration.DataSpaceClient;
import ru.corelia.configuration.DocumentTypeCatalog;
import ru.corelia.http.ApiException;
import tools.jackson.databind.JsonNode;
import java.util.*;

/** DataSpace-specific formats and transaction boundaries stay in this adapter. */
@Component
public class PlatformDocumentVersionRepository implements DocumentVersionRepository {
    private final DocumentTypeCatalog types;
    private final DataSpaceClient data;
    public PlatformDocumentVersionRepository(DocumentTypeCatalog types, DataSpaceClient data) {
        this.types = types; this.data = data; }

    private static String condition(String field, String value) {
        // String expression literal; never concatenate an unescaped client value.
        return "it." + field + " == '" + value.replace("'", "''") + "'";
    }
    private List<JsonNode> search(String operation, String condition, AuthContext auth) {
        List<JsonNode> rows = new ArrayList<>();
        for (int offset = 0; ; ) {
            JsonNode page = data.query(operation, object("cond", condition, "offset", offset, "limit", 500), auth).path(operation);
            List<JsonNode> batch = list(page.path("elems"));
            rows.addAll(batch); offset += batch.size();
            if (offset >= number(page, "count", offset)) return rows;
            if (batch.isEmpty()) throw new ApiException(502, "Неполная выборка DataSpace");
        }
    }
    public String type(String id, AuthContext auth) {
        return search("searchDocument", condition("documentId", id), auth).stream()
            .filter(x -> id.equals(text(x, "documentId")))
            .map(x -> text(x.path("documentType"), "id")).findFirst()
            .orElseThrow(() -> new ApiException(404, "Документ не найден"));
    }
    private List<JsonNode> searchDocument(String type, String id, AuthContext auth) {
        String operation = text(types.definition(type).storage().path("operations"), "search");
        return list(data.query(operation, object("cond", condition("documentId", id), "offset", 0, "limit", 2), auth).path("searchDocument").path("elems"));
    }
    public JsonNode document(String type, String id, AuthContext auth) {
        types.requireType(type);
        return searchDocument(type, id, auth).stream()
            .filter(x -> id.equals(text(x, "documentId")) && type.equals(text(x.path("documentType"), "id"))).map(row -> ru.corelia.integration.DocumentProjection.document(row, types)).findFirst()
            .orElseThrow(() -> new ApiException(404, "Документ не найден"));
    }
    public List<JsonNode> versions(String id, AuthContext auth) {
        return search("searchDocumentVersion", condition("documentId", id), auth).stream()
            .filter(x -> id.equals(text(x, "documentId"))).toList();
    }
    public List<JsonNode> attachments(String id, AuthContext auth) {
        return search("searchAttachment", condition("documentId", id), auth).stream()
            .filter(x -> id.equals(text(x, "documentId"))).toList();
    }
    public JsonNode receipt(String key, AuthContext auth) {
        return list(data.query("searchDocumentCommand", object("cond", condition("commandKey", key)), auth)
            .path("searchDocumentCommand").path("elems")).stream()
            .filter(x -> key.equals(text(x, "commandKey"))).findFirst().orElse(null);
    }
    private tools.jackson.databind.node.ObjectNode mappedAttributes(String type, JsonNode attributes) {
        var result = object();
        JsonNode mapping = types.definition(type).storage().path("fields");
        for (var field : attributes.properties()) result.set(text(mapping, field.getKey()), field.getValue());
        return result;
    }
    public void commit(JsonNode doc, JsonNode attributes, int version, JsonNode createdVersion,
                       JsonNode changedVersion, JsonNode createdFile, JsonNode retiredFile,
                       String key, String hash, JsonNode response, AuthContext auth) {
        var update = object();
        update.put("id", text(doc, "id")); update.put("version", version);
        update.put("changeToken", response.hasNonNull("changeToken") ? text(response, "changeToken") : UUID.randomUUID().toString());
        var compare = object("changeToken", doc.hasNonNull("changeToken") ? doc.path("changeToken") : null);

        if (createdVersion != null && version == 1 && number(doc, "version", 0) == 0) {
            data.query("initializeDocumentVersion", object("id", text(doc, "id"), "token", text(update, "changeToken"), "compare", compare, "version", createdVersion), auth);
            return;
        }
        var vars = object("document", update, "compare", compare,
            "command", object("document", text(doc, "id"), "commandKey", key, "requestHash", hash, "response", write(response)));
        // Omit absent commands altogether. GraphQL coerces every declared variable before
        // evaluating directives, and a child create input requires its parent ID.
        String operation;
        if (createdVersion != null) {
            if (changedVersion == null || createdFile != null || retiredFile != null)
                throw new IllegalArgumentException("Invalid attribute version transaction");
            operation = text(types.definition(text(doc.path("documentType"), "id")).storage().path("operations"), "update");
            var details = mappedAttributes(text(doc.path("documentType"), "id"), attributes);
            details.put("id", text(doc, "detailsId"));
            vars.set("details", details);
            vars.set("detailsCompare", mappedAttributes(text(doc.path("documentType"), "id"), doc.path("attributes")));
            vars.set("version", createdVersion);
            vars.set("previous", changedVersion);
        } else if (changedVersion != null) {
            vars.set("previous", changedVersion);
            if (createdFile != null) {
                vars.set("file", createdFile);
                operation = retiredFile == null ? "commitDocumentFileUpload" : "commitDocumentFileReplace";
            } else {
                if (retiredFile == null) throw new IllegalArgumentException("Missing retired attachment");
                operation = "commitDocumentFileDelete";
            }
            if (retiredFile != null)
                vars.set("retired", object("id", text(retiredFile, "id"), "current", false));
        } else {
            if (createdFile != null || retiredFile != null)
                throw new IllegalArgumentException("Attachment command requires a version manifest");
            operation = "commitDocumentNoChange";
        }
        data.query(operation, vars, auth);
    }
}
