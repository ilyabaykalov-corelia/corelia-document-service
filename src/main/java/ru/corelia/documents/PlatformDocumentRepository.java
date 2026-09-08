package ru.corelia.documents;

import static ru.corelia.support.Json.*;

import org.springframework.stereotype.Component;

import ru.corelia.auth.AuthContext;
import ru.corelia.config.CoreliaConfig;
import ru.corelia.http.ApiException;
import ru.corelia.integration.DataSpaceClient;
import ru.corelia.profile.ProductProfile;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.*;

/** Отображает метаданные профиля в GraphQL. Клиент не может передавать имена операций и полей. */
@Component
public class PlatformDocumentRepository implements DocumentRepository {
    private final DataSpaceClient data;
    private final ProductProfile profile;
    private final CoreliaConfig config;

    public PlatformDocumentRepository(
            DataSpaceClient data, ProductProfile profile, CoreliaConfig config) {
        this.data = data;
        this.profile = profile;
        this.config = config;
    }

    private List<JsonNode> raw(String code, AuthContext auth) {
        JsonNode type = profile.type(code), mapping = type.path("platform");
        String operation = text(mapping, "searchOperation");
        Set<String> fields =
                new LinkedHashSet<>(
                        List.of("id", text(mapping, "idField"), text(mapping, "statusField")));
        for (String name : List.of("createdByField", "createdAtField"))
            if (!text(mapping, name).isEmpty()) fields.add(text(mapping, name));
        if (!text(mapping, "typeField").isEmpty())
            fields.add(text(mapping, "typeField") + " { id name }");
        type.path("fields")
                .properties()
                .forEach(e -> fields.add(profile.fieldMapping(e.getValue(), e.getKey())));
        fields.addAll(ProductProfile.strings(mapping.path("extraReadFields")));
        String query =
                "query "
                        + operation
                        + "($offset: Int, $limit: Int) { "
                        + operation
                        + "(offset: $offset, limit: $limit) { elems { "
                        + String.join(" ", fields)
                        + " } count } }";
        List<JsonNode> result = new ArrayList<>();
        long maximum = config.number("CORELIA_DOCUMENT_SCAN_LIMIT", 10000);
        for (int offset = 0; ; ) {
            JsonNode page =
                    data.execute(query, object("offset", offset, "limit", 500), auth)
                            .path(operation);
            List<JsonNode> rows = list(page.path("elems"));
            result.addAll(rows);
            offset += rows.size();
            if (rows.isEmpty() || offset >= number(page, "count", offset)) return result;
            if (offset >= maximum)
                throw new ApiException(
                        422,
                        "Превышен предел выборки документов; настройте фильтрацию в адаптере"
                            + " платформы");
        }
    }

    public List<JsonNode> all(String type, AuthContext auth) {
        return raw(type, auth).stream().map(row -> map(type, row)).toList();
    }

    private JsonNode find(String code, String id, AuthContext auth) {
        String field = text(profile.type(code).path("platform"), "idField");
        return raw(code, auth).stream()
                .filter(row -> id.equals(text(row, field)))
                .findFirst()
                .orElseThrow(() -> new ApiException(404, "Документ не найден"));
    }

    public JsonNode get(String type, String id, AuthContext auth) {
        return map(type, find(type, id, auth));
    }

    public void update(String code, String id, JsonNode attributes, AuthContext auth) {
        JsonNode row = find(code, id, auth),
                type = profile.type(code),
                mapping = type.path("platform");
        ObjectNode input = object("id", text(row, "id"));
        attributes
                .properties()
                .forEach(
                        e ->
                                input.set(
                                        profile.fieldMapping(
                                                type.path("fields").path(e.getKey()), e.getKey()),
                                        e.getValue()));
        String operation = text(mapping, "updateOperation");
        data.execute(
                "mutation "
                        + operation
                        + "($input: "
                        + text(mapping, "updateInput")
                        + "!) { packet { "
                        + operation
                        + "(input: $input) { id } } }",
                object("input", input),
                auth);
    }

    private JsonNode map(String code, JsonNode row) {
        JsonNode type = profile.type(code), mapping = type.path("platform");
        String id = text(row, text(mapping, "idField"));
        if (id.isEmpty())
            throw new ApiException(502, "DataSpace вернул документ без публичного идентификатора");
        ObjectNode attributes = object();
        type.path("fields")
                .properties()
                .forEach(
                        e -> {
                            JsonNode value =
                                    row.path(profile.fieldMapping(e.getValue(), e.getKey()));
                            if (!value.isMissingNode()) attributes.set(e.getKey(), value);
                        });
        String status = profile.normalizeStatus(code, text(row, text(mapping, "statusField")));
        ObjectNode document =
                object(
                        "id",
                        id,
                        "typeCode",
                        code,
                        "typeName",
                        text(type, "name"),
                        "attributes",
                        attributes,
                        "status",
                        status,
                        "statusLabel",
                        profile.label(code, status));
        for (String field : List.of("createdBy", "createdAt")) {
            JsonNode value = row.path(text(mapping, field + "Field"));
            if (!value.isMissingNode() && !value.isNull()) document.set(field, value);
        }
        return document;
    }
}
