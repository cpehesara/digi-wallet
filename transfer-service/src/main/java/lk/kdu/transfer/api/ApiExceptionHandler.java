package lk.kdu.transfer.api;

import lk.kdu.transfer.common.DomainErrors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(DomainErrors.MissingIdempotencyKeyException.class)
    public ResponseEntity<Map<String, String>> missingKey(DomainErrors.MissingIdempotencyKeyException e) {
        return body(HttpStatus.BAD_REQUEST, "MISSING_IDEMPOTENCY_KEY", e.getMessage());
    }

    @ExceptionHandler(DomainErrors.KeyReuseException.class)
    public ResponseEntity<Map<String, String>> keyReuse(DomainErrors.KeyReuseException e) {
        return body(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSED", e.getMessage());
    }

    @ExceptionHandler(DomainErrors.WalletNotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(DomainErrors.WalletNotFoundException e) {
        return body(HttpStatus.NOT_FOUND, "WALLET_NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler(DomainErrors.InsufficientFundsException.class)
    public ResponseEntity<Map<String, String>> insufficient(DomainErrors.InsufficientFundsException e) {
        return body(HttpStatus.CONFLICT, "INSUFFICIENT_FUNDS", e.getMessage());
    }

    @ExceptionHandler(DomainErrors.TooMuchContentionException.class)
    public ResponseEntity<Map<String, String>> contention(DomainErrors.TooMuchContentionException e) {
        return body(HttpStatus.SERVICE_UNAVAILABLE, "TOO_MUCH_CONTENTION", e.getMessage());
    }

    private static ResponseEntity<Map<String, String>> body(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("error", code, "message", message));
    }
}