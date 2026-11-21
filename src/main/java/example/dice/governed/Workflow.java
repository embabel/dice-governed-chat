package example.dice.governed;

import com.embabel.agent.api.common.Ai;
import com.embabel.agent.core.DataDictionary;
import com.embabel.common.ai.model.LlmOptions;
import com.embabel.dice.agent.ContextBudget;
import com.embabel.dice.audit.AuditEventListener;
import com.embabel.dice.common.PropositionRejected;
import com.embabel.dice.common.PropositionRoutedToReview;
import com.embabel.dice.common.SourceAnalysisContext;
import com.embabel.dice.common.resolver.EscalatingEntityResolver;
import com.embabel.dice.pipeline.ChunkPropositionResult;
import com.embabel.dice.pipeline.PropositionPipeline;
import com.embabel.dice.proposition.EntityMention;
import com.embabel.dice.proposition.MentionRole;
import com.embabel.dice.proposition.Proposition;
import com.embabel.dice.proposition.PropositionStatus;
import com.embabel.dice.proposition.extraction.LlmPropositionExtractor;
import com.embabel.dice.proposition.gate.ConfidenceGate;
import com.embabel.dice.proposition.gate.GateContext;
import com.embabel.dice.proposition.gate.GateDecision;
import com.embabel.dice.provenance.ConnectorRef;
import com.embabel.dice.store.ContextReadScope;
import com.embabel.dice.store.ReadBudget;
import com.embabel.dice.store.ReadCriteria;
import com.embabel.dice.store.ReadQuery;
import com.embabel.dice.store.ScopedReadSpec;
import com.embabel.dice.temporal.TemporalMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

@Service
final class Workflow {

    private static final Logger logger = LoggerFactory.getLogger(Workflow.class);
    private static final String SEED_SOURCE = "initial-refund-policy";
    private static final String SEED_POLICY = "Support leads can approve refunds within 30 days.";
    private static final String TARGET = "governed.review.target";
    private static final String TARGET_REVISION = "governed.review.target-revision";
    private static final String POLICY_CHANGE = "A change to the approved refund policy needs review.";
    private static final ReadBudget READ_BUDGET = ReadBudget.ofRows(200);
    private static final ContextBudget CONTEXT_BUDGET = ContextBudget.ofTokens(128);
    private static final DataDictionary SCHEMA =
            DataDictionary.fromClasses("support", RefundPolicy.class, ContactPreference.class, Customer.class);

    private final Storage storage;
    private final PropositionPipeline pipeline;
    private final DecisionRevision decisions;
    private final AuditEventListener auditEvents;
    private final ConfidenceGate confidence = new ConfidenceGate(0.6);
    private final List<Object> workspaces = IntStream.range(0, 32).mapToObj(unused -> new Object()).toList();

    Workflow(Storage storage, Ai ai) {
        this.storage = storage;
        this.pipeline = PropositionPipeline.withExtractor(
                LlmPropositionExtractor.builder(LlmOptions.withDefaultLlm()).withAi(ai));
        this.decisions = new DecisionRevision(ai);
        this.auditEvents = new AuditEventListener(storage.audit());
    }

    Turn tell(String workspace, String messageId, String text) {
        synchronized (lock(workspace)) {
            var recorded = storage.source(workspace, messageId);
            if (recorded.isPresent()) {
                return replay(recorded.orElseThrow(), text);
            }
            if (messageId.equals(SEED_SOURCE)) {
                throw new IllegalArgumentException("This message ID is reserved.");
            }

            initialize(workspace);
            var startingClaims = active(workspace);
            var context = SourceAnalysisContext.withContextId(workspace)
                    .withEntityResolver(EscalatingEntityResolver.withoutVector(storage.entities(workspace)))
                    .withSchema(SCHEMA)
                    .withSourceLocator(new ConnectorRef("governed-chat/" + workspace, messageId));
            var extracted = extract(text, messageId, context);
            var admissions = plan(extracted.getPropositions(), startingClaims);

            return storage.write(workspace, () -> {
                var existingSource = storage.source(workspace, messageId);
                if (existingSource.isPresent()) {
                    return replay(existingSource.orElseThrow(), text);
                }
                if (!claims(active(workspace)).equals(claims(startingClaims))) {
                    throw new RequestConflict("The workspace changed during extraction. Please retry the message.");
                }
                storage.saveSource(workspace, messageId, text);
                admissions.forEach(this::apply);
                return new Turn(reply(workspace, admissions));
            });
        }
    }

