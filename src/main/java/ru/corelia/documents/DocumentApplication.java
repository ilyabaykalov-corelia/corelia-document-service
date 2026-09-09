package ru.corelia.documents;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import ru.corelia.config.LocalEnvironment;

/** Запускает приложение corelia-document-service. */
@SpringBootApplication(
        scanBasePackages = {
            "ru.corelia.config",
            "ru.corelia.support",
            "ru.corelia.auth",
            "ru.corelia.http",
            "ru.corelia.cache",
            "ru.corelia.integration",
            "ru.corelia.transport",
            "ru.corelia.documents"
        })
public class DocumentApplication {
    public static void main(String[] args) {
        var app = new SpringApplication(DocumentApplication.class);
        app.setDefaultProperties(LocalEnvironment.load());
        app.run(args);
    }
}
