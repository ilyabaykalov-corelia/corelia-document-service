package ru.corelia.documents;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.corelia.integration.DocumentTypes;
import ru.corelia.transport.ServiceClient;
import java.util.List;

@Configuration
public class DocumentPolicies {
    @Bean
    public List<DocumentPolicy> configuredDocumentPolicies(DocumentTypes types, ServiceClient services) {
        return types.types().stream().map(type -> (DocumentPolicy) new ConfiguredDocumentPolicy(services, types, type)).toList();
    }
}
