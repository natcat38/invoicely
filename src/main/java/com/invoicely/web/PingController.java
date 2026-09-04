package com.invoicely.web;

import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * An endpoint that needs no authentication and touches no database, so it can
 * answer "is the application up and serving HTTP?" on its own.
 *
 * <p>Actuator's {@code /actuator/health} already reports health, but it also
 * checks the datasource — useful, and a different question. This one stays
 * trivially cheap and will stay permitted-for-all when Task 4 locks the rest of
 * the API down, which makes it the right thing for a load balancer to poll.
 */
@RestController
class PingController {

    @GetMapping("/ping")
    Pong ping() {
        return new Pong("ok", Instant.now());
    }

    record Pong(String status, Instant time) {
    }
}
