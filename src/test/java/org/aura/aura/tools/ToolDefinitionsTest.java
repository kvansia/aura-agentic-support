package org.aura.aura.tools;

import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolUnion;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The advertisement: what the model is told, in bytes that must not move between requests. */
class ToolDefinitionsTest {

    @Test
    void theToolsAreSortedByName_whateverOrderTheyWereDeclaredIn() {
        // Tools sit at the very start of the prompt-cache prefix; an order that depended on
        // declaration order would move bytes the first time someone added a tool "at the end".
        List<ToolDefinitions.ToolSpec> shuffled = new ArrayList<>(ToolDefinitions.standard());
        Collections.reverse(shuffled);

        assertThat(new ToolDefinitions(shuffled).toolUnions())
                .extracting(union -> union.asTool().name())
                .containsExactly("create_followup_ticket", "get_order_status", "initiate_refund");
        assertThat(new ToolDefinitions(shuffled).canonicalForm())
                .as("the cache-key form is order-independent too")
                .isEqualTo(new ToolDefinitions().canonicalForm());
    }

    @Test
    void everySchemaIsClosed_andDeclaresTheBriefsRequiredFields() {
        Map<String, List<String>> required = new HashMap<>();
        for (ToolUnion union : new ToolDefinitions().toolUnions()) {
            Tool tool = union.asTool();
            assertThat(tool.inputSchema()._additionalProperties().get("additionalProperties"))
                    .as(tool.name() + " must be additionalProperties=false")
                    .hasToString("false");
            assertThat(tool.description()).as(tool.name()).isPresent();
            required.put(tool.name(), tool.inputSchema().required().orElseThrow());
        }

        assertThat(required).containsEntry("get_order_status", List.of("order_id"));
        assertThat(required).containsEntry("create_followup_ticket", List.of("team", "summary"));
        // Explicit units AND currency (ADR-047): all four, no defaults the model could lean on.
        assertThat(required).containsEntry("initiate_refund",
                List.of("order_id", "amount_cents", "currency", "reason"));
    }

    @Test
    void theRequestFormIsStableAcrossInstances() {
        // Two independently built advertisements must serialize identically — the property the ADR-020
        // prefix cache and the response-cache key both depend on.
        assertThat(new ToolDefinitions().toolUnions()).isEqualTo(new ToolDefinitions().toolUnions());
        assertThat(new ToolDefinitions().canonicalForm())
                .contains("\"additionalProperties\":false")
                .contains("pending_confirmation");
    }
}
