package org.aura.aura.tools.backoffice;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory order system, seeded DETERMINISTICALLY from an injected clock.
 *
 * <p>Dates are relative to that clock rather than hard-coded ("shipped yesterday", not "shipped
 * 2026-09-22") so the seed reads the same on any day the demo runs, and a test that pins the clock
 * pins every date. Nothing here reads the system clock.
 *
 * <p>Seeds: SF-4412 shipped yesterday via FastShip · SF-1001 processing · SF-2002 delivered ·
 * SF-3003 cancelled. Every other id is not found.
 */
@Service
public class FakeOrderService implements OrderService {

    private final Map<String, Order> orders;

    public FakeOrderService(@Qualifier(BackOfficeConfig.CLOCK) Clock clock) {
        LocalDate today = LocalDate.now(clock);
        this.orders = Map.of(
                "SF-4412", new Order("SF-4412", Status.SHIPPED, "FastShip", today.minusDays(1), null,
                        List.of(new LineItem("SKU-HDPH-01", 1))),
                "SF-1001", new Order("SF-1001", Status.PROCESSING, null, null, null,
                        List.of(new LineItem("SKU-MUG-12", 2))),
                "SF-2002", new Order("SF-2002", Status.DELIVERED, "FastShip", today.minusDays(6),
                        today.minusDays(3), List.of(new LineItem("SKU-LAMP-07", 1))),
                "SF-3003", new Order("SF-3003", Status.CANCELLED, null, null, null,
                        List.of(new LineItem("SKU-DESK-02", 1))));
    }

    @Override
    public Optional<Order> findOrder(String orderId) {
        return Optional.ofNullable(orders.get(orderId));
    }
}
