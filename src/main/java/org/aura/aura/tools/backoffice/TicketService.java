package org.aura.aura.tools.backoffice;

/** Internal follow-up work items for other ShopFast teams. Not customer handoff — that is escalation. */
public interface TicketService {

    /**
     * Idempotent on {@code idempotencyKey}: a second call with the same key returns the FIRST ticket
     * and creates nothing.
     *
     * @param orderId nullable — a follow-up need not be about one order
     * @param summary model-authored; stored for the receiving team, never echoed back into a prompt
     */
    FollowupTicket createFollowup(String idempotencyKey, Team team, String orderId, String summary);

    enum Team { WAREHOUSE, SHIPPING, PAYMENTS }

    record FollowupTicket(String ticketId, Team team, String orderId, String summary, boolean replayed) {}
}
