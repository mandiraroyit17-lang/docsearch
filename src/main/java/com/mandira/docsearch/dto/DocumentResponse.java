package com.mandira.docsearch.dto;

import java.time.Instant;

/**
 * Response DTO for document retrieval and creation.
 *
 * Contains all document metadata including the generated id and creation timestamp.
 *
 * @param id        the unique document identifier (UUID)
 * @param tenantId  the tenant identifier
 * @param title     the document title
 * @param content   the document content
 * @param createdAt the document creation timestamp
 */
public record DocumentResponse(
        String id,
        String tenantId,
        String title,
        String content,
        Instant createdAt
) {
}
