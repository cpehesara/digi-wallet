package lk.kdu.wallet;

import lk.kdu.wallet.idempotency.RequestHasher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class RequestHasherTest {

    @Test
    void sameInputGivesSameHash() {
        assertEquals(RequestHasher.sha256("POST /wallets|Alice"), RequestHasher.sha256("POST /wallets|Alice"));
    }

    @Test
    void differentInputGivesDifferentHash() {
        assertNotEquals(RequestHasher.sha256("topup|1000"), RequestHasher.sha256("topup|1001"));
    }

    @Test
    void hashIs64HexCharacters() {
        assertEquals(64, RequestHasher.sha256("anything").length());
    }
}