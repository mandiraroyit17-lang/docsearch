package com.mandira.docsearch.dto;

/**
 * Response DTO for search results.
 *
 * Represents a single search hit with relevance score.
 *
 * @param id    the document identifier
 * @param title the document title
 * @param score the Lucene relevance score (higher is more relevant)
 */
public record SearchResultItem(String id, String title, float score) {
}
