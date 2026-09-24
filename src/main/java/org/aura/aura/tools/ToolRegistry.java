package org.aura.aura.tools;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Name → executor dispatch, with a boot-time consistency check (ADR-047).
 *
 * <h2>Why the check runs at boot</h2>
 * The advertisement ({@link ToolDefinitions}) and the implementations ({@link ToolExecutor} beans) are
 * two lists maintained in two places, and they drift in two different directions with two different
 * failure modes:
 * <ul>
 *   <li><b>advertised but unregistered</b> — the model is told it can call a tool nobody implements.
 *       Every call answers {@code unknown_tool}, and the model's behaviour degrades in a way no test
 *       that stubs the model would notice.</li>
 *   <li><b>registered but unadvertised</b> — dead code that is one advertisement edit away from being
 *       live, reviewed by nobody as a model-reachable action.</li>
 * </ul>
 * Both are cheap to detect and expensive to discover in production, so startup FAILS, naming the
 * direction and the tool.
 */
@Slf4j
@Component
public class ToolRegistry {

    private final ToolDefinitions definitions;
    private final Map<String, ToolExecutor> byName = new HashMap<>();
    private final Set<String> duplicates = new TreeSet<>();

    public ToolRegistry(List<ToolExecutor> executors, ToolDefinitions definitions) {
        this.definitions = definitions;
        for (ToolExecutor executor : executors) {
            if (byName.putIfAbsent(executor.toolName(), executor) != null) {
                duplicates.add(executor.toolName());
            }
        }
    }

    @PostConstruct
    void verifyConsistency() {
        Set<String> advertised = definitions.advertisedNames();
        Set<String> registered = new TreeSet<>(byName.keySet());

        List<String> problems = new ArrayList<>();
        if (!duplicates.isEmpty()) {
            problems.add("registered more than once: " + duplicates);
        }
        Set<String> unregistered = new TreeSet<>(advertised);
        unregistered.removeAll(registered);
        if (!unregistered.isEmpty()) {
            problems.add("advertised but unregistered: " + unregistered);
        }
        Set<String> unadvertised = new TreeSet<>(registered);
        unadvertised.removeAll(advertised);
        if (!unadvertised.isEmpty()) {
            problems.add("registered but unadvertised: " + unadvertised);
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("tool registry drift — " + String.join("; ", problems));
        }
        log.info("tool registry consistent — {} tool(s): {}", registered.size(), registered);
    }

    /**
     * Runs one {@code tool_use} block. NEVER throws.
     *
     * <p>An unknown name cannot happen after the boot check — the model can only name what it was
     * shown — except that the model is not bound by what it was shown. It can emit any string, so this
     * answers {@code unknown_tool} rather than trusting the check (defence in depth). The catch-all is
     * the same idea one layer down: executors promise never to throw, and this is where that promise
     * is enforced rather than assumed, because a throw here would escape into the resolver loop.
     */
    public ToolResultPayload dispatch(String name, String toolUseId, JsonNode input) {
        ToolExecutor executor = byName.get(name);
        if (executor == null) {
            log.warn("model requested an unknown tool '{}' (tool_use_id={}); answering unknown_tool", name, toolUseId);
            return ToolResultPayload.error("unknown_tool");
        }
        try {
            return executor.execute(toolUseId, input);
        } catch (RuntimeException broken) {
            log.error("tool executor '{}' broke its never-throw contract; answering is_error", name, broken);
            return ToolResultPayload.error("execution_failed");
        }
    }
}
