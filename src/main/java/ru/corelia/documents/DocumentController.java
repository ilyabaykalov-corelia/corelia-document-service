package ru.corelia.documents;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import ru.corelia.http.ApiRequest;
import ru.corelia.integration.DocumentTypes;

import tools.jackson.databind.JsonNode;

/** Внутренний API карточек, одинаковый для поддерживаемых видов документов. */
@RestController
@RequestMapping("/internal/v1")
public class DocumentController {
    private final DocumentTypes types;
    private final ru.corelia.integration.DataSpaceClient data;
    private final DocumentVersionService versions;
    private final DocumentService documents;
    private final ApiRequest requests;

    public DocumentController(DocumentTypes types,
            DocumentService documents,
            DocumentVersionService versions,
            ApiRequest requests,
            ru.corelia.integration.DataSpaceClient data) {
        this.types = types;
        this.versions = versions;
        this.data = data;
        this.documents = documents;
        this.requests = requests;
    }

    @GetMapping("/document-types/available")
    public JsonNode available(HttpServletRequest request) {
        JsonNode page =
                data.query(
                                "refDocumentTypeListGet",
                                ru.corelia.support.Json.object(),
                                requests.auth(request))
                        .path("searchDocumentType");
        return ru.corelia.support.Json.object(
                "items", page.path("elems"), "total", page.path("count"));
    }

    @GetMapping("/document-types")
    public JsonNode types() {
        return types.catalog();
    }

    @GetMapping("/document-types/{type}")
    public JsonNode definition(@PathVariable String type) { return types.publicDefinition(type); }

    @PostMapping("/documents/search")
    public JsonNode searchAll(HttpServletRequest request) {
        return documents.searchAll(requests.body(request), requests.auth(request));
    }

    @PostMapping("/documents/{type}/search")
    public JsonNode search(@PathVariable String type, HttpServletRequest request) {
        return documents.search(type, requests.body(request), requests.auth(request));
    }

    @GetMapping("/documents/by-id/{id}")
    public JsonNode getById(@PathVariable String id, HttpServletRequest request) {
        return versions.getById(id, requests.auth(request));
    }

    @GetMapping("/documents/{type}/{id}")
    public JsonNode get(
            @PathVariable String type, @PathVariable String id, HttpServletRequest request) {
        return documents.get(type, id, requests.auth(request));
    }

    @PostMapping("/documents/{type}")
    public ResponseEntity<JsonNode> create(@PathVariable String type, HttpServletRequest request) {
        return ResponseEntity.status(201)
                .body(documents.create(type, requests.body(request), requests.auth(request)));
    }

    @PostMapping(value = "/documents/{type}/stream", consumes = "multipart/form-data")
    public ResponseEntity<JsonNode> createStream(
            @PathVariable String type,
            @RequestParam String requestId,
            @RequestParam String attributes,
            @RequestParam MultipartFile file,
            HttpServletRequest request) {
        try {
            return ResponseEntity.status(201)
                    .body(documents.createStream(type, requestId, ru.corelia.support.Json.parse(attributes), file, requests.auth(request)));
        } catch (RuntimeException error) {
            throw error;
        }
    }

    @PatchMapping("/documents/{type}/{id}")
    public JsonNode update(
            @PathVariable String type, @PathVariable String id, HttpServletRequest request) {
        return documents.update(type, id, requests.body(request), requests.auth(request));
    }

    @GetMapping("/documents/{type}/{id}/capabilities")
    public JsonNode capabilities(@PathVariable String type, @PathVariable String id, HttpServletRequest r) {
        return versions.capabilities(type, id, requests.auth(r));
    }

    @GetMapping("/documents/{type}/{id}/versions")
    public JsonNode versions(@PathVariable String type, @PathVariable String id, HttpServletRequest r) {
        return versions.versions(type, id, requests.auth(r));
    }
    @GetMapping("/documents/{type}/{id}/versions/{version}")
    public JsonNode version(@PathVariable String type, @PathVariable String id, @PathVariable int version, HttpServletRequest r) {
        return versions.get(type, id, version, requests.auth(r));
    }
    @PostMapping("/documents/{type}/{id}/attachment-commands")
    public JsonNode attachmentCommand(@PathVariable String type, @PathVariable String id, HttpServletRequest r) {
        return versions.attachment(type, id, requests.body(r), requests.auth(r));
    }
}
