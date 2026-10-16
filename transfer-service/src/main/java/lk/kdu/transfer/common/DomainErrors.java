package lk.kdu.transfer.common;

import java.util.UUID;

public final class DomainErrors {
    private DomainErrors() {}

    public static class WalletNotFoundException extends RuntimeException {
        public WalletNotFoundException(UUID id) { super("Wallet not found: " + id); }
    }

    public static class InsufficientFundsException extends RuntimeException {
        public InsufficientFundsException() { super("Insufficient funds"); }
    }

    public static class OptimisticConflictException extends RuntimeException {
        public OptimisticConflictException() { super("Concurrent update detected"); }
    }

    public static class TooMuchContentionException extends RuntimeException {
        public TooMuchContentionException() { super("Too many concurrent updates, please retry"); }
    }

    public static class MissingIdempotencyKeyException extends RuntimeException {
        public MissingIdempotencyKeyException() {
            super("Header 'Idempotency-Key' is required (1-128 characters)");
        }
    }

    public static class KeyReuseException extends RuntimeException {
        public KeyReuseException() {
            super("Idempotency-Key was already used with a different request payload");
        }
    }
}