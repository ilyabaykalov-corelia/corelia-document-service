package ru.corelia.documents;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.corelia.configuration.DocumentTypeCatalog;
import ru.corelia.provider.PermissionProvider;
import ru.corelia.transport.ServiceClient;
import java.util.List;

@Configuration
public class DocumentPolicies {
    @Bean
    public List<DocumentPolicy> configuredDocumentPolicies(DocumentTypeCatalog types, ServiceClient services, PermissionProvider permissions) {
        return types.types().stream().map(type -> (DocumentPolicy) new ConfiguredDocumentPolicy(services, types, type, permissions)).toList();
    }
}
