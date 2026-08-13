# Document Search Service — Architecture, Production Readiness & Experience

Companion document to the code in this repository. See `README.md` for how to run and verify the prototype itself.

---

## 1. Architecture Design

### 1.1 High-level architecture

**Prototype (this repository) — everything runs in one JVM:**

```
                    ┌─────────────┐
                    │    Client    │
                    └──────┬──────┘
                           │ HTTP
                           ▼
                ┌───────────────────────┐
                │ RateLimitInterceptor    │   per-tenant token bucket
                └───────────┬────────────┘
                            ▼
                ┌───────────────────────┐
                │  DocumentController     │   REST endpoints, validation
                └───────────┬────────────┘
                            ▼
                ┌───────────────────────┐
                │   DocumentService       │   @Cacheable / @CacheEvict
                └──────┬───────────┬─────┘
                       ▼           ▼
          ┌──────────────────┐ ┌───────────────────────┐
          │ DocumentRepository │ │  LuceneIndexService     │
          │  H2 (metadata)     │ │  embedded Lucene (search)│
          └──────────────────┘ └───────────────────────┘
```

**Production target — what this architecture scales to:**

```
                          ┌────────────┐
                          │   Client    │
                          └─────┬──────┘
                                ▼
                      ┌───────────────────┐
                      │   Load Balancer      │
                      └─────────┬──────────┘
                                ▼
                ┌───────────────────────────────┐
                │  App instances (stateless) × N   │  auto-scaled
                │  Spring Boot + rate limiter        │
                └───┬─────────────┬───────────┬───┘
                    ▼             ▼           ▼
              ┌──────────┐  ┌───────────┐ ┌─────────────────┐
              │  Redis     │  │ Postgres/  │ │  Elasticsearch    │
              │ cache +    │  │ CockroachDB│ │  cluster           │
              │ rate limits│  │ (metadata) │ │ (shards + replicas)│
              └──────────┘  └───────────┘ └─────────────────┘
                                                    ▲
                                              index writes
                                                    │
                                          ┌───────────────────┐
                                          │  Indexing workers    │
                                          └─────────┬─────────┘
                                                    ▲
                                          ┌───────────────────┐
                                          │   Kafka / SQS         │
                                          └───────────────────┘
```

Every box on the right maps to an interface already in the codebase (`DocumentRepository`, `LuceneIndexService`, the `@Cacheable` abstraction) — swapping the implementation behind each doesn't touch the controller or service layer.

### 1.2 Data flow

**Indexing — `POST /documents`:**
1. Request passes `RateLimitInterceptor` (per-tenant token bucket)
2. `DocumentController` validates the body (`@Valid` on `DocumentRequest`)
3. `DocumentService.indexDocument()` writes metadata to the relational store — source of truth for CRUD
4. Same call indexes into the search engine — source of truth for search (analyzed, tokenized, committed)
5. Generated id returned to the caller

In production, step 4 decouples from step 3: the app publishes an event after the DB commit; a separate indexing worker pool consumes it and writes to Elasticsearch. That means write throughput to `/documents` is bounded by the metadata store, not by Elasticsearch's indexing rate, and a temporary search-cluster hiccup delays search-visibility rather than rejecting writes outright.

**Search — `GET /search`:**
1. Request passes `RateLimitInterceptor`
2. `DocumentController` extracts `q`, `tenant`, `page`, `size`
3. `DocumentService.search()` checks the cache first (keyed on tenant + query + page + size)
4. Cache miss → query hits the search engine: text match (`MUST`) combined with a tenant `TermQuery` (`FILTER`, not scored)
5. Results scored, paginated, cached briefly, returned

### 1.3 Storage strategy

| Concern | Prototype | Production | Why |
|---|---|---|---|
| Metadata/tenant store | H2 (in-memory JPA) | PostgreSQL / CockroachCB | Simple key-based reads/writes — not the bottleneck. CockroachDB earns its complexity specifically if multi-region write availability is a requirement. |
| Search + ranking | Embedded Apache Lucene | Elasticsearch cluster | Same ranking engine (BM25) — Elasticsearch *is* Lucene, distributed. Needed once a single node can't hold the index or serve the QPS target. |
| Cache | Caffeine, in-process, 30s TTL | Redis | Must be shared across horizontally scaled app instances; an in-process cache can't do that. |
| Rate limiting | Hand-rolled in-memory token bucket | Same algorithm, Redis-backed counters | Per-instance limits are meaningless once there's more than one instance behind a load balancer. |

