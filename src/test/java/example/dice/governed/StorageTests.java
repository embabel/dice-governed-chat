package example.dice.governed;

import com.embabel.agent.api.common.Ai;
import com.embabel.agent.core.DataDictionary;
import com.embabel.common.ai.classification.ClassificationResult;
import com.embabel.common.ai.decision.DecisionSpec;
import com.embabel.common.ai.decision.Questions;
import com.embabel.common.ai.model.LlmOptions;
import com.embabel.dice.audit.AuditCoverageException;
import com.embabel.dice.common.SourceAnalysisContext;
import com.embabel.dice.common.resolver.EscalatingEntityResolver;
import com.embabel.dice.pipeline.PropositionPipeline;
import com.embabel.dice.proposition.Proposition;
import com.embabel.dice.proposition.PropositionStatus;
import com.embabel.dice.proposition.extraction.LlmPropositionExtractor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StorageTests {

    @TempDir
    Path directory;

    @Test
    void storedBeliefsAndPendingReviewsSurviveReopeningTheSameDatabase() {
        Instant cutover;
        String approvedId;
        try (var application = start()) {
            var storage = application.getBean(Storage.class);
            cutover = storage.ensureBaseline("support");
            approvedId = storage.facts().save(fact("Refunds are allowed for 30 days.")).getId();
            storage.saveReview("support", "review-1", fact("Extend refunds to 60 days."), "policy_conflict");
            storage.saveSource("support", "review-1", "Extend refunds to 60 days.");
        }

        try (var application = start()) {
            var storage = application.getBean(Storage.class);
            assertThat(storage.ensureBaseline("support")).isEqualTo(cutover);
            assertThat(storage.facts().findById(approvedId)).isNotNull();
            assertThat(storage.audit().beliefSetAsOf("support", Instant.now()))
                    .singleElement().extracting(claim -> claim.getId()).isEqualTo(approvedId);
            assertThatThrownBy(() -> storage.audit().beliefSetAsOf("support", cutover.minusNanos(1)))
                    .isInstanceOf(AuditCoverageException.class);
            assertThat(storage.pending("support")).singleElement().satisfies(review -> {
                assertThat(review.id()).isEqualTo("review-1");
                assertThat(review.reason()).isEqualTo("policy_conflict");
                assertThat(review.fact().getText()).isEqualTo("Extend refunds to 60 days.");
                assertThat(review.fact().getGrounding()).containsExactly("support-message-1");
            });
            assertThat(storage.pending("another-workspace")).isEmpty();
            assertThat(storage.source("support", "review-1")).contains("Extend refunds to 60 days.");
            assertThat(storage.source("another-workspace", "review-1")).isEmpty();
            assertThatThrownBy(() -> storage.saveSource("support", "review-1", "Other text"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(storage.source("support", "review-1")).contains("Extend refunds to 60 days.");
        }
    }

    @Test
    void canonicalMatchFindsSupersededTextOnlyInTheAttemptedWorkspace() {
        try (var application = start()) {
            var storage = application.getBean(Storage.class);
            storage.ensureBaseline("support");
            var original = fact("Refunds are allowed for 30 days.").withStatus(PropositionStatus.SUPERSEDED);
            var saved = storage.facts().save(original);

            assertThat(storage.findCanonicalMatch(fact(original.getText())))
                    .hasValueSatisfying(match -> {
                        assertThat(match.getId()).isEqualTo(saved.getId());
                        assertThat(match.getStatus()).isEqualTo(PropositionStatus.SUPERSEDED);
                    });
            var otherWorkspace = Proposition.draft("other", original.getText(), 0.9).toProposition();
            assertThat(storage.findCanonicalMatch(otherWorkspace)).isEmpty();
        }
    }

    @Test
    void aFailedWriteRollsBackFactsAuditSourceAndPendingReviewsTogether() {
        try (var application = start()) {
            var storage = application.getBean(Storage.class);
            storage.ensureBaseline("support");
            var fact = fact("Refunds are allowed for 30 days.");
            storage.saveReview("support", "waiting", fact, "policy_conflict");
            assertThatThrownBy(() -> storage.write("support", () -> {
                storage.facts().save(fact);
                storage.saveSource("support", "source", "New text");
                storage.deleteReview("support", "waiting");
                throw new IllegalStateException("Force rollback");
            })).isInstanceOf(IllegalStateException.class);
            assertThat(storage.facts().findById(fact.getId())).isNull();
            assertThat(storage.audit().history("support", fact.getId())).isEmpty();
            assertThat(storage.source("support", "source")).isEmpty();
            assertThat(storage.pending("support")).extracting(Storage.Pending::id).containsExactly("waiting");
        }
    }

    @Test
    void offlineEmbabelExtractsAndAnswersTheConfiguredDecisionRole() {
        try (var application = start()) {
            var ai = application.getBean(Ai.class);
            var extractor = LlmPropositionExtractor.builder(LlmOptions.withDefaultLlm()).withAi(ai);
            var pipeline = PropositionPipeline.withExtractor(extractor);
            var entities = application.getBean(Storage.class).entities("support");
            var context = SourceAnalysisContext.withContextId("support")
                    .withEntityResolver(EscalatingEntityResolver.withoutVector(entities))
                    .withSchema(DataDictionary.fromClasses("support", ContactPreference.class));
            var result = pipeline.processText("Email is our preferred follow-up channel.", "source-1", context);
            assertThat(result.getPropositions()).extracting(Proposition::getText)
                    .containsExactly("Email is our preferred follow-up channel.");

            var question = Questions.named("decision-1")
                    .choice("How does the new proposition relate to the candidate?")
                    .option("contradicts", "Claims conflict")
                    .option("unrelated", "Claims are independent")
                    .build();
            var response = ai.decisions().byRole("dice-revision").ask("""
                    New proposition: Phone is our preferred follow-up channel.
                    Candidate 1: Email is our preferred follow-up channel.
                    """, DecisionSpec.of(List.of(question)));
            assertThat(response.answer(question)).isInstanceOfSatisfying(ClassificationResult.Selected.class,
                    decision -> assertThat(decision.getCategoryId()).isEqualTo("contradicts"));
        }
    }

    record ContactPreference(String name) {}

    private ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(Application.class)
                .web(WebApplicationType.NONE)
                .run("--governed.store.path=" + directory.resolve("support.sqlite"));
    }

    private static Proposition fact(String text) {
        return Proposition.draft("support", text, 0.9)
                .withGrounding(List.of("support-message-1"))
                .toProposition();
    }
}
