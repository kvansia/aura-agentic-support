package org.aura.aura.tools.backoffice;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * The clock the fake back-office systems are seeded from.
 *
 * <p>FIXED, and QUALIFIED. Fixed because the fakes are a deterministic demo fixture — "SF-4412 shipped
 * yesterday" must mean the same date on every run of an eval. Qualified because a bare {@code Clock}
 * bean is the kind of thing the next feature autowires by type without noticing it is frozen; the name
 * says exactly whose clock this is. When the fakes are replaced by real clients, this bean goes with
 * them.
 */
@Configuration(proxyBeanMethods = false)
public class BackOfficeConfig {

    public static final String CLOCK = "backOfficeClock";

    @Bean(CLOCK)
    Clock backOfficeClock(@Value("${aura.tools.back-office-clock:2026-09-23T12:00:00Z}") Instant fixedAt) {
        return Clock.fixed(fixedAt, ZoneOffset.UTC);
    }
}
