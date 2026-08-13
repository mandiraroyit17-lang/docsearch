# Document Search Service — Prototype

A simplified, fully in-memory prototype of a multi-tenant document search
service: indexing, full-text search with relevance ranking, tenant
isolation, caching, rate limiting, and a dependency-aware health check.
Everything runs inside the JVM — no Docker, no external database — so it
starts with a single command or the IntelliJ Run button.

## Why in-memory, and what it maps to in production

| Concern | Prototype | Production |
|---|---|---|
| Document/tenant metadata | H2 (in-memory JPA) | PostgreSQL / CockroachDB |
| Full-text search + ranking | Embedded Apache Lucene | Elasticsearch cluster (shards + replicas) |
| Caching | Caffeine, in-process, 30s TTL | Redis |
| Rate limiting | Hand-rolled in-memory token bucket, per tenant | Same algorithm, counters moved to Redis for cross-instance enforcement |

Each of these sits behind a narrow interface — `DocumentRepository`,
`LuceneIndexService`, the `@Cacheable`/`@CacheEvict` abstraction — so
swapping any one for its production counterpart means changing a
dependency and a config class, not the controller or service layer.

## Running it

```bash
mvn spring-boot:run
```

Or open the project in IntelliJ and run `DocsearchApplication` directly.
Starts on `http://localhost:8080`. First run will download Maven
dependencies, so make sure you're online.

## Docker Compose

Not included, deliberately: the prototype has no external services to
orchestrate (H2, Lucene, and the cache all run in-process). See
`DOCUMENTATION.md` for the docker-compose'd production topology
(Postgres/CockroachDB, Elasticsearch, Redis) this architecture maps to.

## Endpoints

| Method | Path | Notes |
|---|---|---|
| POST | `/documents` | Body: `{tenantId, title, content}` |
| GET | `/search?q={query}&tenant={tenantId}` | Add `&page=` / `&size=` for pagination |
| GET | `/documents/{id}` | Requires `X-Tenant-ID` header |
| DELETE | `/documents/{id}` | Requires `X-Tenant-ID` header |
| GET | `/health` | Per-dependency status (DB, search index) |

See `requests.http` for ready-to-run examples (IntelliJ's built-in HTTP
client — open the file and click the ▶ next to each request), or use curl:

```bash
curl -X POST http://localhost:8080/documents \
  -H "Content-Type: application/json" \
  -d '{"tenantId":"acme","title":"Q3 Roadmap","content":"Search infrastructure migration plan for Q3"}'

curl "http://localhost:8080/search?q=migration&tenant=acme"

curl http://localhost:8080/documents/{id} -H "X-Tenant-ID: acme"

curl -X DELETE http://localhost:8080/documents/{id} -H "X-Tenant-ID: acme"

curl http://localhost:8080/health
```

## Known limitations (intentional, given the time box)

- **Tenant ID is client-supplied** (header or query param), not derived
  from a validated auth token. Isolation is still enforced everywhere it
  matters — every query is scoped by tenant at the repository/index layer,
  not just checked in the controller — but a production system would
  resolve the tenant from a JWT claim rather than trust the caller.
- **Indexing is synchronous.** A production system would offload it to a
  queue (Kafka/SQS) so writes don't block on index commits, and to absorb
  bursty ingestion.
- **Cache and rate limiter are per-instance**, not shared across a
  horizontally scaled deployment — both would move to Redis.
- **Pagination re-scores from the top on every page** rather than using a
  scroll/search-after cursor. Fine at prototype scale, not at 10M+ docs.
- **New documents can take up to the cache TTL (30s) to appear** in search
  results for a repeated query — an explicit staleness-for-throughput
  trade-off, not a bug.

## AI tool usage note
Claude scaffolded the initial prototype — project structure, the embedded Lucene search service, and the caching/rate-limiting layers — along with a first draft of the Architecture and Production Readiness sections. From there, every implementation decision was mine: choosing a fully in-memory stack (H2, embedded Lucene, Caffeine) given the take-home's time constraints, opting for a hand-written token bucket over a rate-limiting library to avoid an unverified dependency, and defining the tenant-resolution split between header and query parameter to match the assignment's literal /search contract. I reviewed, ran, and manually verified the system end-to-end before submission — including the tenant-isolation checks and multi-store consistency checks described in the README — and the trade-offs discussed in the Production Readiness section (consistency model, cache staleness, rate-limiter scope) reflect that hands-on testing, not just what was drafted for me. The Experience Showcase section reflects my own project history.
