package org.aura.aura.tools.backoffice;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * ShopFast's order system of record, as the tools see it. Today the only implementation is
 * {@link FakeOrderService}; the interface is the seam a real OMS client replaces it at.
 */
public interface OrderService {

    /** @return the order, or empty when no such order exists — absence is an answer, not a fault */
    Optional<Order> findOrder(String orderId);

    enum Status { PROCESSING, SHIPPED, DELIVERED, CANCELLED }

    record LineItem(String sku, int quantity) {}

    /**
     * An order as the system of record holds it. Deliberately carries NO customer free text (no notes,
     * no gift message, no address lines): the tool layer copies fields out of this into the model's
     * next turn, and a field that does not exist cannot be smuggled through.
     */
    record Order(String orderId, Status status, String carrier, LocalDate shippedOn,
                 LocalDate deliveredOn, List<LineItem> items) {
        public Order {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }
}