    private ChunkPropositionResult extract(String text, String messageId, SourceAnalysisContext context) {
        var started = System.nanoTime();
        try {
            var result = pipeline.processText(text, messageId, context);
            if (result instanceof ChunkPropositionResult.Failed failed) {
                throw new IllegalStateException("Could not extract claims from this message.", failed.getCause());
            }
            return result;
        } finally {
            logger.debug("Extraction and grounding took {} ms", (System.nanoTime() - started) / 1_000_000.0);
        }
    }

    private Turn replay(String recorded, String submitted) {
        if (!recorded.equals(submitted)) {
            throw new RequestConflict("This message ID has already been used for different text.");
        }
        return new Turn("This message was already processed.");
    }

    State state(String workspace) {
        synchronized (lock(workspace)) {
            return snapshot(workspace, initialize(workspace));
        }
    }

    State approve(String workspace, String reviewId) {
        synchronized (lock(workspace)) {
            return storage.write(workspace, () -> {
                var pending = storage.pending(workspace).stream()
                        .filter(review -> review.id().equals(reviewId))
                        .findFirst().orElseThrow(ReviewUnavailable::new);
                var proposed = pending.fact();
                if (!proposed.getMetadata().containsKey(TARGET)) {
                    throw new RequestConflict("Resolve the comparison before approving this proposal.");
                }
                var target = currentTarget(proposed, active(workspace));
                if (target.isEmpty()) {
                    throw new RequestConflict("The claim changed after this review was opened.");
                }
                var prior = target.orElseThrow();
                boolean policy = describesPolicy(proposed);
                if (policy != prior.getPinned()) {
                    throw new RequestConflict("The approval status changed after this review was opened.");
                }

                var metadata = new HashMap<>(proposed.getMetadata());
                metadata.remove(TARGET);
                metadata.remove(TARGET_REVISION);
                var confirmed = proposed.toDraft().withMetadata(metadata).toProposition();
                var sameText = read(workspace, ReadCriteria.empty()).stream()
                        .filter(fact -> fact.getText().equals(confirmed.getText()))
                        .findFirst();
                // The store reuses IDs for exact text. Restore an earlier claim through its own ID.
                var accepted = sameText.map(fact -> fact.absorbEvidence(confirmed)).orElse(confirmed)
                        .withStatus(PropositionStatus.ACTIVE).withPinned(policy).toDraft();
                if (sameText.filter(fact -> fact.getId().equals(prior.getId())).isEmpty()) {
                    storage.facts().save(prior.withPinned(false).withStatus(PropositionStatus.SUPERSEDED));
                }
                if (sameText.isEmpty()) {
                    accepted = accepted.withTemporal(new TemporalMetadata(
                            null, null, null, null, List.of(prior.getId())));
                }
                storage.facts().save(accepted.toProposition());
                storage.deleteReview(workspace, reviewId);
                return snapshot(workspace, storage.ensureBaseline(workspace));
            });
        }
    }

    private List<Admission> plan(List<Proposition> incoming, List<Proposition> existing) {
        var accepted = new LinkedHashMap<String, Proposition>();
        existing.forEach(fact -> accepted.put(fact.getId(), fact));
        var admissions = new ArrayList<Admission>();
        for (var incomingFact : incoming) {
            var admission = plan(incomingFact, List.copyOf(accepted.values()));
            admissions.add(admission);
            if (admission.disposition() == Disposition.ACCEPT || admission.disposition() == Disposition.REINFORCE) {
                accepted.put(admission.fact().getId(), admission.fact());
            }
        }
        return List.copyOf(admissions);
    }

