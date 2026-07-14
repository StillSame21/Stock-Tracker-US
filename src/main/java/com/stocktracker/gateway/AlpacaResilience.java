package com.stocktracker.gateway;

import java.time.Duration;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.HttpStatusCodeException;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.springframework.web.client.HttpClientErrorException;

/**
 * Rate limit, retry and circuit-break every outbound Alpaca call, wired
 * functionally against the resilience4j core jars — deliberately not
 * {@code resilience4j-spring-boot3}/{@code spring-boot4} (SETUP.md §7): those
 * starters are chasing Spring Framework 7 compatibility and the exact API
 * surface can't be verified without live docs access, so this sidesteps the
 * question entirely. Same reasoning covers using core {@code Retry} here
 * instead of Spring Boot 4's native {@code @Retryable} the plan mentions —
 * this repo doesn't pin a Spring Framework 7 annotation package it can't
 * verify compiles.
 */
@Component
public class AlpacaResilience {

    private final RateLimiter rateLimiter;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;

    public AlpacaResilience(AlpacaProperties properties) {
        this.rateLimiter = RateLimiter.of("alpaca", RateLimiterConfig.custom()
                .limitForPeriod(properties.rateLimitPerMinute())   // 10% headroom under the 200 rpm ceiling (C7)
                .limitRefreshPeriod(Duration.ofMinutes(1))
                .timeoutDuration(Duration.ofSeconds(5))
                .build());

        this.circuitBreaker = CircuitBreaker.of("alpaca", CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .build());

        this.retry = Retry.of("alpaca", RetryConfig.custom()
                .maxAttempts(4)
                .intervalFunction(IntervalFunction.ofExponentialBackoff(Duration.ofMillis(200), 2.0))
                .retryOnException(AlpacaResilience::isRetryable)
                .build());
    }

    // Composed by hand rather than via the resilience4j-all Decorators helper — that
    // artifact pulls in every module (bulkhead, timelimiter, etc.) for one convenience
    // wrapper we don't otherwise need. Recommended nesting order per resilience4j docs:
    // Retry(CircuitBreaker(RateLimiter(call))).
    public <T> T call(Supplier<T> operation) {
        Supplier<T> decorated = RateLimiter.decorateSupplier(rateLimiter, operation);
        decorated = CircuitBreaker.decorateSupplier(circuitBreaker, decorated);
        decorated = Retry.decorateSupplier(retry, decorated);
        return decorated.get();
    }

    private static boolean isRetryable(Throwable t) {
        if (t instanceof HttpClientErrorException.TooManyRequests) {
            return true;
        }
        return t instanceof HttpServerErrorException
                || (t instanceof HttpStatusCodeException e && e.getStatusCode().is5xxServerError());
    }
}
