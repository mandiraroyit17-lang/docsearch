package com.mandira.docsearch.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Applies a per-tenant {@link TokenBucket} to document and search requests.
 * 50-request burst, refilling at 20/sec sustained — generous enough not to
 * interfere with normal demo traffic, tight enough to visibly return 429s
 * under a burst.
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final int CAPACITY = 50;
    private static final int REFILL_PER_SECOND = 20;

    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    /**
     * Pre-processes HTTP requests to enforce per-tenant rate limiting.
     *
     * Resolves the tenant id from either the X-Tenant-ID header or tenant query parameter.
     * Creates a per-tenant token bucket on first request, then checks if the request
     * can consume a token. Returns 429 TOO MANY REQUESTS if the bucket is exhausted.
     *
     * @param request  the incoming HTTP request
     * @param response the HTTP response object (used to write 429 status if rate limited)
     * @param handler  the handler that will process the request
     * @return true if the request should proceed, false if rate limited
     * @throws Exception if an error occurs during request processing
     */
    @Override
    public boolean preHandle(@NonNull HttpServletRequest request,
                              @NonNull HttpServletResponse response,
                              @NonNull Object handler) throws Exception {
        String tenantId = resolveTenantId(request);
        TokenBucket bucket = buckets.computeIfAbsent(tenantId, id -> new TokenBucket(CAPACITY, REFILL_PER_SECOND));

        if (bucket.tryConsume()) {
            return true;
        }

        response.setStatus(429);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"rate limit exceeded for tenant '" + tenantId + "'\"}");
        return false;
    }

    /**
     * Resolves the tenant id from the HTTP request.
     *
     * First checks for the X-Tenant-ID header, then falls back to the tenant
     * query parameter. Returns "unknown" if neither is provided or both are blank.
     *
     * @param request the HTTP request
     * @return the resolved tenant id, or "unknown" if not found
     */
    private String resolveTenantId(HttpServletRequest request) {
        String header = request.getHeader("X-Tenant-ID");
        if (header != null && !header.isBlank()) {
            return header;
        }
        String param = request.getParameter("tenant");
        if (param != null && !param.isBlank()) {
            return param;
        }
        return "unknown";
    }
}
