package org.aura.aura.tools.backoffice;

/**
 * Refunds. DESTRUCTIVE in principle — this is the interface through which money would move — and INERT
 * in practice until Day 18 (ADR-045): the only operation is to REQUEST a refund, which lands as
 * {@link Status#PENDING_CONFIRMATION} and moves nothing. There is intentionally no "complete" method on
 * this interface yet; the confirmation gate that would call one does not exist.
 */
public interface RefundService {

    /** Idempotent on {@code idempotencyKey}, like {@link TicketService#createFollowup}. */
    RefundRequest requestRefund(String idempotencyKey, String orderId, long amountCents, String currency,
                                Reason reason);

    enum Reason { DAMAGED, NOT_DELIVERED, WRONG_ITEM, POLICY_RETURN }

    enum Status { PENDING_CONFIRMATION }

    record RefundRequest(String refundId, String orderId, long amountCents, String currency, Reason reason,
                         Status status) {}
}
