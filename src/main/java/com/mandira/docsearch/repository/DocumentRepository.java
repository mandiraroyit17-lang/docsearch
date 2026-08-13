package com.mandira.docsearch.repository;

import com.mandira.docsearch.model.DocumentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DocumentRepository extends JpaRepository<DocumentEntity, String> {

    // Scoping every lookup by tenantId here (not just in the controller)
    // is what actually enforces isolation — a missing WHERE clause on this
    // query would be a real cross-tenant data leak, not just a style issue.
    Optional<DocumentEntity> findByIdAndTenantId(String id, String tenantId);
}