    History history(String workspace, Instant asOf) {
        synchronized (lock(workspace)) {
            var claims = storage.audit().beliefSetAsOf(workspace, asOf).stream()
                    .filter(fact -> fact.getStatus() == PropositionStatus.ACTIVE)
                    .map(fact -> new Claim(fact.getId(), fact.getText(), fact.getPinned(), List.of()))
                    .toList();
            return new History(asOf, claims);
        }
    }

    private Instant initialize(String workspace) {
        return storage.write(workspace, () -> {
            var baseline = storage.ensureBaseline(workspace);
            if (storage.source(workspace, SEED_SOURCE).isEmpty()) {
                var policy = Proposition.draft(workspace, SEED_POLICY, 1.0)
                        .withId(scopedId(workspace, SEED_SOURCE))
                        .withMentions(List.of(new EntityMention("30 days", "RefundPolicy", null, MentionRole.OBJECT)))
                        .withGrounding(List.of(SEED_SOURCE)).withPinned(true).toProposition();
                storage.saveSource(workspace, SEED_SOURCE, SEED_POLICY);
                storage.facts().save(policy);
            }
            return baseline;
        });
    }

    private Admission plan(Proposition fact, List<Proposition> active) {
        var verdict = confidence.evaluate(fact, new GateContext()).getDecision();
        if (verdict instanceof GateDecision.Reject rejected) {
            return new Admission(fact, null, Disposition.REJECT, rejected.getReason());
        }
        if (describesPolicy(fact)) {
            var approved = active.stream().filter(Proposition::getPinned).findFirst().orElseThrow();
            if (approved.getText().equalsIgnoreCase(fact.getText())) {
                return new Admission(fact, null, Disposition.UNCHANGED, null);
            }
            return new Admission(fact, approved, Disposition.REVIEW, POLICY_CHANGE);
        }
        var candidates = active.stream()
                .filter(existing -> !existing.getPinned())
                .filter(existing -> comparable(fact, existing))
                .toList();
        if (candidates.size() > 12) {
            return new Admission(fact, null, Disposition.REVIEW,
                    "There are too many related claims to decide this change automatically.");
        }
        var decision = decisions.classify(fact, candidates);
        return switch (decision.relation()) {
            case UNRELATED -> new Admission(fact, null, Disposition.ACCEPT, null);
            case DUPLICATE, REINFORCES -> new Admission(
                    decision.candidate().absorbEvidence(fact), null, Disposition.REINFORCE, null);
            case CONTRADICTS -> new Admission(fact, decision.candidate(), Disposition.REVIEW,
                    "The revision decision found an incompatible value in accepted memory.");
            case REVIEW -> new Admission(fact, null, Disposition.REVIEW, decision.reason());
        };
    }

    private void apply(Admission admission) {
        var fact = admission.fact();
        var workspace = fact.getContextIdValue();
        switch (admission.disposition()) {
            case ACCEPT, REINFORCE -> storage.facts().save(fact);
            case REVIEW -> {
                var metadata = new HashMap<>(fact.getMetadata());
                if (admission.previous() != null) {
                    metadata.put(TARGET, admission.previous().getId());
                    metadata.put(TARGET_REVISION, admission.previous().getMetadataRevised().toString());
                }
                var proposed = fact.toDraft().withMetadata(metadata).toProposition();
                storage.saveReview(workspace, proposed.getId(), proposed, admission.reason());
                auditEvents.onEvent(new PropositionRoutedToReview(proposed, admission.reason()));
            }
            case REJECT -> auditEvents.onEvent(new PropositionRejected(fact, admission.reason()));
            case UNCHANGED -> {
                // Repeating the current policy does not add authoritative evidence.
            }
        }
    }

    private boolean comparable(Proposition incoming, Proposition candidate) {
        return incoming.getMentions().stream().anyMatch(mention ->
                candidate.getMentions().stream().anyMatch(existing -> sameType(mention, existing)
                        && (mention.getType().equalsIgnoreCase("ContactPreference")
                        || mention.getSpan().equalsIgnoreCase(existing.getSpan()))));
    }

