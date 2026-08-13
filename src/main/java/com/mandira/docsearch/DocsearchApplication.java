package com.mandira.docsearch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

/**
 * Main entry point for the DocSearch application.
 *
 * A document search service built on embedded Apache Lucene with multi-tenancy
 * support, in-memory caching, and per-tenant rate limiting. Enables caching
 * support via the @EnableCaching annotation.
 */
@SpringBootApplication
@EnableCaching
public class DocsearchApplication {

    /**
     * Starts the DocSearch Spring Boot application.
     *
     * @param args command-line arguments passed to the application
     */
    public static void main(String[] args) {
        SpringApplication.run(DocsearchApplication.class, args);
    }
}
