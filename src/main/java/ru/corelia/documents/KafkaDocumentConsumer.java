package ru.corelia.documents;

import static ru.corelia.support.Json.object;

import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import ru.corelia.config.CoreliaConfig;
import ru.corelia.http.ApiException;
import ru.corelia.support.LogJson;

/** Infrastructure-адаптер Kafka; ошибки отдельного сообщения не останавливают consumer. */
@Component
public class KafkaDocumentConsumer implements SmartLifecycle {
    private final KafkaDocumentCreationProcessor processor;
    private final CoreliaConfig runtime;
    private final KafkaServiceTokenProvider tokens;
    private final AtomicReference<KafkaConsumer<String, String>> consumer = new AtomicReference<>();
    private volatile boolean running;
    private Thread worker;

    public KafkaDocumentConsumer(KafkaDocumentCreationProcessor processor, CoreliaConfig runtime, KafkaServiceTokenProvider tokens) {
        this.processor = processor; this.runtime = runtime; this.tokens = tokens;
    }

    @Override public synchronized void start() {
        if (running || !processor.enabled()) return;
        if (!tokens.enabled()) {
            LogJson.warn("Kafka consumer создания документов отключён: не настроен service account", object());
            return;
        }
        running = true;
        worker = Thread.ofPlatform().name("corelia-kafka-document-consumer").daemon(true).start(this::consume);
    }

    private void consume() {
        var configuration = processor.configuration();
        while (running) {
            try (var current = new KafkaConsumer<String, String>(properties(configuration.consumerGroup()))) {
                consumer.set(current);
                current.subscribe(configuration.routes().stream().map(route -> route.topic()).toList());
                while (running) for (var record : current.poll(Duration.ofMillis(500))) {
                    try {
                        var result = processor.process(record.topic(), record.value(), tokens.token());
                        for (var document : result.created()) LogJson.info("Создан документ по Kafka-сообщению", object(
                                "topic", record.topic(), "partition", record.partition(), "offset", record.offset(),
                                "documentId", document.documentId()));
                        for (var rejected : result.rejected()) LogJson.warn("Документ из Kafka batch отклонён", object(
                                "topic", record.topic(), "partition", record.partition(), "offset", record.offset(),
                                "itemIndex", rejected.index(), "error", rejected.error()));
                    } catch (ApiException error) {
                        if (error.status() == 503) {
                            LogJson.warn("Kafka-сообщение ожидает токен service account", object("topic", record.topic(), "partition", record.partition(), "offset", record.offset(), "error", error.getMessage()));
                            break;
                        }
                        LogJson.warn("Kafka-сообщение создания документа отклонено", object("topic", record.topic(), "partition", record.partition(), "offset", record.offset(), "error", error.getMessage()));
                    } catch (RuntimeException error) {
                        LogJson.warn("Kafka-сообщение создания документа отклонено", object("topic", record.topic(), "partition", record.partition(), "offset", record.offset(), "error", error.getMessage()));
                    }
                    current.commitSync();
                }
            } catch (WakeupException error) {
                if (running) LogJson.warn("Kafka consumer создания документов прерван", object("error", error.getMessage()));
            } catch (RuntimeException error) {
                if (running) {
                    Throwable cause = error.getCause();
                    LogJson.warn("Kafka consumer создания документов повторит подключение", object(
                            "errorType", error.getClass().getSimpleName(), "error", error.getMessage(),
                            "causeType", cause == null ? "" : cause.getClass().getSimpleName(),
                            "cause", cause == null ? "" : cause.getMessage()));
                    try { Thread.sleep(1000); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
                }
            } finally {
                consumer.set(null);
            }
        }
    }

    private Properties properties(String group) {
        var properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, runtime.value("CORELIA_KAFKA_BOOTSTRAP_SERVERS", "kafka:9092"));
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, group);
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return properties;
    }

    @Override public synchronized void stop() {
        running = false;
        KafkaConsumer<String, String> current = consumer.get();
        if (current != null) current.wakeup();
    }
    @Override public boolean isRunning() { return running; }
    @Override public boolean isAutoStartup() { return true; }
    @Override public int getPhase() { return Integer.MAX_VALUE; }
}
