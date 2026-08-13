package com.mandira.docsearch.controller;

import com.mandira.docsearch.dto.DocumentRequest;
import com.mandira.docsearch.dto.DocumentResponse;
import com.mandira.docsearch.dto.SearchResultItem;
import com.mandira.docsearch.model.DocumentEntity;
import com.mandira.docsearch.service.DocumentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class DocumentController {

    private final DocumentService documentService;

    /**
     * Constructs the DocumentController with the document service.
     *
     * @param documentService the service for document operations
     */
    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    /**
     * Indexes a new document.
     *
     * Accepts a document creation request with tenant id, title, and content.
     * Returns the created document with a generated id and creation timestamp.
     *
     * @param request the document request containing tenant id, title, and content
     * @return a 201 CREATED response with the indexed document
     */
    @PostMapping("/documents")
    public ResponseEntity<DocumentResponse> indexDocument(@Valid @RequestBody DocumentRequest request) {
        DocumentEntity saved = documentService.indexDocument(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(saved));
    }

    // Tenant via header here — the assignment leaves this endpoint's
    // multi-tenancy mechanism open ("header-based or path-based").
    /**
     * Retrieves a document by id for the authenticated tenant.
     *
     * Tenant is passed via the X-Tenant-ID header. Returns 404 if the document
     * is not found or belongs to a different tenant.
     *
     * @param id       the document id (path variable)
     * @param tenantId the tenant identifier from X-Tenant-ID header
     * @return a 200 OK response with the document, or 404 NOT FOUND if not found
     */
    @GetMapping("/documents/{id}")
    public ResponseEntity<DocumentResponse> getDocument(
            @PathVariable String id,
            @RequestHeader("X-Tenant-ID") String tenantId) {
        return documentService.getDocument(id, tenantId)
                .map(doc -> ResponseEntity.ok(toResponse(doc)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Deletes a document by id for the authenticated tenant.
     *
     * Tenant is passed via the X-Tenant-ID header. Returns 204 NO CONTENT
     * regardless of whether the document existed (idempotent).
     *
     * @param id       the document id (path variable)
     * @param tenantId the tenant identifier from X-Tenant-ID header
     * @return a 204 NO CONTENT response
     */
    @DeleteMapping("/documents/{id}")
    public ResponseEntity<Void> deleteDocument(
            @PathVariable String id,
            @RequestHeader("X-Tenant-ID") String tenantId) {
        documentService.deleteDocument(id, tenantId);
        return ResponseEntity.noContent().build();
    }

    // Tenant as a query param here matches the assignment's literal
    // endpoint contract: GET /search?q={query}&tenant={tenantId}.
    // In production this would be resolved from a validated auth token
    // instead of a client-supplied parameter — see README security notes.
    /**
     * Searches for documents by query text.
     *
     * Accepts query and tenant as request parameters (matches the assignment's
     * literal endpoint contract: GET /search?q={query}&tenant={tenantId}).
     * In production, tenantId would be resolved from a validated auth token
     * instead of a client-supplied parameter.
     * Supports pagination via page and size parameters (defaults: page=0, size=10).
     *
     * @param query    the search query text (required)
     * @param tenantId the tenant identifier (required, supplied as query parameter)
     * @param page     the page number (0-based, default 0)
     * @param size     the page size (default 10)
     * @return a 200 OK response with the paginated search results
     */
    @GetMapping("/search")
    public ResponseEntity<List<SearchResultItem>> search(
            @RequestParam("q") String query,
            @RequestParam("tenant") String tenantId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(documentService.search(tenantId, query, page, size));
    }

    /**
     * Converts a DocumentEntity to its API response representation.
     *
     * @param doc the document entity
     * @return the response DTO with all document fields
     */
    private DocumentResponse toResponse(DocumentEntity doc) {
        return new DocumentResponse(doc.getId(), doc.getTenantId(), doc.getTitle(), doc.getContent(), doc.getCreatedAt());
    }
}
