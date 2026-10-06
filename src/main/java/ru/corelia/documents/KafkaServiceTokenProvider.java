package ru.corelia.documents;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import org.springframework.stereotype.Component;

import ru.corelia.config.CoreliaConfig;
import ru.corelia.http.ApiException;
import ru.corelia.support.Json;

/** Получает и кэширует access token технического Kafka-исполнителя. */
@Component
public class KafkaServiceTokenProvider {
    private static final Path SECRET = Path.of("/run/secrets/corelia-service-client-secret");
    private final CoreliaConfig runtime;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private volatile Token token;

    public KafkaServiceTokenProvider(CoreliaConfig runtime) { this.runtime = runtime; }

    public boolean enabled() {
        return !runtime.value("CORELIA_KAFKA_OAUTH_CLIENT_ID").isBlank() && Files.isRegularFile(SECRET);
    }

    public String token() {
        Token cached = token;
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) return cached.value();
        synchronized (this) {
            cached = token;
            if (cached != null && cached.expiresAt().isAfter(Instant.now())) return cached.value();
            token = request();
            return token.value();
        }
    }

    private Token request() {
        String secret;
        try { secret = Files.readString(SECRET, StandardCharsets.UTF_8).trim(); }
        catch (java.io.IOException error) { throw new ApiException(503, "Не удалось прочитать secret service account Kafka"); }
        if (secret.isEmpty()) throw new ApiException(503, "Secret service account Kafka пуст");
        String clientId = runtime.value("CORELIA_KAFKA_OAUTH_CLIENT_ID");
        String endpoint = runtime.value("CORELIA_KAFKA_TOKEN_URL", runtime.issuer().replaceAll("/+$", "") + "/protocol/openid-connect/token");
        String form = "grant_type=client_credentials&client_id=" + encode(clientId) + "&client_secret=" + encode(secret);
        try {
            var request = HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form)).build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new ApiException(503, "Token endpoint Kafka service account вернул HTTP " + response.statusCode());
            var payload = Json.parse(response.body());
            String value = payload.path("access_token").asString();
            long expiresIn = payload.path("expires_in").asLong(0);
            if (value.isBlank() || expiresIn < 60) throw new ApiException(503, "Token endpoint Kafka service account вернул некорректный ответ");
            return new Token(value, Instant.now().plusSeconds(Math.max(1, expiresIn - 30)));
        } catch (java.io.IOException error) {
            throw new ApiException(503, "Не удалось получить токен service account Kafka");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ApiException(503, "Получение токена service account Kafka прервано");
        } catch (IllegalArgumentException error) {
            throw new ApiException(503, "Некорректно настроен token endpoint Kafka service account");
        }
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private record Token(String value, Instant expiresAt) {}
}
