package example.dice.governed;

import com.embabel.dice.proposition.EntityMention;
import com.embabel.dice.proposition.Proposition;
import com.embabel.dice.proposition.gate.ConfidenceGate;
import com.embabel.dice.proposition.gate.GateContext;
import com.embabel.dice.proposition.gate.GateDecision;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Plans admissions against one scoped snapshot. Only the workflow commits the result. */
final class AdmissionPolicy {

    private static final String POLICY_CHANGE = "A change to the approved refund policy needs review.";

    private final DecisionRevision decisions;
    private final ConfidenceGate confidence = new ConfidenceGate(0.6);

    AdmissionPolicy(DecisionRevision decisions) {
        this.decisions = decisions;
    }

    List<Admission> plan(List<Proposition> incoming, List<Proposition> existing) {
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

    private Admission plan(Proposition fact, List<Proposition> active) {
        var verdict = confidence.evaluate(fact, new GateContext()).getDecision();
        if (verdict instanceof GateDecision.Reject rejected) {
            return new Admission(fact, null, Disposition.REJECT, rejected.getReason());
        }
        if (RefundClaims.describesPolicy(fact)) {
            var approved = active.stream().filter(Proposition::getPinned).findFirst().orElseThrow();
            if (approved.getText().equalsIgnoreCase(fact.getText())) {
                return new Admission(fact, null, Disposition.UNCHANGED, null);
            }
            return new Admission(fact, approved, Disposition.REVIEW, POLICY_CHANGE);
        }
        if (RefundClaims.refundScopeIsUnclear(fact)) {
            return new Admission(fact, null, Disposition.REVIEW,
                    "Clarify whether this refund claim concerns one customer or the workspace policy.");
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

    private boolean comparable(Proposition incoming, Proposition candidate) {
        var incomingIdentity = incoming.getMentions().stream().filter(this::comparisonMention).toList();
        var candidateIdentity = candidate.getMentions().stream().filter(this::comparisonMention).toList();
        // A missed tag is not evidence that the facts are unrelated. Let Decision assess the text.
        if (incomingIdentity.isEmpty() || candidateIdentity.isEmpty()) {
            return true;
        }
        return incomingIdentity.stream().anyMatch(mention ->
                candidateIdentity.stream().anyMatch(existing -> mention.getType().equalsIgnoreCase(existing.getType())
                        && (mention.getType().equalsIgnoreCase("ContactPreference")
                        || mention.getSpan().equalsIgnoreCase(existing.getSpan()))));
    }

    private boolean comparisonMention(EntityMention mention) {
        return !mention.getSpan().isBlank() && (mention.getType().equalsIgnoreCase("ContactPreference")
                || mention.getType().equalsIgnoreCase("Customer"));
    }

    enum Disposition { ACCEPT, REINFORCE, REVIEW, REJECT, UNCHANGED }

    record Admission(Proposition fact, Proposition previous, Disposition disposition, String reason) {}
}
