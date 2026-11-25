package example.dice.governed;

import com.embabel.agent.api.common.Ai;
import com.embabel.agent.core.DataDictionary;
import com.embabel.common.ai.model.LlmOptions;
import com.embabel.dice.agent.ContextBudget;
import com.embabel.dice.audit.AuditEventListener;
import com.embabel.dice.common.EntityResolver;
import com.embabel.dice.common.PropositionRejected;
import com.embabel.dice.common.PropositionRoutedToReview;
import com.embabel.dice.common.Resolutions;
import com.embabel.dice.common.SourceAnalysisContext;
import com.embabel.dice.pipeline.ChunkPropositionResult;
import com.embabel.dice.pipeline.PropositionPipeline;
import com.embabel.dice.proposition.EntityMention;
import com.embabel.dice.proposition.MentionRole;
import com.embabel.dice.proposition.Proposition;
import com.embabel.dice.proposition.PropositionStatus;
import com.embabel.dice.proposition.extraction.LlmPropositionExtractor;
import com.embabel.dice.provenance.ConnectorRef;
import com.embabel.dice.store.ContextReadScope;
import com.embabel.dice.store.ReadBudget;
import com.embabel.dice.store.ReadCriteria;
import com.embabel.dice.store.ReadQuery;
import com.embabel.dice.store.ScopedReadSpec;
import com.embabel.dice.temporal.TemporalMetadata;
import example.dice.governed.AdmissionPolicy.Admission;
import example.dice.governed.AdmissionPolicy.Disposition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
final class Workflow {

    private static final Logger logger = LoggerFactory.getLogger(Workflow.class);
    private static final String SEED_SOURCE = "initial-refund-policy";
    private static final String SEED_POLICY = "Support leads can approve refunds within 30 days.";
    private static final String TARGET = "governed.review.target";
    private static final String TARGET_REVISION = "governed.review.target-revision";
    private static final String TARGET_CONTENT_REVISION = "governed.review.target-content-revision";
    private static final ReadBudget READ_BUDGET = ReadBudget.ofRows(200);
    private static final ContextBudget CONTEXT_BUDGET = ContextBudget.ofTokens(128);
    private static final DataDictionary SCHEMA =
            DataDictionary.fromClasses("support", RefundPolicy.class, ContactPreference.class, Customer.class);

    // This workflow compares grounded mentions. It has no canonical entity catalog.
    static final EntityResolver MENTIONS_ONLY = (suggestions, schema) ->
            new Resolutions<>(suggestions.getChunkIds(), List.of());

    private final Storage storage;
    private final PropositionPipeline pipeline;
    private final AdmissionPolicy policy;
    private final AuditEventListener auditEvents;

    Workflow(Storage storage, Ai ai) {
        this.storage = storage;
        this.pipeline = PropositionPipeline.withExtractor(
                LlmPropositionExtractor.builder(LlmOptions.withDefaultLlm()).withAi(ai));
        this.policy = new AdmissionPolicy(new DecisionRevision(ai));
        this.auditEvents = new AuditEventListener(storage.audit());
    }

    Turn tell(String workspace, String messageId, String text) {
        var recorded = storage.source(workspace, messageId);
        if (recorded.isPresent()) {
            return replay(recorded.orElseThrow(), text);
        }
        if (messageId.equals(SEED_SOURCE)) {
            throw new IllegalArgumentException("This message ID is reserved.");
        }

        var startingClaims = storage.write(workspace, () -> {
            initialize(workspace);
            return active(workspace);
        });
        var context = SourceAnalysisContext.withContextId(workspace)
                .withEntityResolver(MENTIONS_ONLY)
                .withSchema(SCHEMA)
                .withSourceLocator(new ConnectorRef("governed-chat/" + workspace, messageId));
        var extracted = extract(text, messageId, context);
        var admissions = policy.plan(extracted.getPropositions(), startingClaims);

        return storage.write(workspace, () -> {
            var existingSource = storage.source(workspace, messageId);
            if (existingSource.isPresent()) {
                return replay(existingSource.orElseThrow(), text);
            }
            if (!revisions(active(workspace)).equals(revisions(startingClaims))) {
                throw new RequestConflict("The workspace changed during extraction. Please retry the message.");
            }
            storage.saveSource(workspace, messageId, text);
            admissions.forEach(this::apply);
            return new Turn(reply(workspace, admissions));
        });
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
        return storage.write(workspace, () -> snapshot(workspace, initialize(workspace)));
    }

