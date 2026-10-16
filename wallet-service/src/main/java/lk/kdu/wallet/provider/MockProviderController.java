package lk.kdu.wallet.provider;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

@RestController
@RequestMapping("/mock-provider")
public class MockProviderController {

    private final ConcurrentHashMap<String, String> charges = new ConcurrentHashMap<>();
    private volatile int failPercent = 0;
    private volatile long delayMs = 0;

    @PostMapping("/charges")
    public ResponseEntity<String> charge(@RequestHeader("Idempotency-Key") String key) throws InterruptedException {
        String chargeId = charges.computeIfAbsent(key, k -> UUID.randomUUID().toString());
        if (delayMs > 0) Thread.sleep(delayMs);
        if (ThreadLocalRandom.current().nextInt(100) < failPercent) {
            return ResponseEntity.status(500).contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":\"provider failure\"}");
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .body("{\"chargeId\":\"" + chargeId + "\"}");
    }

    @PostMapping("/chaos")
    public Map<String, Object> chaos(@RequestParam(required = false) Integer failPercent,
                                     @RequestParam(required = false) Long delayMs) {
        if (failPercent != null) this.failPercent = failPercent;
        if (delayMs != null) this.delayMs = delayMs;
        return Map.of("failPercent", this.failPercent, "delayMs", this.delayMs);
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return Map.of("distinctCharges", charges.size());
    }
}