package lk.kdu.wallet.idempotency;

import lk.kdu.wallet.common.DomainErrors;
import org.springframework.dao.DuplicateKeyException;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

public final class ConflictRetry {
    private ConflictRetry() {}

    public static <T> T run(int maxAttempts, Supplier<T> action) {
        for (int attempt = 1; ; attempt++) {
            try {
                return action.get();
            } catch (DuplicateKeyException | DomainErrors.OptimisticConflictException e) {
                if (attempt >= maxAttempts) {
                    throw new DomainErrors.TooMuchContentionException();
                }
                try {
                    Thread.sleep(ThreadLocalRandom.current().nextLong(5, 25L * attempt));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new DomainErrors.TooMuchContentionException();
                }
            }
        }
    }
}