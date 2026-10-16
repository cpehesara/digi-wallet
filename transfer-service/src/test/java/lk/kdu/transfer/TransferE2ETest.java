package lk.kdu.transfer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

@EnabledIfSystemProperty(named = "e2e", matches = "true")
class TransferE2ETest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String WALLET = "http://localhost:8080";
    private static final String TRANSFER = "http://localhost:8081";

    @Test
    void successfulTransferMovesMoneyExactlyOnce() throws Exception {
        String a = newWallet();
        String b = newWallet();
        topUp(a, 1000);

        String key = UUID.randomUUID().toString();
        String id = startTransfer(key, a, b, 400);
        assertEquals(id, startTransfer(key, a, b, 400), "same key must return the same transfer");

        waitForState(id, "COMPLETED");
        assertEquals("600", field(get(WALLET, "/wallets/" + a + "/balance"), "balanceMinor"));
        assertEquals("400", field(get(WALLET, "/wallets/" + b + "/balance"), "balanceMinor"));
    }

    @Test
    void insufficientFundsFailsWithoutMovingMoney() throws Exception {
        String a = newWallet();
        String b = newWallet();
        topUp(a, 100);

        String id = startTransfer(UUID.randomUUID().toString(), a, b, 500);
        waitForState(id, "FAILED");
        assertEquals("100", field(get(WALLET, "/wallets/" + a + "/balance"), "balanceMinor"));
    }

    @Test
    void failedCreditIsCompensated() throws Exception {
        String a = newWallet();
        topUp(a, 1000);
        String ghost = UUID.randomUUID().toString(); // a wallet that does not exist

        String id = startTransfer(UUID.randomUUID().toString(), a, ghost, 300);
        waitForState(id, "COMPENSATED");
        assertEquals("1000", field(get(WALLET, "/wallets/" + a + "/balance"), "balanceMinor"));
    }

    private static String newWallet() throws Exception {
        HttpResponse<String> r = post(WALLET, "/wallets", UUID.randomUUID().toString(), "{\"ownerName\":\"Test\"}");
        assertEquals(201, r.statusCode());
        return field(r.body(), "walletId");
    }

    private static void topUp(String wallet, long amount) throws Exception {
        HttpResponse<String> r = post(WALLET, "/wallets/" + wallet + "/topups", UUID.randomUUID().toString(),
                "{\"amountMinor\":" + amount + "}");
        assertEquals(201, r.statusCode());
    }

    private static String startTransfer(String key, String from, String to, long amount) throws Exception {
        HttpResponse<String> r = post(TRANSFER, "/transfers", key,
                "{\"fromWalletId\":\"" + from + "\",\"toWalletId\":\"" + to + "\",\"amountMinor\":" + amount + "}");
        assertEquals(202, r.statusCode());
        return field(r.body(), "transferId");
    }

    private static void waitForState(String id, String expected) throws Exception {
        String state = "";
        for (int i = 0; i < 60; i++) {                       // up to ~18 seconds
            state = field(get(TRANSFER, "/transfers/" + id), "state");
            if (expected.equals(state)) return;
            Thread.sleep(300);
        }
        throw new AssertionError("Transfer " + id + " ended in " + state + ", expected " + expected);
    }

    private static HttpResponse<String> post(String base, String path, String key, String json) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", key)
                .POST(HttpRequest.BodyPublishers.ofString(json)).build();
        return HTTP.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private static String get(String base, String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    private static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*\"?([^\",}]+)").matcher(json);
        if (!m.find()) throw new AssertionError("Field " + name + " not found in " + json);
        return m.group(1);
    }
}