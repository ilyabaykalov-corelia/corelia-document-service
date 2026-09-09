package ru.corelia.documents;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import ru.corelia.http.ApiRequest;
import ru.corelia.integration.PdsContract;

import tools.jackson.databind.JsonNode;

/** Внутренний API карточек, одинаковый для поддерживаемых видов документов. */
@RestController
@RequestMapping("/internal/v1")
public class DocumentController {
    private final ru.corelia.integration.DataSpaceClient data;
    private final DocumentService documents;
    private final ApiRequest requests;
    private final PlatformDocumentRepository repository;

    public DocumentController(
            DocumentService documents,
            ApiRequest requests,
            ru.corelia.integration.DataSpaceClient data,
            PlatformDocumentRepository repository) {
        this.data = data;
        this.documents = documents;
        this.requests = requests;
        this.repository = repository;
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
        return PdsContract.catalog();
    }

    @PostMapping("/documents/{type}/search")
    public JsonNode search(@PathVariable String type, HttpServletRequest request) {
        return documents.search(type, requests.body(request), requests.auth(request));
    }

    @GetMapping("/documents/{type}/{id}")
    public JsonNode get(
            @PathVariable String type, @PathVariable String id, HttpServletRequest request) {
        return documents.get(type, id, requests.auth(request));
    }

    @GetMapping("/documents/{type}/{id}/versions")
    public JsonNode versions(@PathVariable String type, @PathVariable String id, HttpServletRequest request) {
        return ru.corelia.support.Json.array(repository.versions(type, id, requests.auth(request)));
    }

    @GetMapping("/documents/{type}/{id}/versions/{version}")
    public JsonNode version(@PathVariable String type, @PathVariable String id, @PathVariable int version, HttpServletRequest request) {
        return repository.versions(type, id, requests.auth(request)).stream()
                .filter(item -> item.path("version").asInt() == version)
                .findFirst()
                .orElseThrow(() -> new ru.corelia.http.ApiException(404, "Версия документа не найдена"));
    }

    @PostMapping("/documents/{type}")
    public ResponseEntity<JsonNode> create(@PathVariable String type, HttpServletRequest request) {
        return ResponseEntity.status(201)
                .body(documents.create(type, requests.body(request), requests.auth(request)));
    }

    @PatchMapping("/documents/{type}/{id}")
    public JsonNode update(
            @PathVariable String type, @PathVariable String id, HttpServletRequest request) {
        return documents.update(type, id, requests.body(request), requests.auth(request));
    }
}
