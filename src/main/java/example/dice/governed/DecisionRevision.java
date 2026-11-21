package example.dice.governed;

import com.embabel.agent.api.common.Ai;
import com.embabel.common.ai.classification.ClassificationResult;
import com.embabel.common.ai.decision.DecisionSpec;
import com.embabel.common.ai.decision.Question;
import com.embabel.common.ai.decision.Questions;
import com.embabel.dice.proposition.Proposition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;

/** Classifies already-extracted claims against admitted workspace facts. */
final class DecisionRevision {

    private static final Logger logger = LoggerFactory.getLogger(DecisionRevision.class);
    private static final double MIN_CONFIDENCE = 0.7;

    private final Ai ai;

    DecisionRevision(Ai ai) {
        this.ai = ai;
    }

    Outcome classify(Proposition incoming, List<Proposition> candidates) {
        if (candidates.isEmpty()) {
            return Outcome.UNRELATED;
        }
        var input = new StringBuilder("New proposition: ").append(incoming.getText());
        var questions = new ArrayList<Question<ClassificationResult>>(candidates.size());
        for (int index = 0; index < candidates.size(); index++) {
            int number = index + 1;
            input.append("\nCandidate ").append(number).append(": ").append(candidates.get(index).getText());
            questions.add(Questions.named("relation-" + number)
                    .choice("How does the new proposition relate to candidate " + number + "?")
                    .option("duplicate", "States the same fact")
                    .option("reinforces", "Adds support to the same fact")
                    .option("contradicts", "Asserts an incompatible value for the same fact")
                    .option("unrelated", "Describes a different fact")
                    .option("uncertain", "There is insufficient evidence to choose another category")
                    .build());
        }

        var started = System.nanoTime();
        try {
            var response = ai.decisions().byRole("dice-revision")
                    .ask(input.toString(), DecisionSpec.of(List.<Question<?>>copyOf(questions)));
            if (response.getRequestFailure() != null) {
                return Outcome.review("The revision decision did not return a usable result.");
            }

            var related = Outcome.UNRELATED;
            for (int index = 0; index < questions.size(); index++) {
                var answer = response.answer(questions.get(index));
                if (!(answer instanceof ClassificationResult.Selected selected)
                        || selected.getConfidence() != null && selected.getConfidence() < MIN_CONFIDENCE) {
                    return Outcome.review("The revision decision was uncertain.");
                }
                var relation = switch (selected.getCategoryId()) {
                    case "duplicate" -> Relation.DUPLICATE;
                    case "reinforces" -> Relation.REINFORCES;
                    case "contradicts" -> Relation.CONTRADICTS;
                    case "unrelated" -> Relation.UNRELATED;
                    default -> Relation.REVIEW;
                };
                if (relation == Relation.REVIEW) {
                    return Outcome.review("The revision decision was uncertain.");
                }
                if (relation != Relation.UNRELATED) {
                    if (related.relation() != Relation.UNRELATED) {
                        return Outcome.review("The revision decision matched more than one accepted claim.");
                    }
                    related = new Outcome(relation, candidates.get(index), null);
                }
            }
            return related;
        } catch (CancellationException cancellation) {
            throw cancellation;
        } catch (RuntimeException unavailable) {
            logger.warn("Decision role dice-revision failed ({})", unavailable.getClass().getSimpleName());
            return Outcome.review("The revision decision service is unavailable.");
        } finally {
            logger.debug("Revision decision for {} candidates took {} ms", candidates.size(),
                    (System.nanoTime() - started) / 1_000_000.0);
        }
    }

    enum Relation { UNRELATED, DUPLICATE, REINFORCES, CONTRADICTS, REVIEW }

    record Outcome(Relation relation, Proposition candidate, String reason) {
        private static final Outcome UNRELATED = new Outcome(Relation.UNRELATED, null, null);

        static Outcome review(String reason) {
            return new Outcome(Relation.REVIEW, null, reason);
        }
    }
}
