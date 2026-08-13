package com.mandira.docsearch.controller;

import com.mandira.docsearch.repository.DocumentRepository;
import com.mandira.docsearch.service.LuceneIndexService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class HealthController {

    private final DocumentRepository documentRepository;
    private final LuceneIndexService luceneIndexService;

    /**
     * Constructs the HealthController with repository and search index service dependencies.
     *
     * @param documentRepository   the document repository for database health checks
     * @param luceneIndexService   the Lucene search index service for index health checks
     */
    public HealthController(DocumentRepository documentRepository, LuceneIndexService luceneIndexService) {
        this.documentRepository = documentRepository;
        this.luceneIndexService = luceneIndexService;
    }

    /**
     * Health check endpoint that verifies application dependencies.
     *
     * Checks the health of both the database and search index. Returns the
     * overall application status (UP if all dependencies are UP, DOWN otherwise)
     * along with individual dependency statuses.
     *
     * @return a map containing overall status and individual dependency health checks
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> deps = new LinkedHashMap<>();
        deps.put("database", checkDatabase());
        deps.put("searchIndex", luceneIndexService.isHealthy() ? "UP" : "DOWN");

        boolean allUp = deps.values().stream().allMatch("UP"::equals);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", allUp ? "UP" : "DOWN");
        body.put("dependencies", deps);
        return body;
    }

    /**
     * Checks database connectivity and availability.
     *
     * Performs a simple query count to verify the database connection.
     * Returns "UP" if successful, "DOWN" if any exception occurs.
     *
     * @return "UP" if database is accessible, "DOWN" if unavailable
     */
    private String checkDatabase() {
        try {
            documentRepository.count();
            return "UP";
        } catch (Exception e) {
            return "DOWN";
        }
    }
}
