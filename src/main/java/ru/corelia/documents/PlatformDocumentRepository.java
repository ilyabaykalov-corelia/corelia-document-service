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
                    data.query("searchPdsContract", object("offset", offset, "limit", 500), auth)
                            .path("searchPdsContract");
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
        return raw(code, auth).stream()
                .filter(row -> id.equals(text(row, "documentId")))
                .findFirst()
                .orElseThrow(() -> new ApiException(404, "Документ не найден"));
    }

    public JsonNode get(String type, String id, AuthContext auth) {
        return map(type, find(type, id, auth));
    }

    /** Возвращает исторические состояния карточки в порядке от новой версии к старой. */
    public List<JsonNode> versions(String type, String id, AuthContext auth) {
        PdsContract.requireType(type);
        JsonNode page = data.query("documentStates", object("id", find(type, id, auth).path("id"), "offset", 0, "limit", 500), auth)
                .path("getStatesPdsContract");
        List<JsonNode> states = list(page.path("elems"));
        if (states.isEmpty()) throw new ApiException(409, "История карточки ещё не инициализирована в DataSpace");
        List<JsonNode> result = new ArrayList<>();
        for (int index = states.size() - 1; index >= 0; index--) {
            ObjectNode item = (ObjectNode) map(type, states.get(index));
            item.put("version", index + 1);
            item.put("historyId", text(states.get(index), "sysHistNumber"));
            item.put("versionCreatedAt", text(states.get(index), "sysHistoryTime"));
            result.add(item);
        }
        return result;
    }

    public void update(String code, String id, JsonNode attributes, AuthContext auth) {
        JsonNode row = find(code, id, auth);
        ObjectNode input = object("id", text(row, "id"));
        PdsContract.validateAttributes(attributes, true)
                .properties()
                .forEach(e -> input.set(e.getKey(), e.getValue()));
        data.query("updatePdsContract", object("input", input), auth);
    }

    private JsonNode map(String code, JsonNode row) {
        String id = text(row, "documentId");
        if (id.isEmpty())
            throw new ApiException(502, "DataSpace вернул документ без публичного идентификатора");
        ObjectNode attributes = object();
        for (String field : PdsContract.FIELDS) {
            JsonNode value = row.path(field);
            if (!value.isMissingNode()) attributes.set(field, value);
        }
        String status = PdsContract.normalizeStatus(text(row, "approvalStatus"));
        ObjectNode document =
                object(
                        "id",
                        id,
                        "dataSpaceId",
                        text(row, "id"),
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
        if (!row.path("attachmentsHead").isMissingNode()) {
            JsonNode head = row.path("attachmentsHead");
            document.put("attachmentsHead", head.isObject() ? text(head, "id") : text(head));
        }
        if (!row.path("historyInitialized").isMissingNode()) document.set("historyInitialized", row.path("historyInitialized"));
        return document;
    }
}
