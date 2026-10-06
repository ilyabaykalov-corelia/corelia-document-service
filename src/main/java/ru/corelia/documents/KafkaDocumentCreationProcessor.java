package ru.corelia.documents;

import static ru.corelia.support.Json.object;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import ru.corelia.auth.AuthContext;
import ru.corelia.configuration.ConfigurationLoader;
import ru.corelia.configuration.KafkaDocumentCreationConfiguration;
import ru.corelia.http.ApiException;
import ru.corelia.observability.CoreliaObservability;
import ru.corelia.support.Json;

import tools.jackson.databind.JsonNode;

/** Преобразует нейтральное событие Kafka в обычный сценарий создания документа. */
@Component
public class KafkaDocumentCreationProcessor {
    private final DocumentService documents;
    private final CoreliaObservability observability;
    private final KafkaDocumentCreationConfiguration configuration;
    private final Map<String, KafkaDocumentCreationConfiguration.Route> routes;

    @Autowired
    public KafkaDocumentCreationProcessor(DocumentService documents, CoreliaObservability observability,
            ConfigurationLoader.LoadedConfiguration loaded) {
        this(documents, observability, loaded.kafkaDocumentCreation());
    }

    KafkaDocumentCreationProcessor(DocumentService documents, CoreliaObservability observability,
            KafkaDocumentCreationConfiguration configuration) {
        this.documents = documents;
        this.observability = observability;
        this.configuration = configuration;
        this.routes = new LinkedHashMap<>();
        if (configuration != null) for (var route : configuration.routes()) routes.put(route.topic(), route);
    }

    public boolean enabled() { return configuration != null; }
    public KafkaDocumentCreationConfiguration configuration() { return configuration; }

    /** Обрабатывает одно сообщение без раскрытия его содержимого в журнале. */
    public Result process(String topic, String payload, String accessToken) {
        var route = routes.get(topic);
        if (route == null) throw new ApiException(400, "Для Kafka topic не настроен тип документа");
        JsonNode message = Json.parse(payload);
        if (!message.isObject()) throw new ApiException(400, "Kafka-сообщение должно быть JSON-объектом");
        if (message.has("items")) return processBatch(route, message, accessToken);
        return new Result(List.of(create(route, message, accessToken)), List.of());
    }

    private Result processBatch(KafkaDocumentCreationConfiguration.Route route, JsonNode message, String accessToken) {
        requireFields(message, "items");
        JsonNode items = message.path("items");
        if (!items.isArray() || items.isEmpty()) throw new ApiException(400, "Kafka batch требует непустой items-массив");
        var created = new ArrayList<Created>();
        var rejected = new ArrayList<Rejected>();
        for (int index = 0; index < items.size(); index++) {
            try {
                created.add(create(route, items.get(index), accessToken));
            } catch (ApiException error) {
                if (error.status() == 503) throw error;
                rejected.add(new Rejected(index, error.getMessage()));
            } catch (RuntimeException error) {
                rejected.add(new Rejected(index, error.getMessage()));
            }
        }
        return new Result(created, rejected);
    }

    private Created create(KafkaDocumentCreationConfiguration.Route route, JsonNode message, String accessToken) {
        if (!message.isObject()) throw new ApiException(400, "Элемент Kafka batch должен быть JSON-объектом");
        requireFields(message, "requestId", "attributes", "initialAttachment");
        if (!message.path("requestId").isTextual() || message.path("requestId").asString().isBlank())
            throw new ApiException(400, "Kafka-сообщение требует requestId");
        if (!message.path("attributes").isObject()) throw new ApiException(400, "Kafka-сообщение требует attributes-объект");
        if (message.has("initialAttachment") && !message.path("initialAttachment").isObject())
            throw new ApiException(400, "initialAttachment должен быть объектом");
        var creation = object("requestId", message.path("requestId").asString(), "attributes", message.path("attributes").deepCopy());
        if (message.has("initialAttachment")) creation.set("initialAttachment", message.path("initialAttachment").deepCopy());
        JsonNode document = documents.create(route.typeCode(), creation, auth(accessToken));
        observability.documentCreated(route.typeCode());
        return new Created(document == null ? "" : document.path("id").asString(""));
    }

    private static void requireFields(JsonNode message, String... allowed) {
        for (String field : message.propertyNames()) {
            if (java.util.Arrays.stream(allowed).noneMatch(field::equals))
                throw new ApiException(400, "Kafka-сообщение содержит неизвестное поле: " + field);
        }
    }

    public record Result(List<Created> created, List<Rejected> rejected) { }
    public record Created(String documentId) { }
    public record Rejected(int index, String error) { }

    private AuthContext auth(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) throw new ApiException(503, "Не настроен токен технического пользователя Kafka");
        var actor = configuration.actor();
        return new AuthContext(accessToken, actor.id(), actor.login(), actor.fullName(), actor.email(), actor.roles(), actor.taskUsername());
    }
}
