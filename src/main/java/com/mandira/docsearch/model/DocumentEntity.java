package com.mandira.docsearch.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Document metadata, persisted in H2 for the prototype. The searchable
 * content itself is also written into the Lucene index (see
 * {@link com.mandira.docsearch.service.LuceneIndexService}) — this table
 * is the source of truth for CRUD, the index is the source of truth for
 * search.
 */
@Entity
@Table(name = "documents")
public class DocumentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String tenantId;

    @Column(nullable = false)
    private String title;

    @Lob
    @Column(nullable = false)
    private String content;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    /**
     * No-arg constructor required by JPA for entity instantiation.
     */
    protected DocumentEntity() {
        // required by JPA
    }

    /**
     * Constructs a new DocumentEntity with the given tenant, title, and content.
     *
     * The id and createdAt fields are automatically generated.
     *
     * @param tenantId the tenant identifier for multi-tenancy isolation
     * @param title    the document title
     * @param content  the document content
     */
    public DocumentEntity(String tenantId, String title, String content) {
        this.tenantId = tenantId;
        this.title = title;
        this.content = content;
    }

    /**
     * Gets the unique document identifier.
     *
     * @return the document id (UUID)
     */
    public String getId() {
        return id;
    }

    /**
     * Gets the tenant identifier.
     *
     * @return the tenant id
     */
    public String getTenantId() {
        return tenantId;
    }

    /**
     * Gets the document title.
     *
     * @return the title
     */
    public String getTitle() {
        return title;
    }

    /**
     * Gets the document content.
     *
     * @return the content
     */
    public String getContent() {
        return content;
    }

    /**
     * Gets the document creation timestamp.
     *
     * @return the creation instant (immutable after entity creation)
     */
    public Instant getCreatedAt() {
        return createdAt;
    }
}
