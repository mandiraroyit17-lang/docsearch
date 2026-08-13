package com.mandira.docsearch.config;

import com.mandira.docsearch.ratelimit.RateLimitInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final RateLimitInterceptor rateLimitInterceptor;

    /**
     * Constructs WebConfig with the rate limit interceptor.
     *
     * @param rateLimitInterceptor the interceptor for enforcing per-tenant rate limits
     */
    public WebConfig(RateLimitInterceptor rateLimitInterceptor) {
        this.rateLimitInterceptor = rateLimitInterceptor;
    }

    /**
     * Registers the rate limit interceptor for all document and search endpoints.
     *
     * Applies per-tenant rate limiting to /documents/** and /search paths.
     *
     * @param registry the interceptor registry to configure
     */
    @Override
    public void addInterceptors(@NonNull InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/documents/**", "/search");
    }
}
