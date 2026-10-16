package lk.kdu.wallet.messaging;

import lk.kdu.wallet.common.DomainErrors;
import lk.kdu.wallet.common.Kv;
import lk.kdu.wallet.idempotency.ConflictRetry;
import lk.kdu.wallet.idempotency.IdempotentExecutor;
import lk.kdu.wallet.idempotency.RequestHasher;
import lk.kdu.wallet.ledger.LedgerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

@Component
public class CommandConsumer {
    private static final Logger log = LoggerFactory.getLogger(CommandConsumer.class);

    private final IdempotentExecutor executor;
    private final LedgerService ledger;
    private final OutboxWriter outbox;

    public CommandConsumer(IdempotentExecutor executor, LedgerService ledger, OutboxWriter outbox) {
        this.executor = executor;
        this.ledger = ledger;
        this.outbox = outbox;
    }

    @RabbitListener(queues = RabbitConfig.COMMAND_QUEUE)
    public void onCommand(String payload) {
        Map<String, String> c = Kv.parse(payload);
        UUID transferId = UUID.fromString(c.get("transferId"));
        String step = c.get("step");
        UUID walletId = UUID.fromString(c.get("walletId"));
        long amount = Long.parseLong(c.get("amountMinor"));

        String type = "DEBIT".equals(step) ? LedgerService.DEBITED : LedgerService.CREDITED; // CREDIT and REFUND both credit
        long stepNo = switch (step) { case "DEBIT" -> 1; case "CREDIT" -> 2; default -> 3; };
        String opKey = transferId + ":" + step;
        String hash = RequestHasher.sha256(step + "|" + walletId + "|" + amount);

        ConflictRetry.run(12, () -> executor.execute("COMMAND", opKey, hash, () -> {
            String result = "SUCCEEDED";
            String reason = "";
            try {
                LedgerService.BalanceView b = ledger.balance(walletId);   // WalletNotFoundException if missing
                if (LedgerService.DEBITED.equals(type) && b.balanceMinor() < amount) {
                    throw new DomainErrors.InsufficientFundsException();
                }
                ledger.append(walletId, type, amount, "transfer-" + transferId);
            } catch (DomainErrors.InsufficientFundsException | DomainErrors.WalletNotFoundException e) {
                result = "FAILED";
                reason = Kv.clean(e.getMessage());
            }
            outbox.write(transferId, stepNo, "reply." + step.toLowerCase(),
                    Kv.of("transferId", transferId.toString(), "step", step, "result", result, "reason", reason));
            log.info("Command {} for transfer {} -> {}", step, transferId, result);
            return new IdempotentExecutor.Stored(200, result, false);
        }));
    }
}