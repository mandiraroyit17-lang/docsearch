package com.mandira.docsearch.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request DTO for creating a new document.
 *
 * All fields are required and must not be blank.
 *
 * @param tenantId the tenant identifier for multi-tenancy isolation
 * @param title    the document title
 * @param content  the document content
 */
public record DocumentRequest(
        @NotBlank String tenantId,
        @NotBlank String title,
        @NotBlank String content
) {
}
