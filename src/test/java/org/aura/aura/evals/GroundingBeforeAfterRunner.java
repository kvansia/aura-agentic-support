package org.aura.aura.evals;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.StructuredTextBlock;
import org.aura.aura.ResolverPromptProvider;
import org.aura.aura.resolver.Resolution;
import org.aura.aura.resolver.ResolverService;
import org.aura.aura.resolver.ResolverToolLoop;
import org.aura.aura.retrieval.ContextBlock;
import org.aura.aura.retrieval.RetrievalService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Day 16 BEFORE/AFTER experiment: the same tickets, the same corpus, the same model, run once
 * through the pre-grounding resolver and once through today's, in one session.
 *
 * <h2>Why the before-run is executed rather than remembered</h2>
 * The obvious cheaper design is to read the last archived results file from {@code docs/evals/} and
 * call that the baseline. It would be wrong, because between that run and this one the CORPUS moved:
 * Day 16 rewrote the gift-card section, which is the whole trap slice. A baseline taken against a
 * different knowledge base is not a baseline for a grounding measurement — it would attribute a
 * corpus edit to a prompt change. Both arms run here, back to back, against the same
 * {@code kb_chunks}.
 *
 * <p>Everything the before arm needs is frozen beside it and must stay frozen:
 * {@code resolver_system_prompt_v4_frozen.md} and {@link BeforeResolverOutput}. The request built in
 * {@link #beforeParams} deliberately DUPLICATES {@code ResolverService.paramsFor} rather than calling
 * it. Sharing that method would make the baseline track every future change to the live request
 * shape, and a baseline that moves with the thing it measures measures nothing.
 *
 * <h2>What the before arm can and cannot be scored on — the asymmetry IS the result</h2>
 * The old resolver emits no citations, so citation checks are meaningless against it; and with k=8
 * retrieval always returns something, so the pre-existing empty-context escalation (G1) never fires
 * and the old system NEVER REFUSES. That is not a gap in the experiment, it is the finding: the
 * before arm's refusal rate is structurally zero, so every unanswerable ticket it answers is a
 * hallucination it had no mechanism to avoid.
 *
 * <p>So the before arm is scored on the three things that ARE comparable:
 * <ul>
 *   <li>expected-fact presence (did it state the corpus's value?),</li>
 *   <li>trap-prior behaviour (did it answer from the corpus or from training data?),</li>
 *   <li>answered-when-unanswerable (the hallucination count).</li>
 * </ul>
 * Over-refusal is reported for both and is expected to be exactly 0/N before — which is what makes
 * the after arm's number the honest cost of the change rather than a regression against nothing.
 *
 * <h2>Cost</h2>
 * 12 tickets x 2 arms = 24 live Sonnet calls plus 12 Voyage query embeddings, on top of whatever
 * {@link EvalRunner} costs. Tagged {@code eval} and excluded from {@code mvn test}; run it with
 * {@code mvn test -Pevals}, which needs live ANTHROPIC_API_KEY and VOYAGE_API_KEY.
 */
@Tag("eval")
@ActiveProfiles({"test", "evals"})
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "aura.ingest.enabled=true")
class GroundingBeforeAfterRunner extends org.aura.aura.PostgresBackedContext {

    private static final String FROZEN_PROMPT = "prompts/resolver_system_prompt_v4_frozen.md";

    private final AnthropicClient client;
    private final ResolverToolLoop resolver;
    private final RetrievalService retrieval;
    private final ResolverPromptProvider afterPrompts;

    private final EvalScorer scorer = new EvalScorer();

    @Autowired
    GroundingBeforeAfterRunner(AnthropicClient client, ResolverToolLoop resolver,
                               RetrievalService retrieval, ResolverPromptProvider afterPrompts) {
        this.client = client;
        this.resolver = resolver;
        this.retrieval = retrieval;
        this.afterPrompts = afterPrompts;
    }

    @Test
    void beforeAndAfterOverTheGroundingSlices() {
        List<EvalTicket> tickets = GoldenSetLoader.load().tickets().stream()
                .filter(t -> GroundingClass.of(t.slice()).isPresent())
                .toList();
        assertThat(tickets)
                .as("the grounding slices must exist in the golden set; this runner has nothing to do otherwise")
                .isNotEmpty();

        List<Arm> before = new ArrayList<>();
        List<Arm> after = new ArrayList<>();

        for (EvalTicket ticket : tickets) {
            // ONE retrieval, BOTH arms. Re-retrieving per arm would spend a second billable embedding
            // to obtain a context that differs in the fourth decimal place, and would leave a real (if
            // small) chance that the two arms answered from different document sets — at which point
            // the comparison is between two experiments rather than two prompts.
            ContextBlock context = retrieval.retrieve(ticket.customerMessage());

            after.add(arm(ticket, resolver.resolve(ticket.customerMessage(), context),
                    EvalScorer.CitationRegime.ENFORCED));
            // ABSENT, and this is the single most consequential line in the comparison. The old
            // schema had no citations field, so every before-arm answer has an empty cited list for a
            // reason that has nothing to do with its judgment. Scoring it ENFORCED would mark every
            // one of them — including the ones that state the corpus's value and dodge the trap — as
            // a hallucination, and the after arm would win against a baseline penalised for lacking
            // a feature it never had. A flattering number is not a result.
            before.add(arm(ticket, runBeforeArm(ticket.customerMessage(), context),
                    EvalScorer.CitationRegime.ABSENT));
        }

        String report = render(before, after);
        System.out.println(report);
        Path file = write(before, after);
        System.out.println("\nGrounding before/after written to: " + file.toAbsolutePath());
    }

    // ---- the before arm -------------------------------------------------------------------------

    /**
     * The pre-Day-16 resolve path: frozen prompt, two-field schema, and — critically — NO GATES.
     * Whatever the model returns is what the customer would have seen.
     *
     * <p>It is mapped into a {@link Resolution} with {@code RESOLVED} status and an empty cited list,
     * which is exactly what the old code produced. That is what lets one {@link EvalScorer} grade
     * both arms: the difference between them is then entirely in the outputs, not in two scoring
     * paths that could disagree.
     */
    private Resolution runBeforeArm(String ticket, ContextBlock context) {
        StructuredMessage<BeforeResolverOutput> message = client.messages().create(beforeParams(ticket, context));
        if (message.stopReason().filter(StopReason.END_TURN::equals).isEmpty()) {
            throw new IllegalStateException("before-arm stop_reason="
                    + message.stopReason().map(StopReason::asString).orElse("<absent>"));
        }
        BeforeResolverOutput output = message.content().stream()
                .flatMap(block -> block.text().stream())
                .findFirst()
                .map(StructuredTextBlock::text)
                .orElseThrow(() -> new IllegalStateException("before-arm response carried no text block"));

        return Resolution.resolved(output.reply(), context.sourcesProvided(), List.of(), output.escalate());
    }

    /** A frozen copy of the Day 14 request shape. See the class javadoc on why it is not shared. */
    private StructuredMessageCreateParams<BeforeResolverOutput> beforeParams(String ticket, ContextBlock context) {
        return MessageCreateParams.builder()
                .model(Model.CLAUDE_SONNET_4_5)
                .maxTokens(ResolverService.MAX_TOKENS)
                .temperature(ResolverService.TEMPERATURE)
                // Plain .system(String): no cache_control. The breakpoint is a COST optimisation and
                // omitting it changes billing, not output — and leaving it off keeps this arm from
                // sharing a cached prefix with the after arm, which would be harmless but confusing
                // in the usage logs of a run whose whole purpose is comparing two prompts.
                .system(frozenPrompt())
                .outputConfig(BeforeResolverOutput.class)
                .addUserMessage(context.rendered() + "\n\ncustomer ticket: " + ticket)
                .build();
    }

    private static String frozenPrompt() {
        try (InputStream in = new ClassPathResource(FROZEN_PROMPT).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("the frozen v4 prompt must be on the test classpath", e);
        }
    }

    // ---- scoring + report -----------------------------------------------------------------------

    private Arm arm(EvalTicket ticket, Resolution resolution, EvalScorer.CitationRegime regime) {
        // The classifier is not called: it is untouched by Day 16 and both arms would get the same
        // label, so paying Haiku twice per ticket would buy a column of identical values. A neutral
        // stand-in keeps the scorer's signature honest; only the grounding dimension is read below.
        return new Arm(ticket, resolution,
                scorer.score(ticket, NEUTRAL_CLASSIFICATION, resolution, regime));
    }

    private static final org.aura.aura.classification.ClassificationResult NEUTRAL_CLASSIFICATION =
            new org.aura.aura.classification.ClassificationResult(
                    new org.aura.aura.classification.TicketClassification(
                            org.aura.aura.classification.TicketCategory.OTHER,
                            org.aura.aura.classification.TicketUrgency.MEDIUM,
                            org.aura.aura.classification.TicketIntent.GET_INFORMATION, 0.0),
                    true, org.aura.aura.classification.ReviewReason.DEPENDENCY_UNAVAILABLE);

    private String render(List<Arm> before, List<Arm> after) {
        StringBuilder b = new StringBuilder();
        String rule = "=".repeat(78);
        b.append(rule).append("\nAURA GROUNDING — BEFORE / AFTER\n").append(rule).append("\n");
        b.append(String.format("before: resolver_system_prompt_v4_frozen.md (no grounding contract, no gates)%n"));
        b.append(String.format("after : resolver_system_prompt.md v%d + G3/G4%n", afterPrompts.promptVersion()));
        b.append("timestamp=").append(LocalDateTime.now()).append("\n\n");

        b.append(String.format("%-18s %-26s %-26s%n", "METRIC", "BEFORE", "AFTER"));
        Metrics beforeMetrics = metrics(before);
        Metrics afterMetrics = metrics(after);
        b.append(String.format("%-18s %-26s %-26s%n", "hallucination",
                beforeMetrics.hallucination(), afterMetrics.hallucination()));
        b.append(String.format("%-18s %-26s %-26s%n", "refusal correct",
                beforeMetrics.refusalCorrectness(), afterMetrics.refusalCorrectness()));
        b.append(String.format("%-18s %-26s %-26s%n", "over-refusal",
                beforeMetrics.overRefusal(), afterMetrics.overRefusal()));
        b.append("""

                READ THE BEFORE COLUMN NARROWLY. The old resolver emits no citations, and with k=8
                retrieval always returns something, so its pre-existing empty-context escalation never
                fires -- it has NO mechanism to refuse. Its refusal correctness is therefore 0/N by
                construction, not by judgment, and its over-refusal is 0/N for the same reason. Only
                the hallucination column compares like with like; the other two rows are there to
                show what the before arm structurally could not do.
                """);

        b.append("\nPER TICKET\n");
        for (int i = 0; i < after.size(); i++) {
            Arm b0 = before.get(i);
            Arm a0 = after.get(i);
            b.append(String.format("  [%s] %-13s before=%-26s after=%s%n",
                    a0.ticket().id(), a0.ticket().slice(),
                    b0.score().grounding().outcome(), a0.score().grounding().outcome()));
            if (a0.score().grounding().isFailure()) {
                b.append(String.format("        after : %s%n", a0.score().grounding().reason()));
            }
            if (b0.score().grounding().isFailure()) {
                b.append(String.format("        before: %s%n", b0.score().grounding().reason()));
            }
        }
        b.append(rule).append("\n");
        return b.toString();
    }

    private Metrics metrics(List<Arm> arms) {
        List<Arm> graded = arms.stream().filter(a -> a.score().grounding().isGraded()).toList();
        List<Arm> shouldRefuse = graded.stream().filter(a -> !a.score().grounding().expectedAnswer()).toList();
        List<Arm> shouldAnswer = graded.stream().filter(a -> a.score().grounding().expectedAnswer()).toList();
        return new Metrics(
                ratio(count(graded, TicketScore.GroundingResult.Outcome.HALLUCINATED), graded.size()),
                ratio(count(shouldRefuse, TicketScore.GroundingResult.Outcome.CORRECTLY_REFUSED), shouldRefuse.size()),
                ratio(count(shouldAnswer, TicketScore.GroundingResult.Outcome.OVER_REFUSED), shouldAnswer.size()));
    }

    private static int count(List<Arm> arms, TicketScore.GroundingResult.Outcome outcome) {
        return (int) arms.stream().filter(a -> a.score().grounding().outcome() == outcome).count();
    }

    private static String ratio(int hits, int total) {
        return String.format("%d/%d (%.1f%%)", hits, total, total == 0 ? 0.0 : 100.0 * hits / total);
    }

    private Path write(List<Arm> before, List<Arm> after) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("resolverPromptVersionAfter", afterPrompts.promptVersion());
        root.put("beforePrompt", FROZEN_PROMPT);
        root.put("goldenSetVersion", GoldenSetLoader.load().goldenSetVersion());
        root.put("timestamp", LocalDateTime.now().toString());
        root.put("beforeMetrics", metricsMap(metrics(before)));
        root.put("afterMetrics", metricsMap(metrics(after)));
        root.put("beforeCaveat",
                "The before arm has no citations and no refusal mechanism (k=8 means the empty-context "
                        + "escalation never fires), so only hallucination compares like with like. Its "
                        + "refusal-correctness and over-refusal rows are structural zeros.");

        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < after.size(); i++) rows.add(row(before.get(i), after.get(i)));
        root.put("tickets", rows);

        try {
            Path dir = Path.of("docs", "evals");
            Files.createDirectories(dir);
            Path file = dir.resolve(String.format("grounding-before-after-res%d-%s.json",
                    afterPrompts.promptVersion(),
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss"))));
            Files.writeString(file, JSON.writeValueAsString(root), StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException("failed writing the grounding before/after results", e);
        }
    }

    private Map<String, Object> metricsMap(Metrics m) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("hallucinationRate", m.hallucination());
        out.put("refusalCorrectness", m.refusalCorrectness());
        out.put("overRefusalRate", m.overRefusal());
        return out;
    }

    private Map<String, Object> row(Arm before, Arm after) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", after.ticket().id());
        row.put("slice", after.ticket().slice());
        row.put("beforeOutcome", before.score().grounding().outcome().name());
        row.put("beforeReason", before.score().grounding().reason());
        row.put("beforeAnswer", before.resolution().answer());
        row.put("afterOutcome", after.score().grounding().outcome().name());
        row.put("afterReason", after.score().grounding().reason());
        row.put("afterStatus", after.resolution().status().name());
        row.put("afterEscalationCause", after.resolution().escalationCause().name());
        row.put("afterCitations", EvalScorer.citedBreadcrumbs(after.resolution()));
        // The full text is archived on BOTH arms deliberately. A rate tells you what changed; only the
        // sentences tell you whether the change is the one you wanted, and re-running to recover them
        // costs 24 live calls.
        row.put("afterAnswer", after.resolution().answer());
        row.put("retrieved", EvalScorer.providedBreadcrumbs(after.resolution()));
        return row;
    }

    private static final tools.jackson.databind.ObjectMapper JSON = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build();

    private record Arm(EvalTicket ticket, Resolution resolution, TicketScore score) {}

    private record Metrics(String hallucination, String refusalCorrectness, String overRefusal) {}
}
