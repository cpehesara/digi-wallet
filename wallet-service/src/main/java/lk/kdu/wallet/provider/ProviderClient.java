package lk.kdu.wallet.provider;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ProviderClient {

    public static class ProviderUnavailableException extends RuntimeException {
        public ProviderUnavailableException(String message) { super(message); }
    }

    private static final Pattern CHARGE_ID = Pattern.compile("\"chargeId\"\\s*:\\s*\"([^\"]+)\"");

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    private final String baseUrl;
    private final Retry retry;
    private final CircuitBreaker breaker;
    private final Bulkhead bulkhead;

    public ProviderClient(@Value("${provider.base-url:http://localhost:8080}") String baseUrl) {
        this.baseUrl = baseUrl;
        this.retry = Retry.of("provider", RetryConfig.custom()
                .maxAttempts(4)
                .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(200, 2.0, 0.5)) // backoff + jitter
                .ignoreExceptions(CallNotPermittedException.class, BulkheadFullException.class)
                .build());
        this.breaker = CircuitBreaker.of("provider", CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .slidingWindowSize(6)
                .minimumNumberOfCalls(4)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .permittedNumberOfCallsInHalfOpenState(2)
                .build());
        this.bulkhead = Bulkhead.of("provider", BulkheadConfig.custom()
                .maxConcurrentCalls(10)
                .maxWaitDuration(Duration.ZERO)
                .build());
    }

    public String charge(String operationId, long amountMinor) {
        Supplier<String> call = () -> doCall(operationId, amountMinor);
        Supplier<String> guarded = Retry.decorateSupplier(retry,
                CircuitBreaker.decorateSupplier(breaker,
                        Bulkhead.decorateSupplier(bulkhead, call)));
        try {
            return guarded.get();
        } catch (CallNotPermittedException e) {
            throw new ProviderUnavailableException("Circuit breaker is OPEN: failing fast");
        } catch (BulkheadFullException e) {
            throw new ProviderUnavailableException("Too many concurrent provider calls");
        } catch (RuntimeException e) {
            throw new ProviderUnavailableException("Provider failed after retries: " + e.getMessage());
        }
    }

    public String breakerState() {
        return breaker.getState().name();
    }

    private String doCall(String operationId, long amountMinor) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/mock-provider/charges"))
                    .timeout(Duration.ofSeconds(2))                       // never wait forever
                    .header("Content-Type", "application/json")
                    .header("Idempotency-Key", operationId)
                    .POST(HttpRequest.BodyPublishers.ofString("{\"amountMinor\":" + amountMinor + "}"))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                throw new IllegalStateException("provider answered HTTP " + res.statusCode());
            }
            Matcher m = CHARGE_ID.matcher(res.body());
            if (!m.find()) throw new IllegalStateException("provider answer had no chargeId");
            return m.group(1);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("provider unreachable: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }
}