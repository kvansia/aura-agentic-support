package org.aura.aura.tools.backoffice;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory ticket system. Ids are a sequence ({@code FT-0001}, ...) so a seeded run is reproducible.
 *
 * <h2>What the idempotency key protects, and what it does not</h2>
 * The key is the {@code tool_use_id}. That protects against DISPATCH-LAYER replays — the same
 * {@code tool_use} block being executed twice (a retried loop iteration, a redelivered request). It
 * does NOT protect against SEMANTIC duplicates: the model asking, in a fresh block with a fresh id, for
 * "the same" warehouse check it already requested. Deciding when two differently-worded requests are
 * the same work item is policy, not plumbing, and it is Day 18's.
 */
@Service
public class FakeTicketService implements TicketService {

    private final Map<String, FollowupTicket> byKey = new ConcurrentHashMap<>();
    private final AtomicInteger sequence = new AtomicInteger();

    @Override
    public FollowupTicket createFollowup(String idempotencyKey, Team team, String orderId, String summary) {
        FollowupTicket existing = byKey.get(idempotencyKey);
        if (existing != null) {
            return new FollowupTicket(existing.ticketId(), existing.team(), existing.orderId(),
                    existing.summary(), true);
        }
        return byKey.computeIfAbsent(idempotencyKey, key -> new FollowupTicket(
                "FT-%04d".formatted(sequence.incrementAndGet()), team, orderId, summary, false));
    }

    /** Every ticket actually created — one per distinct key. For tests and the demo. */
    public List<FollowupTicket> created() {
        return List.copyOf(byKey.values());
    }
}
