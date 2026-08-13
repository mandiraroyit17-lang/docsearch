package com.mandira.docsearch;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class DocsearchApplicationTests {

    @Test
    void contextLoads() {
        // Verifies the whole wiring — controllers, services, repository,
        // Lucene index, cache manager, rate limiter — comes up cleanly.
    }
}