### 1.4 API design

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/documents` | Index a new document |
| `GET` | `/search?q={query}&tenant={tenantId}&page=&size=` | Full-text search, tenant-scoped, paginated |
| `GET` | `/documents/{id}` | Fetch a document (requires `X-Tenant-ID` header) |
| `DELETE` | `/documents/{id}` | Remove a document (requires `X-Tenant-ID` header) |
| `GET` | `/health` | Per-dependency status |

Example — index:
```json
POST /documents
{ "tenantId": "acme", "title": "Q3 Roadmap", "content": "Search infrastructure migration plan" }

→ 201
{ "id": "3f2a1c9e-...", "tenantId": "acme", "title": "Q3 Roadmap", "content": "...", "createdAt": "..." }
```

Example — search:
```json
GET /search?q=migration&tenant=acme

→ 200
[ { "id": "3f2a1c9e-...", "title": "Q3 Roadmap", "score": 1.42 } ]
```

### 1.5 Consistency model and trade-offs

- **Metadata store and search index are eventually consistent, not transactional together.** A crash between the DB commit and the Lucene commit leaves a document fetchable by id but invisible to search. In production, the queue makes this window explicit and monitorable instead of implicit.
- **Search results are cached for 30s.** A newly indexed document may not appear in a *repeated identical query* for up to that TTL — an explicit throughput-for-freshness trade-off, not a bug.
- **Deletes evict the entire shared search cache**, not a scoped subset — simple and guarantees no stale-delete leakage, at the cost of a temporary cache-hit dip for unrelated queries after any delete.

### 1.6 Caching strategy

Single layer in the prototype: a read-through cache in front of the search engine (`@Cacheable` on `DocumentService.search()`), keyed per tenant so cache entries never cross tenant boundaries. In production this stays conceptually the same layer, just backed by Redis instead of in-process Caffeine, plus Elasticsearch's own internal filter-cache (query-time bitsets for clauses like the tenant `FILTER`, cached automatically by the engine).

### 1.7 Message queue usage

Not present in the prototype — deliberately, to keep the setup dependency-free. Where it fits in production:
- **Indexing**: decouples the write path from the search engine's indexing rate (see 1.2). A queue also absorbs bursty bulk-ingestion without that burst competing with live search traffic for the same resources.
- **Why it matters at the stated scale**: 1000+ QPS is a *read* target. Without a queue, a large ingestion job and peak search traffic contend for the same Elasticsearch cluster capacity at the worst possible time.

### 1.8 Multi-tenancy approach

Tenant id is resolved from the `X-Tenant-ID` header (`/documents/{id}`) or a `tenant` query param (`/search`, matching the assignment's literal contract). Isolation is enforced **at the query layer, not just the controller**: `DocumentRepository.findByIdAndTenantId` and the Lucene `FILTER` clause mean there's no code path where one tenant's request can return another tenant's data.

The gap, stated plainly: the tenant id is currently client-supplied, not derived from a verified identity. Production fix: resolve it from a JWT claim after authentication, so a caller can't simply assert a different tenant. At the storage layer, Elasticsearch would additionally use **custom routing on `tenantId`**, so each tenant's documents land on a predictable shard subset — isolation and a performance win together, since a search only has to touch the shards that could possibly contain that tenant's data.

---

## 2. Production Readiness Analysis

**Scalability (100x growth):** Elasticsearch scales by adding data nodes and rebalancing shards; app tier scales horizontally behind the load balancer since it's stateless; cache and rate limiter move to Redis so limits stay meaningful across instances; metadata store scales via read replicas (Postgres) or native range partitioning (CockroachDB).

**Resilience:** Circuit breaker (e.g., Resilience4j) around Elasticsearch calls, falling back to cached-only results rather than a hard failure; retry with exponential backoff for transient errors; Elasticsearch replica shards provide failover if a data node is lost; metadata store failover to a replica on primary loss.

**Security:** Tenant resolved from a validated JWT claim, not a client-supplied header/param; TLS in transit; encryption at rest for both the metadata store and the search cluster; query text already passes through `QueryParser.escape()` to prevent Lucene query-syntax injection — the same discipline extends to any user input reaching a query DSL; secrets (DB credentials, signing keys) in a vault, not config files.

**Observability:** Metrics via Micrometer/Prometheus — per-endpoint latency histograms, cache hit rate, rate-limiter rejection count; structured logs carrying tenant id and a trace id on every line; distributed tracing (OpenTelemetry) across the app → queue → indexing worker → search engine hops, so a slow search is traceable to a specific stage rather than a black box.

**Performance:** Elasticsearch shard count sized to actual data volume up front (resharding later is expensive); periodic segment force-merge on write-heavy indices; bulk indexing API from queue consumers instead of one-document-at-a-time; a composite index on `(tenant_id, created_at)` in the metadata store for any future list/audit queries.

**Operations:** CI/CD via Jenkins/GitHub Actions with SonarQube quality gates ahead of deploy; rolling or blue-green deploys, straightforward since the app tier is stateless; schema migrations via Flyway rather than the prototype's `ddl-auto=update`; automated Elasticsearch snapshots to object storage and point-in-time recovery for the metadata store.

**SLA (99.95% availability ≈ ~4.4 hours of downtime/year budget):** Multi-AZ deployment for app tier, search cluster, and metadata store; replica shards so a single node loss isn't an outage; load-balancer health checks pulling unhealthy instances out of rotation automatically; graceful degradation (serve cached or partial results) over hard failure when a dependency is impaired.

---

## 3. Experience Showcase

> The two entries below marked **[FILL IN]** need your actual specifics — I only have what's in your resume, and inventing incident details would be dishonest in a job application. Everything else is drafted directly from your stated experience.

**A similar distributed system you've built, and its scale/impact**

At JPMorgan Chase, I led end-to-end development of Autom8, a distributed system (React.js frontend, Spring Boot microservices backend) that consolidated three legacy applications into one platform. The project reduced maintenance costs by 30% and improved response time by 40%, and was recognized with a Certificate of Recognition as one of the team's highest-visibility engineering achievements.

**A performance optimization that resulted in significant improvement**

As part of the Autom8 rebuild, I refactored the frontend UI components using React.js, Context API, and modern hooks, which reduced component re-render overhead and improved rendering performance.

*[FILL IN: if you have a specific measured number for this — e.g., a reduction in re-render count, load time, or a profiler comparison before/after — add it here. A concrete figure is what separates this from a generic-sounding claim in an interview follow-up.]*

**A critical production incident you resolved in a distributed system**

*[FILL IN — this needs a real incident from your SocGen or JPMC production-support work, not a generic one. Use this structure: what broke (symptom, which service) → how you found it (alert, Splunk, user report) → immediate mitigation → root cause → the actual fix → what changed afterward to prevent recurrence. Your resume states you "lead cross-team production support coordination, ensuring rapid incident triage" at SocGen — that's the responsibility; this section needs one specific instance of it.]*

**An architectural decision that balanced competing concerns**

The decision to consolidate three legacy applications into Autom8 rather than incrementally patch them individually balanced short-term migration risk and effort against long-term maintainability — the 30% maintenance-cost reduction is the measured outcome of that trade-off.

*[FILL IN: if you remember the specific competing concerns you weighed at the time — e.g., a phased rollout vs. big-bang migration decision, a build-vs-buy call, or a consistency-vs-availability trade-off in Autom8's design — a sentence or two of that reasoning makes this answer much stronger than the outcome alone.]*

---

## 4. AI Tool Usage Note

Claude (Anthropic) was used to scaffold this prototype: project structure, the embedded Lucene search service, the caching and rate-limiting layers, and the drafting of this documentation's Architecture and Production Readiness sections. All code was reviewed and the underlying trade-offs (consistency model, tenant isolation, caching strategy) understood before submission — see the "known limitations" section of `README.md` for an explicit list of what the prototype does and doesn't demonstrate at production scale. The Experience Showcase section reflects the candidate's own project history.