    private boolean sameType(EntityMention one, EntityMention other) {
        return one.getType().equalsIgnoreCase(other.getType());
    }

    private boolean describesPolicy(Proposition fact) {
        if (fact.getMentions().stream().anyMatch(mention -> mention.getType().equalsIgnoreCase("RefundPolicy"))) {
            return true;
        }
        var text = fact.getText().toLowerCase(Locale.ROOT);
        return text.contains("refund") && (text.contains("approv") || text.contains("policy")
                || text.contains("deadline") || text.contains("eligib") || text.contains(" days"));
    }

    private String reply(String workspace, List<Admission> admissions) {
        var notes = new ArrayList<String>();
        if (admissions.stream().anyMatch(admission -> admission.disposition() == Disposition.REVIEW)) {
            notes.add("I sent a proposed change for review.");
        }
        if (admissions.stream().anyMatch(admission -> admission.disposition() == Disposition.REJECT)) {
            notes.add("I could not accept a claim with insufficient confidence.");
        }
        if (admissions.stream().anyMatch(admission ->
                admission.disposition() == Disposition.ACCEPT || admission.disposition() == Disposition.REINFORCE)) {
            notes.add("I updated the workspace memory.");
        }
        var selected = CONTEXT_BUDGET.select(active(workspace)).getSelected();
        selected.forEach(fact -> notes.add(fact.getText()));
        return String.join(" ", notes);
    }

    private State snapshot(String workspace, Instant baseline) {
        var accepted = active(workspace);
        var reviews = storage.pending(workspace).stream()
                .map(review -> new Review(review.id(), review.fact().getText(),
                        review.fact().getGrounding().getFirst(), review.reason(),
                        currentTarget(review.fact(), accepted).isPresent()))
                .toList();
        return new State(baseline, Instant.now(), claims(accepted), reviews);
    }

    private Optional<Proposition> currentTarget(Proposition proposed, List<Proposition> accepted) {
        var targetId = proposed.getMetadata().get(TARGET);
        var targetRevision = proposed.getMetadata().get(TARGET_REVISION);
        return accepted.stream().filter(fact -> fact.getId().equals(targetId)
                && fact.getMetadataRevised().toString().equals(targetRevision)).findFirst();
    }

    private List<Proposition> active(String workspace) {
        return read(workspace, ReadCriteria.empty().withStatus(PropositionStatus.ACTIVE))
                .stream().sorted(Comparator.comparing(Proposition::getId)).toList();
    }

    private List<Proposition> read(String workspace, ReadCriteria criteria) {
        var view = ScopedReadSpec.of(ContextReadScope.ofValues(workspace));
        return storage.reads().query(view, ReadQuery.of(criteria), READ_BUDGET);
    }

    private List<Claim> claims(List<Proposition> facts) {
        return facts.stream().map(fact -> new Claim(fact.getId(), fact.getText(), fact.getPinned(),
                fact.getGrounding())).toList();
    }

    private Object lock(String workspace) {
        return workspaces.get(Math.floorMod(workspace.hashCode(), workspaces.size()));
    }

    private static String scopedId(String workspace, String source) {
        return UUID.nameUUIDFromBytes((workspace + "\u0000" + source).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private enum Disposition { ACCEPT, REINFORCE, REVIEW, REJECT, UNCHANGED }

    private record Admission(Proposition fact, Proposition previous, Disposition disposition, String reason) {}

    static final class RequestConflict extends RuntimeException {
        RequestConflict(String message) {
            super(message);
        }
    }

    static final class ReviewUnavailable extends RuntimeException {
        ReviewUnavailable() {
            super("Review not found.");
        }
    }

    record RefundPolicy(String name) {}
    record ContactPreference(String name) {}
    record Customer(String name) {}

    record Turn(String reply) {}
    record State(Instant baseline, Instant observedAt, List<Claim> claims, List<Review> reviews) {}
    record Claim(String id, String text, boolean pinned, List<String> sources) {}
    record Review(String id, String text, String sourceId, String reason, boolean approvable) {}
    record History(Instant asOf, List<Claim> claims) {}
}
