package lk.kdu.wallet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

@EnabledIfSystemProperty(named = "e2e", matches = "true")
class IdempotencyE2ETest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String BASE = "http://localhost:8080";

    @Test
    void fiftyParallelIdenticalTopupsApplyExactlyOnce() throws Exception {
        String wallet = createWallet();
        String key = UUID.randomUUID().toString();
        String path = "/wallets/" + wallet + "/topups";

        int n = 50;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<HttpResponse<String>>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return post(path, key, "{\"amountMinor\":1000}");
            }));
        }
        start.countDown();

        int replayed = 0;
        for (Future<HttpResponse<String>> f : futures) {
            HttpResponse<String> r = f.get();
            assertEquals(201, r.statusCode());
            if ("true".equals(r.headers().firstValue("Idempotent-Replayed").orElse("false"))) replayed++;
        }
        pool.shutdown();

        assertEquals(n - 1, replayed, "exactly one request may do the work");
        assertEquals("1000", field(get("/wallets/" + wallet + "/balance"), "balanceMinor"));
        assertEquals("1", field(get("/wallets/" + wallet + "/balance"), "lastSeq"));
    }

    @Test
    void sameKeyWithDifferentAmountIsRejected() throws Exception {
        String wallet = createWallet();
        String key = UUID.randomUUID().toString();
        String path = "/wallets/" + wallet + "/topups";

        assertEquals(201, post(path, key, "{\"amountMinor\":500}").statusCode());
        assertEquals(422, post(path, key, "{\"amountMinor\":999}").statusCode());
        assertEquals("500", field(get("/wallets/" + wallet + "/balance"), "balanceMinor"));
    }

    @Test
    void withdrawalBeyondBalanceIsRejected() throws Exception {
        String wallet = createWallet();
        assertEquals(409, post("/wallets/" + wallet + "/withdrawals", UUID.randomUUID().toString(),
                "{\"amountMinor\":1}").statusCode());
    }

    @Test
    void parallelDifferentKeysNeverLoseAnUpdate() throws Exception {
        String wallet = createWallet();
        String path = "/wallets/" + wallet + "/topups";

        int n = 8;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<HttpResponse<String>>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return post(path, UUID.randomUUID().toString(), "{\"amountMinor\":100}");
            }));
        }
        start.countDown();
        for (Future<HttpResponse<String>> f : futures) {
            assertEquals(201, f.get().statusCode());
        }
        pool.shutdown();

        assertEquals("800", field(get("/wallets/" + wallet + "/balance"), "balanceMinor"));
        assertEquals("8", field(get("/wallets/" + wallet + "/balance"), "lastSeq"));
    }

    private static String createWallet() throws Exception {
        HttpResponse<String> r = post("/wallets", UUID.randomUUID().toString(), "{\"ownerName\":\"Test\"}");
        assertEquals(201, r.statusCode());
        return field(r.body(), "walletId");
    }

    private static HttpResponse<String> post(String path, String key, String json) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(BASE + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (key != null) b.header("Idempotency-Key", key);
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String get(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + path)).GET().build();
        return HTTP.send(req, HttpResponse.BodyHandlers.ofString()).body();
    }

    private static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*\"?([^\",}]+)").matcher(json);
        if (!m.find()) throw new AssertionError("Field " + name + " not found in " + json);
        return m.group(1);
    }
}