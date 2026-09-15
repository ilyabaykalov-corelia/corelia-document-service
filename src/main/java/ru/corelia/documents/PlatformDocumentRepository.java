package ru.corelia.documents;

import static ru.corelia.support.Json.*;

import org.springframework.stereotype.Component;

import ru.corelia.auth.AuthContext;
import ru.corelia.config.CoreliaConfig;
import ru.corelia.http.ApiException;
import ru.corelia.integration.DataSpaceClient;
import ru.corelia.integration.PdsContract;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.*;

/** Адаптер модели ПДС: использует зарегистрированные в Platform V операции GraphQL. */
@Component
public class PlatformDocumentRepository implements DocumentRepository {
    private final DataSpaceClient data;
    private final CoreliaConfig config;

    public PlatformDocumentRepository(DataSpaceClient data, CoreliaConfig config) {
        this.data = data;
        this.config = config;
    }

    private List<JsonNode> raw(String code, AuthContext auth) {
        PdsContract.requireType(code);
        List<JsonNode> result = new ArrayList<>();
        long maximum = config.number("CORELIA_DOCUMENT_SCAN_LIMIT", 10000);
        for (int offset = 0; ; ) {
            JsonNode page =
                    data.query("searchDocument", object("offset", offset, "limit", 500), auth)
                            .path("searchDocument");
            List<JsonNode> rows = list(page.path("elems"));
            result.addAll(rows.stream().filter(row -> code.equals(text(row.path("documentType"), "id"))).map(ru.corelia.integration.DocumentProjection::pds).toList());
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
        return raw(code, auth).stream()
                .filter(row -> id.equals(text(row, "documentId")))
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
        for (String field : PdsContract.FIELDS) {
            JsonNode value = row.path("attributes").path(field);
            if (!value.isMissingNode()) attributes.set(field, value);
        }
        String status = PdsContract.normalizeStatus(text(row, "status"));
        ObjectNode document =
                object(
                        "id",
                        id,
                        "typeCode",
                        code,
                        "typeName",
                        PdsContract.NAME,
                        "attributes",
                        attributes,
                        "status",
                        status,
                        "statusLabel",
                        PdsContract.label(status));
        for (String field : List.of("createdBy", "createdAt")) {
            JsonNode value = row.path(field);
            if (!value.isMissingNode() && !value.isNull()) document.set(field, value);
        }
        return document;
    }
}
