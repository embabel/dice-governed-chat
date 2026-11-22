package example.dice.governed;

import com.embabel.agent.api.common.Ai;
import com.embabel.agent.spi.support.springai.SpringAiLlmService;
import com.embabel.common.ai.decision.DecisionService;
import com.embabel.common.ai.model.LlmOptions;
import com.embabel.common.ai.model.ServiceSelector;
import com.embabel.dice.audit.AuditCoverageException;
import com.embabel.dice.proposition.Proposition;
import com.embabel.dice.proposition.PropositionStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

@SpringBootTest
class WorkflowTests {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) throws Exception {
        var database = Files.createTempDirectory("dice-chat-workflow-").resolve("workflow.db");
        properties.add("governed.store.path", () -> database.toString());
    }

    @Autowired
    Workflow workflow;

    @Autowired
    Storage storage;

    @Autowired
    Ai ai;

    @Test
    void aChallengedPolicyChangesOnlyAfterApprovalAndKeepsItsHistory() {
        var before = workflow.state("policy-test");
        assertThat(before.claims()).singleElement().satisfies(claim -> {
            assertThat(claim.text()).contains("30 days");
            assertThat(claim.pinned()).isTrue();
        });

        workflow.tell("policy-test", "chat-correction", "Support leads can approve refunds within 60 days.");
        var disputed = workflow.state("policy-test");
        assertThat(disputed.claims()).isEqualTo(before.claims());
        assertThat(disputed.reviews()).singleElement().satisfies(review -> {
            assertThat(review.text()).contains("60 days");
            assertThat(review.sourceId()).isEqualTo("chat-correction");
            assertThat(review.reason()).containsIgnoringCase("approved refund policy");
        });

        var review = disputed.reviews().getFirst();
        assertThatThrownBy(() -> workflow.approve("other-policy-test", review.id()))
                .isInstanceOf(Workflow.ReviewUnavailable.class);
        var after = workflow.approve("policy-test", review.id());
        assertThat(after.reviews()).isEmpty();
        assertThat(after.claims()).singleElement().satisfies(claim -> {
            assertThat(claim.text()).contains("60 days");
            assertThat(claim.pinned()).isTrue();
            assertThat(claim.sources()).contains("chat-correction");
        });
        assertThat(workflow.history("policy-test", before.observedAt()).claims())
                .extracting(Workflow.Claim::text).containsExactly(before.claims().getFirst().text());
        assertThatThrownBy(() -> workflow.history("policy-test", Instant.EPOCH))
                .isInstanceOf(AuditCoverageException.class);
    }

    @Test
    void approvalCanCommitWhileExtractionWaitsAndAStaleTurnCanBeRetried() throws Exception {
        var workspace = "approval-during-extraction";
        workflow.tell(workspace, "review", "Support leads can approve refunds within 60 days.");
        var pending = reviewFrom(workspace, "review");
        var extracting = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        var delayed = extracting("Email is our preferred follow-up channel.", "[]", () -> {
            extracting.countDown();
            try {
                if (!resume.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Test did not release the model call");
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(failure);
            }
        });

        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var turn = workers.submit(() -> delayed.tell(workspace, "waiting-message",
                    "Email is our preferred follow-up channel."));
            try {
                assertThat(extracting.await(5, TimeUnit.SECONDS)).isTrue();
                var approval = workers.submit(() -> delayed.approve(workspace, pending.id()));
                assertThat(approval.get(3, TimeUnit.SECONDS).claims()).extracting(Workflow.Claim::text)
                        .containsExactly("Support leads can approve refunds within 60 days.");
            } finally {
                resume.countDown();
            }
            assertThatThrownBy(() -> turn.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(Workflow.RequestConflict.class);
        }
        assertThat(storage.source(workspace, "waiting-message")).isEmpty();
        workflow.tell(workspace, "waiting-message", "Email is our preferred follow-up channel.");
        assertThat(workflow.state(workspace).claims()).extracting(Workflow.Claim::text)
                .contains("Email is our preferred follow-up channel.");
    }

    @Test
    void aReviewCannotApproveAgainstAChangedContentRevision() {
        var workspace = "review-content-revision";
        workflow.tell(workspace, "proposal", "Support leads can approve refunds within 60 days.");
        var disputed = workflow.state(workspace);
        var current = storage.facts().findById(disputed.claims().getFirst().id());
        var revised = current.withConfidence(0.95);
        assertThat(revised.getContentRevised()).isAfter(current.getContentRevised());
        assertThat(revised.getMetadataRevised()).isEqualTo(current.getMetadataRevised());
        storage.facts().save(revised);

        assertThatThrownBy(() -> workflow.approve(workspace, disputed.reviews().getFirst().id()))
                .isInstanceOf(Workflow.RequestConflict.class);
        assertThat(workflow.state(workspace).reviews()).singleElement()
                .satisfies(review -> assertThat(review.approvable()).isFalse());
    }

    @Test
    void approvalCanRestoreAnEarlierRuleWithoutLosingItsEarlierEvidence() {
        String workspace = "restored-policy-test";
        var earlier = "Support leads can approve refunds within 45 days.";
        workflow.tell(workspace, "earlier-proposal", earlier);
        var staleReview = workflow.state(workspace).reviews().getFirst();
        workflow.tell(workspace, "first-change", "Support leads can approve refunds within 60 days.");
        var afterChange = workflow.approve(workspace, reviewFrom(workspace, "first-change").id());
        var alternate = afterChange.claims().getFirst();

        workflow.tell(workspace, "restore-original", "Support leads can approve refunds within 30 days.");
        var restored = workflow.approve(workspace, reviewFrom(workspace, "restore-original").id());

        assertThat(restored.claims()).singleElement().satisfies(claim -> {
            assertThat(claim.text()).contains("30 days");
            assertThat(claim.pinned()).isTrue();
            assertThat(claim.sources()).containsExactlyInAnyOrder("initial-refund-policy", "restore-original");
        });
        assertThat(workflow.history(workspace, afterChange.observedAt()).claims())
                .extracting(Workflow.Claim::id).containsExactly(alternate.id());
        assertThatThrownBy(() -> workflow.approve(workspace, staleReview.id()))
                .isInstanceOf(Workflow.RequestConflict.class);
    }

    @Test
    void aDecisionConflictKeepsThePriorPreferenceAndDoesNotReprocessARetry() {
        String workspace = "decision-test";
        var email = "Email is our preferred follow-up channel.";
        var phone = "Phone is our preferred follow-up channel.";
        workflow.tell(workspace, "email-first", email);
        var before = workflow.state(workspace);
        assertThat(before.claims()).extracting(Workflow.Claim::text).contains(email);

        workflow.tell(workspace, "phone-next", phone);
        assertThat(workflow.tell(workspace, "phone-next", phone).reply()).contains("already processed");
        assertThatThrownBy(() -> workflow.tell(workspace, "phone-next", email))
                .isInstanceOf(Workflow.RequestConflict.class);
        var after = workflow.state(workspace);
        assertThat(after.claims()).isEqualTo(before.claims());
        assertThat(after.reviews()).singleElement().satisfies(review -> {
            assertThat(review.text()).isEqualTo(phone);
            assertThat(review.sourceId()).isEqualTo("phone-next");
            assertThat(review.reason()).contains("decision found an incompatible value");
        });

        var approved = workflow.approve(workspace, after.reviews().getFirst().id());
        assertThat(approved.claims()).extracting(Workflow.Claim::text).contains(phone).doesNotContain(email);
    }

    @Test
    void anUnresolvedDecisionRemainsInspectableButCannotAdmitACompetingClaim() {
        var unavailable = spy(ai);
        @SuppressWarnings("unchecked")
        ServiceSelector<DecisionService> decisions = mock(ServiceSelector.class);
        doReturn(decisions).when(unavailable).decisions();
        when(decisions.byRole("dice-revision")).thenThrow(new IllegalStateException("Unavailable"));
        var failingWorkflow = new Workflow(storage, unavailable);
        String workspace = "decision-unavailable-test";

        failingWorkflow.tell(workspace, "email", "Email is our preferred follow-up channel.");
        var before = failingWorkflow.state(workspace);
        failingWorkflow.tell(workspace, "phone", "Phone is our preferred follow-up channel.");
        var pending = failingWorkflow.state(workspace).reviews().getFirst();
        assertThat(pending.reason()).contains("unavailable");
        assertThat(pending.approvable()).isFalse();

        assertThatThrownBy(() -> failingWorkflow.approve(workspace, pending.id()))
                .isInstanceOf(Workflow.RequestConflict.class);
        var after = failingWorkflow.state(workspace);
        assertThat(after.claims()).isEqualTo(before.claims());
        assertThat(after.reviews()).containsExactly(pending);
    }

    @Test
    void approvalStillWorksWhenTheWorkspaceHasMoreArchivedClaimsThanTheReadBudget() {
        String workspace = "archived-budget-test";
        workflow.state(workspace);
        storage.write(workspace, () -> {
            for (int index = 0; index < 200; index++) {
                storage.facts().save(Proposition.draft(workspace, "Archived note " + index, 0.9)
                        .withStatus(PropositionStatus.SUPERSEDED).toProposition());
            }
            return null;
        });

        workflow.tell(workspace, "reported-policy", "Support leads can approve refunds within 60 days.");
        var approved = workflow.approve(workspace, reviewFrom(workspace, "reported-policy").id());

        assertThat(approved.claims()).singleElement().satisfies(claim -> {
            assertThat(claim.text()).contains("60 days");
            assertThat(claim.pinned()).isTrue();
        });
    }

    @Test
    void aMentionlessRefundWindowChallengeCannotEnterAcceptedMemory() {
        String workspace = "mentionless-policy-test";
        String text = "The refund window is two months.";
        var before = workflow.state(workspace);

        extractingWithoutMentions(text).tell(workspace, "refund-window", text);

        var disputed = workflow.state(workspace);
        assertThat(disputed.claims()).isEqualTo(before.claims());
        assertThat(disputed.reviews()).singleElement().satisfies(review -> {
            assertThat(review.text()).isEqualTo(text);
            assertThat(review.reason()).contains("approved refund policy");
            assertThat(review.approvable()).isTrue();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "The customer requested a refund two days ago.",
            "Alice requested a refund on May 3.",
            "Alice requested a refund on June 3.",
            "Alice requested a full refund yesterday.",
            "Alice received a partial refund yesterday.",
            "Alice requested two refunds yesterday.",
            "A refund was approved yesterday.",
            "Alice requested a refund."
    })
    void aRefundServiceEventIsOrdinaryMemoryEvenWhenItMentionsDates(String text) {
        String workspace = "refund-service-test-" + text.hashCode();

        extractingWithoutMentions(text).tell(workspace, "refund-service-event", text);

        var state = workflow.state(workspace);
        assertThat(state.claims()).filteredOn(claim -> !claim.pinned())
                .extracting(Workflow.Claim::text).containsExactly(text);
        assertThat(state.reviews()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Support leads are authorized to approve refunds within 60 days.",
            "Support leads may now approve refunds within 60 days.",
            "Refunds must be approved by support leads within 60 days.",
            "Refunds are approved within 60 days.",
            "Customers are eligible for refunds within 60 days.",
            "Support leads can approve refunds within 60 days for all customers.",
            "The refund window is 60 days, effective on Monday.",
            "The refund window is open for 60 days.",
            "Support leads are authorized to approve refunds for up to 60 days after purchase."
    })
    void knownAuthorityPhrasingIdentifiesAProposedRefundPolicyWithoutTags(String text) {
        String workspace = "refund-authority-test" + text.hashCode();
        var before = workflow.state(workspace);

        extractingWithoutMentions(text).tell(workspace, "policy-authority", text);

        var after = workflow.state(workspace);
        assertThat(after.claims()).isEqualTo(before.claims());
        assertThat(after.reviews()).singleElement().satisfies(review -> {
            assertThat(review.text()).isEqualTo(text);
            assertThat(review.approvable()).isTrue();
        });
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Alice can request a refund until Friday.|true",
            "Alice can request a refund until Friday.|false",
            "Alice's refund window expires Friday.|false",
            "Her refund deadline is Friday.|false",
            "Support leads can approve refunds within 60 days for Alice.|false",
            "Refunds are available until Friday to Alice.|false",
            "The board approved a refund policy extending the window to 60 days.|false",
            "The refund policy covers anyone who requested a refund within 60 days.|false",
            "Anyone who requested a refund within 60 days receives their money back.|false",
            "Every customer is automatically approved a refund.|false",
            "No customer is approved a refund.|false",
            "Customers are always approved refunds.|false",
            "Alice is approved a refund.|false",
            "Customers Are Issued Refunds.|false",
            "Customers Are Denied Refunds.|false",
            "Alice Is Denied A Refund.|false",
            "Approved Customers Are Issued Refunds.|false"
    })
    void claimsWithUnclearRefundScopeCannotReplaceTheWorkspacePolicy(String text, boolean misclassified) {
        String workspace = "individual-refund-test-" + text.hashCode() + "-" + misclassified;
        var before = workflow.state(workspace);
        String mentions = misclassified
                ? "[{\"span\":\"a refund\",\"type\":\"RefundPolicy\",\"role\":\"OBJECT\"}]" : "[]";

        extracting(text, mentions).tell(workspace, "customer-entitlement", text);

        var after = workflow.state(workspace);
        assertThat(after.claims()).isEqualTo(before.claims());
        assertThat(after.reviews()).singleElement().satisfies(review -> {
            assertThat(review.text()).isEqualTo(text);
            assertThat(review.approvable()).isFalse();
            assertThatThrownBy(() -> workflow.approve(workspace, review.id()))
                    .isInstanceOf(Workflow.RequestConflict.class);
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aMissingComparisonIdentityOnEitherSideDoesNotBypassDecision(boolean missingOnIncoming) {
        String workspace = "comparison-identity-test-" + missingOnIncoming;
        var email = "Email is our preferred follow-up channel.";
        var phone = "Phone is our preferred follow-up channel.";
        var first = missingOnIncoming ? workflow : extractingWithoutMentions(email);
        var second = missingOnIncoming ? extractingWithoutMentions(phone) : workflow;
        first.tell(workspace, "first-choice", email);

        second.tell(workspace, "second-choice", phone);

        var disputed = workflow.state(workspace);
        assertThat(disputed.claims()).filteredOn(claim -> !claim.pinned())
                .extracting(Workflow.Claim::text).containsExactly(email);
        assertThat(disputed.reviews()).singleElement().satisfies(review -> {
            assertThat(review.text()).isEqualTo(phone);
            assertThat(review.reason()).contains("decision found an incompatible value");
        });
    }

    private Workflow extractingWithoutMentions(String text) {
        return extracting(text, "[]");
    }

    private Workflow extracting(String text, String mentions) {
        return extracting(text, mentions, () -> {});
    }

    private Workflow extracting(String text, String mentions, Runnable beforeReply) {
        String response = """
                {"propositions":[{"text":"%s","mentions":%s,"confidence":0.96,"decay":0,
                "importance":0.7,"reasoning":"Stated in the message."}]}
                """.formatted(text, mentions);
        var model = new SpringAiLlmService("untagged-extractor", "test", ignored -> {
            beforeReply.run();
            return new ChatResponse(List.of(new Generation(new AssistantMessage(response))));
        });
        var fixture = spy(ai);
        doReturn(ai.withLlmService(model)).when(fixture).withLlm(any(LlmOptions.class));
        return new Workflow(storage, fixture);
    }

    private Workflow.Review reviewFrom(String workspace, String sourceId) {
        return workflow.state(workspace).reviews().stream()
                .filter(review -> review.sourceId().equals(sourceId))
                .findFirst().orElseThrow();
    }
}