    State approve(String workspace, String reviewId) {
        return storage.write(workspace, () -> {
            var pending = storage.pending(workspace, reviewId).orElseThrow(ReviewUnavailable::new);
            var proposed = pending.fact();
            if (!proposed.getMetadata().containsKey(TARGET)) {
                throw new RequestConflict("Resolve the comparison before approving this proposal.");
            }
            var target = currentTarget(proposed, active(workspace));
            if (target.isEmpty()) {
                throw new RequestConflict("The claim changed after this review was opened.");
            }
            var prior = target.orElseThrow();
            boolean policy = RefundClaims.describesPolicy(proposed);
            if (policy != prior.getPinned()) {
                throw new RequestConflict("The approval status changed after this review was opened.");
            }

            var before = Instant.now();
            var metadata = new HashMap<>(proposed.getMetadata());
            metadata.remove(TARGET);
            metadata.remove(TARGET_REVISION);
            metadata.remove(TARGET_CONTENT_REVISION);
            var confirmed = proposed.toDraft().withMetadata(metadata).toProposition();
            var sameText = storage.findCanonicalMatch(confirmed);
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
            var next = storage.facts().save(accepted.toProposition());
            storage.deleteReview(workspace, reviewId);
            storage.saveApproval(workspace, reviewId, before, Instant.now(), next.getText());
            return snapshot(workspace, storage.ensureBaseline(workspace));
        });
    }

    History history(String workspace, Instant asOf) {
        var claims = storage.audit().beliefSetAsOf(workspace, asOf).stream()
                .filter(fact -> fact.getStatus() == PropositionStatus.ACTIVE)
                .map(fact -> new Claim(fact.getId(), fact.getText(), fact.getPinned(), List.of()))
                .toList();
        return new History(asOf, claims);
    }

    private Instant initialize(String workspace) {
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
                    metadata.put(TARGET_CONTENT_REVISION, admission.previous().getContentRevised().toString());
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
                .map(review -> {
                    var target = currentTarget(review.fact(), accepted);
                    return new Review(review.id(), review.fact().getText(),
                            review.fact().getGrounding().getFirst(), review.reason(), target.isPresent(),
                            target.map(Workflow::claim).orElse(null));
                }).toList();
        var approvals = storage.approvals(workspace).stream().map(row -> new Approval(row.id(),
                Instant.parse(row.prior()), Instant.parse(row.approvedAt()), row.text())).toList();
        return new State(baseline, Instant.now(), claims(accepted), reviews, approvals);
    }

    private Optional<Proposition> currentTarget(Proposition proposed, List<Proposition> accepted) {
        var targetId = proposed.getMetadata().get(TARGET);
        var targetRevision = proposed.getMetadata().get(TARGET_REVISION);
        var contentRevision = proposed.getMetadata().get(TARGET_CONTENT_REVISION);
        // Reviews written before content revision tracking have only the metadata revision.
        return accepted.stream().filter(fact -> fact.getId().equals(targetId)
                && fact.getMetadataRevised().toString().equals(targetRevision)
                && (contentRevision == null || fact.getContentRevised().toString().equals(contentRevision)))
                .findFirst();
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
        return facts.stream().map(Workflow::claim).toList();
    }

    private static Claim claim(Proposition fact) {
        return new Claim(fact.getId(), fact.getText(), fact.getPinned(), fact.getGrounding());
    }

    private static List<ClaimRevision> revisions(List<Proposition> facts) {
        return facts.stream().map(fact -> new ClaimRevision(
                fact.getId(), fact.getContentRevised(), fact.getMetadataRevised())).toList();
    }

    private record ClaimRevision(String id, Instant content, Instant metadata) {}

    private static String scopedId(String workspace, String source) {
        return UUID.nameUUIDFromBytes((workspace + "\u0000" + source).getBytes(StandardCharsets.UTF_8)).toString();
    }

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
    record State(Instant baseline, Instant observedAt, List<Claim> claims, List<Review> reviews, List<Approval> approvals) {}
    record Claim(String id, String text, boolean pinned, List<String> sources) {}
    record Review(String id, String text, String sourceId, String reason, boolean approvable, Claim target) {}
    record Approval(String id, Instant prior, Instant approvedAt, String text) {}
    record History(Instant asOf, List<Claim> claims) {}
}
