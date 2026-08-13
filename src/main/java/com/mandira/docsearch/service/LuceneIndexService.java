package com.mandira.docsearch.service;

import jakarta.annotation.PreDestroy;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Embedded, in-memory search index built directly on Apache Lucene — the
 * same engine Elasticsearch wraps. Kept behind this service so a real
 * Elasticsearch (or OpenSearch) client can be swapped in for production
 * without touching {@link DocumentService} or the controller layer.
 *
 * Not clustered: a real deployment needs a distributed index (Elasticsearch
 * shards/replicas) since this one lives entirely in one JVM's memory.
 */
@Service
public class LuceneIndexService {

    private final Directory directory = new ByteBuffersDirectory();
    private final StandardAnalyzer analyzer = new StandardAnalyzer();
    private final IndexWriter writer;

    /**
     * Initializes the Lucene index service with an in-memory index.
     *
     * @throws IllegalStateException if the index writer fails to initialize
     */
    public LuceneIndexService() {
        try {
            IndexWriterConfig config = new IndexWriterConfig(analyzer);
            this.writer = new IndexWriter(directory, config);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to initialize Lucene index", e);
        }
    }

    /**
     * Indexes a document in the search index.
     *
     * Uses updateDocument to make the operation idempotent — re-indexing the same
     * document by id will replace the previous version rather than creating duplicates.
     *
     * @param id        the unique document identifier
     * @param tenantId  the tenant identifier for multi-tenancy isolation
     * @param title     the document title (searchable, stored)
     * @param content   the document content (searchable, not stored)
     * @throws IllegalStateException if indexing fails
     */
    public void index(String id, String tenantId, String title, String content) {
        try {
            Document doc = new Document();
            doc.add(new StringField("id", id, Field.Store.YES));
            doc.add(new StringField("tenantId", tenantId, Field.Store.YES));
            doc.add(new TextField("title", title, Field.Store.YES));
            doc.add(new TextField("content", content, Field.Store.NO));
            // updateDocument = delete-then-add by id, so re-indexing is idempotent
            writer.updateDocument(new Term("id", id), doc);
            writer.commit();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to index document " + id, e);
        }
    }

    /**
     * Deletes a document from the search index by its id.
     *
     * @param id the unique document identifier
     * @throws IllegalStateException if deletion fails
     */
    public void delete(String id) {
        try {
            writer.deleteDocuments(new Term("id", id));
            writer.commit();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to delete document " + id, e);
        }
    }

    /**
     * Tenant filtering happens here, inside the query itself (FILTER clause,
     * not scored) — so a bug in a caller can never accidentally return
     * another tenant's documents just by omitting a check.
     *
     * Pagination re-runs the full scored search and slices the top N each
     * call; fine at prototype scale, but at 10M+ docs this should move to
     * a search-after cursor rather than re-scoring from page zero.
     */
    public List<SearchHit> search(String tenantId, String queryText, int page, int size) {
        try (IndexReader reader = DirectoryReader.open(writer)) {
            IndexSearcher searcher = new IndexSearcher(reader);

            QueryParser parser = new QueryParser("content", analyzer);
            Query textQuery = parser.parse(QueryParser.escape(queryText));
            Query tenantFilter = new TermQuery(new Term("tenantId", tenantId));

            BooleanQuery combined = new BooleanQuery.Builder()
                    .add(textQuery, BooleanClause.Occur.MUST)
                    .add(tenantFilter, BooleanClause.Occur.FILTER)
                    .build();

            int fetchLimit = Math.max((page + 1) * size, size);
            TopDocs topDocs = searcher.search(combined, fetchLimit);

            List<SearchHit> results = new ArrayList<>();
            ScoreDoc[] hits = topDocs.scoreDocs;
            int from = page * size;
            for (int i = from; i < Math.min(hits.length, from + size); i++) {
                Document d = reader.document(hits[i].doc);
                results.add(new SearchHit(d.get("id"), d.get("title"), hits[i].score));
            }
            return results;
        } catch (IOException | ParseException e) {
            throw new IllegalStateException("Search failed for tenant " + tenantId, e);
        }
    }

    /**
     * Health check method to verify the index is functional.
     *
     * @return true if the index can be opened and read; false if any I/O error occurs
     */
    public boolean isHealthy() {
        try (IndexReader reader = DirectoryReader.open(writer)) {
            return reader.numDocs() >= 0;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Closes and releases all Lucene resources.
     *
     * Called automatically by Spring when the application context shuts down.
     *
     * @throws IOException if an error occurs while closing resources
     */
    @PreDestroy
    public void close() throws IOException {
        writer.close();
        directory.close();
    }

    public record SearchHit(String id, String title, float score) {
    }
}
