package ru.corelia.documents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import ru.corelia.auth.AuthContext;
import ru.corelia.configuration.KafkaDocumentCreationConfiguration;
import ru.corelia.http.ApiException;
import ru.corelia.observability.CoreliaObservability;
import tools.jackson.databind.JsonNode;

class KafkaDocumentCreationProcessorTest {
    private final DocumentService documents = mock(DocumentService.class);
    private final KafkaDocumentCreationProcessor processor = new KafkaDocumentCreationProcessor(documents,
            mock(CoreliaObservability.class), new KafkaDocumentCreationConfiguration("documents-test",
                    new KafkaDocumentCreationConfiguration.Actor("broker", "broker", "Broker", "", List.of("operator"), "broker"),
                    List.of(new KafkaDocumentCreationConfiguration.Route("customer.documents.test.create", "TEST_FORM"))));

    @Test void createsConfiguredDocumentThroughTheUsualService() {
        processor.process("customer.documents.test.create", """
            {"requestId":"b7649ae1-35fb-4d09-a78c-9b8058caf2d2","attributes":{"number":"42"}}
            """, "token");
        var body = ArgumentCaptor.forClass(JsonNode.class);
        var auth = ArgumentCaptor.forClass(AuthContext.class);
        verify(documents).create(org.mockito.ArgumentMatchers.eq("TEST_FORM"), body.capture(), auth.capture());
        assertEquals("42", body.getValue().path("attributes").path("number").asString());
        assertEquals("broker", auth.getValue().login());
        assertEquals("token", auth.getValue().token());
    }

    @Test void createsEveryValidDocumentFromBatchAndRejectsOnlyInvalidItems() {
        var result = processor.process("customer.documents.test.create", """
            {"items":[
              {"requestId":"b7649ae1-35fb-4d09-a78c-9b8058caf2d2","attributes":{"number":"42"}},
              {"attributes":{"number":"invalid"}},
              {"requestId":"e8349ae1-35fb-4d09-a78c-9b8058caf2d2","attributes":{"number":"43"}}
            ]}
            """, "token");
        verify(documents, org.mockito.Mockito.times(2)).create(org.mockito.ArgumentMatchers.eq("TEST_FORM"), any(), any());
        assertEquals(2, result.created().size());
        assertEquals(1, result.rejected().size());
        assertEquals(1, result.rejected().getFirst().index());
    }

    @Test void rejectsMalformedMessagesBeforeDocumentCreation() {
        assertThrows(ApiException.class, () -> processor.process("customer.documents.test.create", "{" + "\"attributes\":{}" + "}", "token"));
        assertThrows(ApiException.class, () -> processor.process("unknown", "{}", "token"));
    }
}
