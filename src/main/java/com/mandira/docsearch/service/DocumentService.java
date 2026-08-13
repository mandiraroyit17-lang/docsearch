package com.mandira.docsearch.service;

import com.mandira.docsearch.dto.DocumentRequest;
import com.mandira.docsearch.dto.SearchResultItem;
import com.mandira.docsearch.model.DocumentEntity;
import com.mandira.docsearch.repository.DocumentRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class DocumentService {

    private final DocumentRepository repository;
    private final LuceneIndexService luceneIndexService;

    /**
     * Constructs a DocumentService with the repository and Lucene index service.
     *
     * @param repository          the document repository for persistence
     * @param luceneIndexService  the Lucene search index service
     */
    public DocumentService(DocumentRepository repository, LuceneIndexService luceneIndexService) {
        this.repository = repository;
        this.luceneIndexService = luceneIndexService;
    }

    /**
     * Indexes a new document for search.
     *
     * Persists the document to the database and immediately adds it to the Lucene index.
     * Search cache is intentionally not evicted — there is a documented staleness-for-throughput
     * trade-off where newly indexed documents may take up to the cache TTL to appear in search results.
     *
     * @param request the document creation request containing tenant, title, and content
     * @return the saved document entity with generated id and creation timestamp
     */
    public DocumentEntity indexDocument(DocumentRequest request) {
        DocumentEntity saved = repository.save(
                new DocumentEntity(request.tenantId(), request.title(), request.content())
        );
        luceneIndexService.index(saved.getId(), saved.getTenantId(), saved.getTitle(), saved.getContent());
        // Deliberately not evicting the search cache here: a freshly indexed
        // doc may take up to the cache TTL to appear in search results.
        // Documented staleness-for-throughput trade-off — see README.
        return saved;
    }

    /**
     * Retrieves a document by id for the specified tenant.
     *
     * Ensures tenant isolation by requiring both id and tenantId in the query.
     *
     * @param id       the unique document identifier
     * @param tenantId the tenant identifier
     * @return an Optional containing the document if found and owned by the tenant, otherwise empty
     */
    public Optional<DocumentEntity> getDocument(String id, String tenantId) {
        return repository.findByIdAndTenantId(id, tenantId);
    }

    /**
     * Deletes a document by id for the specified tenant.
     *
     * Removes the document from both the database and the Lucene index, then evicts all
     * search result cache entries. Uses a coarse-grained cache eviction strategy; production
     * deployments with Redis would evict by tenant-scoped key patterns instead.
     *
     * @param id       the unique document identifier
     * @param tenantId the tenant identifier
     */
    @CacheEvict(value = "searchResults", allEntries = true)
    public void deleteDocument(String id, String tenantId) {
        repository.findByIdAndTenantId(id, tenantId)
                .ifPresent(doc -> {
                    repository.delete(doc);
                    luceneIndexService.delete(id);
                });
        // Evicting all entries (not just this tenant's) is coarse but simple;
        // a Redis-backed cache in production would evict by tenant-scoped
        // key pattern instead.
    }

    /**
     * Searches for documents by query text for a specific tenant.
     *
     * Results are cached by tenant, query, page, and size. Returns paginated search hits
     * sorted by relevance score. Tenant isolation is enforced at the Lucene query level.
     *
     * @param tenantId the tenant identifier for multi-tenancy isolation
     * @param query    the search query text
     * @param page     the page number (0-based)
     * @param size     the number of results per page
     * @return a list of search result items matching the query, paginated and cached
     */
    @Cacheable(value = "searchResults", key = "#tenantId + ':' + #query + ':' + #page + ':' + #size")
    public List<SearchResultItem> search(String tenantId, String query, int page, int size) {
        return luceneIndexService.search(tenantId, query, page, size).stream()
                .map(hit -> new SearchResultItem(hit.id(), hit.title(), hit.score()))
                .toList();
    }
}
