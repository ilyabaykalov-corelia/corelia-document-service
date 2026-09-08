package ru.corelia.documents;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import ru.corelia.http.ApiRequest;
import ru.corelia.profile.ProductProfile;

import tools.jackson.databind.JsonNode;

/** Внутренний API карточек, одинаковый для всех настроенных видов документов. */
@RestController
@RequestMapping("/internal/v1")
public class DocumentController {
    private final ru.corelia.integration.DataSpaceClient data;
    private final DocumentService documents;
    private final ApiRequest requests;
    private final ProductProfile profile;

    public DocumentController(
            DocumentService documents,
            ApiRequest requests,
            ProductProfile profile,
            ru.corelia.integration.DataSpaceClient data) {
        this.data = data;
        this.documents = documents;
        this.requests = requests;
        this.profile = profile;
    }

    @GetMapping("/document-types/available")
    public JsonNode available(HttpServletRequest request) {
        String operation =
                ProductProfile.identifier(
                        ru.corelia.support.Json.text(
                                profile.settings("documentTypeCatalog"), "query"));
        String requestName =
                ProductProfile.identifier(
                        ru.corelia.support.Json.fallback(
                                ru.corelia.support.Json.text(
                                        profile.settings("documentTypeCatalog"), "requestName"),
                                operation));
        JsonNode page =
                data.execute(
                                "query "
                                        + requestName
                                        + " { "
                                        + operation
                                        + " { elems { id name } count } }",
                                ru.corelia.support.Json.object(),
                                requests.auth(request))
                        .path(operation);
        return ru.corelia.support.Json.object(
                "items", page.path("elems"), "total", page.path("count"));
    }

    @GetMapping("/document-types")
    public JsonNode types() {
        return profile.catalog();
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
