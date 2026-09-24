package org.aura.aura.tools;

/**
 * The names and shared constraints of the tools AURA advertises to the resolver model (ADR-045).
 * The executors key on these names; the advertisement itself (descriptions and input schemas) is
 * built on top of them.
 */
public class ToolDefinitions {

    public static final String CREATE_FOLLOWUP_TICKET = "create_followup_ticket";
    public static final String GET_ORDER_STATUS = "get_order_status";
    public static final String INITIATE_REFUND = "initiate_refund";

    /** Mirrors {@link ToolInput#ORDER_ID}; stated in the schema so the model sees the rule it is held to. */
    static final String ORDER_ID_PATTERN = "^SF-\\d{4}$";
}
