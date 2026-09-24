package org.aura.aura.tools.backoffice;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory refund ledger with two books: PENDING requests, and COMPLETED money movements.
 *
 * <p>The second book exists only so a test can assert it is empty. Nothing in this class writes to it
 * — there is no code path from a tool call to a completed movement, and that absence is the Day 17
 * safety property (ADR-045: the refund tool is inert until Day 18's human confirmation gate).
 */
@Service
public class FakeRefundService implements RefundService {

    private final Map<String, RefundRequest> pendingByKey = new ConcurrentHashMap<>();
    private final List<RefundRequest> completedMovements = List.of();
    private final AtomicInteger sequence = new AtomicInteger();

    @Override
    public RefundRequest requestRefund(String idempotencyKey, String orderId, long amountCents,
                                       String currency, Reason reason) {
        return pendingByKey.computeIfAbsent(idempotencyKey, key -> new RefundRequest(
                "RF-%04d".formatted(sequence.incrementAndGet()), orderId, amountCents, currency, reason,
                Status.PENDING_CONFIRMATION));
    }

    public List<RefundRequest> pending() {
        return List.copyOf(pendingByKey.values());
    }

    /** Money that actually moved. Always empty on Day 17. */
    public List<RefundRequest> completedMovements() {
        return completedMovements;
    }
}
