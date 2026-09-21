package ru.corelia.documents;

import static ru.corelia.support.Json.*;

import org.springframework.stereotype.Component;

import ru.corelia.auth.AuthContext;
import ru.corelia.http.ApiException;
import ru.corelia.integration.DataSpaceClient;
import ru.corelia.integration.DocumentTypes;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.*;

/** Адаптер моделей документов: использует зарегистрированные в Platform V операции GraphQL. */
@Component
public class PlatformDocumentRepository implements DocumentRepository {
    private final DocumentTypes types;
    private final DataSpaceClient data;

    public PlatformDocumentRepository(DocumentTypes types, DataSpaceClient data) {
        this.types = types;
        this.data = data;
    }

    private static String condition(String field, String value) {
        return "it." + field + " == '" + value.replace("'", "''") + "'";
    }

    private List<JsonNode> raw(String code, AuthContext auth) {
        types.requireType(code);
        List<JsonNode> result = new ArrayList<>();
        for (int offset = 0; ; ) {
            JsonNode page =
                    data.query(
                                    text(types.definition(code).storage().path("operations"), "search"),
                                    object(
                                            "cond", condition("documentType.id", code),
                                            "offset", offset,
                                            "limit", 500),
                                    auth)
                            .path("searchDocument");
            List<JsonNode> rows = list(page.path("elems"));
            result.addAll(rows.stream().filter(row -> code.equals(text(row.path("documentType"), "id"))).map(row -> ru.corelia.integration.DocumentProjection.document(row, types)).toList());
            offset += rows.size();
            if (rows.isEmpty() || offset >= number(page, "count", offset)) return result;
        }
    }

    public List<JsonNode> all(String type, AuthContext auth) {
        return raw(type, auth).stream().map(row -> map(type, row)).toList();
    }

    private JsonNode find(String code, String id, AuthContext auth) {
        types.requireType(code);
        JsonNode page =
                data.query(
                                text(types.definition(code).storage().path("operations"), "search"),
                                object("cond", condition("documentId", id), "offset", 0, "limit", 2),
                                auth)
                        .path("searchDocument");
        return list(page.path("elems")).stream()
                .filter(row -> id.equals(text(row, "documentId")))
                .filter(row -> code.equals(text(row.path("documentType"), "id")))
                .map(row -> ru.corelia.integration.DocumentProjection.document(row, types))
                .findFirst()
                .orElseThrow(() -> new ApiException(404, "Документ не найден"));
    }

    public JsonNode get(String type, String id, AuthContext auth) {
        return map(type, find(type, id, auth));
    }

    private JsonNode map(String code, JsonNode row) {
        String id = text(row, "documentId");
        if (id.isEmpty())
            throw new ApiException(502, "DataSpace вернул документ без публичного идентификатора");
        ObjectNode attributes = object();
        for (String field : types.fields(code)) {
            JsonNode value = row.path("attributes").path(field);
            if (!value.isMissingNode()) attributes.set(field, value);
        }
        String status = text(row, "status");
        ObjectNode document =
                object(
                        "id",
                        id,
                        "typeCode",
                        code,
                        "typeName",
                        types.name(code),
                        "attributes",
                        attributes,
                        "status",
                        status,
                        "statusLabel",
                        types.label(code, status));
        for (String field : List.of("createdBy", "createdAt")) {
            JsonNode value = row.path(field);
            if (!value.isMissingNode() && !value.isNull()) document.set(field, value);
        }
        return document;
    }
}
