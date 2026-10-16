package lk.kdu.wallet.api;

import lk.kdu.wallet.chaos.ChaosState;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/admin/chaos")
public class ChaosController {

    private final ChaosState chaos;

    public ChaosController(ChaosState chaos) { this.chaos = chaos; }

    @GetMapping
    public Map<String, Boolean> state() {
        return Map.of("dropResponse", chaos.dropResponse,
                "duplicatePublish", chaos.duplicatePublish,
                "failConsumer", chaos.failConsumer);
    }

    @PostMapping
    public Map<String, Boolean> set(@RequestParam(required = false) Boolean dropResponse,
                                    @RequestParam(required = false) Boolean duplicatePublish,
                                    @RequestParam(required = false) Boolean failConsumer) {
        if (dropResponse != null) chaos.dropResponse = dropResponse;
        if (duplicatePublish != null) chaos.duplicatePublish = duplicatePublish;
        if (failConsumer != null) chaos.failConsumer = failConsumer;
        return state();
    }
}