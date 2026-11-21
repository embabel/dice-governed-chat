package example.dice.governed;

import com.embabel.agent.api.common.Ai;
import com.embabel.common.ai.decision.DecisionService;
import com.embabel.common.ai.model.ServiceSelector;
import com.embabel.dice.audit.AuditCoverageException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    private Workflow.Review reviewFrom(String workspace, String sourceId) {
        return workflow.state(workspace).reviews().stream()
                .filter(review -> review.sourceId().equals(sourceId))
                .findFirst().orElseThrow();
    }
}
