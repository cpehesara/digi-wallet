package lk.kdu.wallet.chaos;

import org.springframework.stereotype.Component;

@Component
public class ChaosState {
    public volatile boolean dropResponse = false;
    public volatile boolean duplicatePublish = false;
    public volatile boolean failConsumer = false;
}