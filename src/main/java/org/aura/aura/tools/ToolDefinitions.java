package org.aura.aura.tools;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolUnion;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * The tools AURA advertises to the resolver model — the SINGLE SOURCE OF TRUTH for what the model is
 * told it can ask for (ADR-045).
 *
 * <h2>What a tool is here, and what it is not</h2>
 * The model may REQUEST an action by emitting a {@code tool_use} block; only our Spring layer
 * EXECUTES it, through {@link ToolRegistry}. Nothing in this class runs anything. Retrieval is
 * deliberately NOT a tool (ADR-046): it stays server-orchestrated, happens before the model is called,
 * and is what the cache key hashes. Tools are for actions and for point data about the world, and
 * {@code citations[]} stays knowledge-base-only — a tool result is system-of-record, never a citable
 * excerpt.
 *
 * <h2>Why the list is sorted, and why every map is ordered</h2>
 * Tools render at the very START of the prompt-cache prefix (tools → system → messages), ahead of the
 * ADR-020 breakpoint on the system block. So their serialized bytes must be identical on every
 * request, or every request pays a cache WRITE and never earns a read. Two things could perturb them:
 * the order of the list, and the iteration order of the schema maps. The list is sorted by name at
 * construction (so a future tool added "at the end" of {@link #standard()} lands in a deterministic
 * slot rather than wherever it was typed), and every schema map is a {@link LinkedHashMap} — never
 * {@code Map.of}, whose iteration order is unspecified and would shuffle property order across JVMs.
 *
 * <h2>Schemas are closed</h2>
 * {@code additionalProperties: false} at every object level. The schema is prompt (the model reads it)
 * AND contract (the executors re-validate against the same rules, because the model is an untrusted
 * in-process client — see {@link ToolInput}). An open schema would invite fields the executors then
 * have to decide how to ignore.
 */
@Component
public class ToolDefinitions {

    public static final String CREATE_FOLLOWUP_TICKET = "create_followup_ticket";
    public static final String GET_ORDER_STATUS = "get_order_status";
    public static final String INITIATE_REFUND = "initiate_refund";

    /** Mirrors {@link ToolInput#ORDER_ID}; stated in the schema so the model sees the rule it is held to. */
    static final String ORDER_ID_PATTERN = "^SF-\\d{4}$";

    // Canonical-form serializer for the cache key. Jackson 2 (the SDK's own line), not Boot's Jackson 3:
    // the spec maps below are plain collections, and the canonical form only has to be stable, not pretty.
    private static final ObjectMapper CANONICAL = new ObjectMapper();

    /**
     * One tool, as data. The SDK {@link Tool} is derived from this rather than stored, so the canonical
     * form the cache key hashes and the request the model receives are built from the same maps.
     */
    public record ToolSpec(String name, String description, Map<String, Object> inputSchema) {}

    private final List<ToolSpec> specs;

    public ToolDefinitions() {
        this(standard());
    }

    /** For tests that need a deliberately drifted advertisement (see the registry boot check). */
    ToolDefinitions(List<ToolSpec> specs) {
        // Sorted by NAME — deterministic order for prefix-cache stability (see class doc).
        this.specs = specs.stream().sorted(Comparator.comparing(ToolSpec::name)).toList();
    }

    /** The advertised tools, sorted by name, in the SDK's request type. */
    public List<ToolUnion> toolUnions() {
        return specs.stream().map(ToolDefinitions::toSdkTool).map(ToolUnion::ofTool).toList();
    }

    public List<ToolSpec> specs() {
        return specs;
    }

    public Set<String> advertisedNames() {
        return specs.stream().map(ToolSpec::name).collect(Collectors.toCollection(TreeSet::new));
    }

    /**
     * A stable serialization of every advertised tool, for the response cache key.
     *
     * <p>The tools are answer-affecting even on a ticket that never calls one: a changed description
     * changes WHETHER the model reaches for a tool, and so changes what a tool-free answer says. They
     * sit in the same static prefix as the system prompt, so they are keyed alongside it — otherwise a
     * tool edit would keep serving answers produced under the old advertisement for a full TTL.
     */
    public String canonicalForm() {
        try {
            return CANONICAL.writeValueAsString(specs);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("tool definitions are plain maps; serialization cannot fail", e);
        }
    }

    // ------------------------------------------------------------------------------------ the three

    static List<ToolSpec> standard() {
        return List.of(createFollowupTicket(), getOrderStatus(), initiateRefund());
    }

    private static ToolSpec createFollowupTicket() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("order_id", orderIdProperty());
        props.put("team", enumProperty("warehouse", "shipping", "payments"));
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("type", "string");
        summary.put("maxLength", 200);
        props.put("summary", summary);
        return new ToolSpec(CREATE_FOLLOWUP_TICKET,
                "Create a follow-up work item for another ShopFast team (warehouse check, damaged-goods "
                        + "pickup) while this conversation continues. Do NOT use to hand this conversation "
                        + "to a human — that is the resolver's escalation verdict, not a tool. Returns the "
                        + "created ticket id.",
                objectSchema(props, List.of("team", "summary")));
    }

    private static ToolSpec getOrderStatus() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("order_id", orderIdProperty());
        return new ToolSpec(GET_ORDER_STATUS,
                "Fetch the current status of one ShopFast order by id. Use when the customer asks about a "
                        + "specific order's state, shipping, delivery, or contents. Do NOT use for policy "
                        + "questions (returns, warranty) — those come from the knowledge base. Read-only.",
                objectSchema(props, List.of("order_id")));
    }

    private static ToolSpec initiateRefund() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("order_id", orderIdProperty());
        Map<String, Object> amount = new LinkedHashMap<>();
        amount.put("type", "integer");
        amount.put("minimum", 1);
        props.put("amount_cents", amount);
        // Explicit units AND currency (ADR-047): "amount": 49.99 is three bugs waiting — float money,
        // an implied currency, and an implied unit. Integer cents in a closed currency enum is none.
        props.put("currency", enumProperty("CAD"));
        props.put("reason", enumProperty("damaged", "not_delivered", "wrong_item", "policy_return"));
        return new ToolSpec(INITIATE_REFUND,
                "Initiate a refund for a ShopFast order. Destructive — moves money. Currently ALWAYS "
                        + "returns pending_confirmation (human confirmation protocol ships Day 18). Use only "
                        + "when the shown knowledge-base policy authorizes a refund for this situation and "
                        + "the customer asked for one.",
                objectSchema(props, List.of("order_id", "amount_cents", "currency", "reason")));
    }

    private static Map<String, Object> orderIdProperty() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "string");
        p.put("pattern", ORDER_ID_PATTERN);
        return p;
    }

    private static Map<String, Object> enumProperty(String... values) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "string");
        p.put("enum", List.of(values));
        return p;
    }

    private static Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }

    @SuppressWarnings("unchecked")
    private static Tool toSdkTool(ToolSpec spec) {
        Map<String, Object> schema = spec.inputSchema();
        Tool.InputSchema.Properties.Builder properties = Tool.InputSchema.Properties.builder();
        ((Map<String, Object>) schema.get("properties"))
                .forEach((name, property) -> properties.putAdditionalProperty(name, JsonValue.from(property)));
        return Tool.builder()
                .name(spec.name())
                .description(spec.description())
                .inputSchema(Tool.InputSchema.builder()
                        .properties(properties.build())
                        .required((List<String>) schema.get("required"))
                        .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                        .build())
                .build();
    }
}
