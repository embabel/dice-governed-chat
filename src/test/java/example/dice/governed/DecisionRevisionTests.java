package example.dice.governed;

import com.embabel.agent.api.common.Ai;
import com.embabel.common.ai.decision.DecisionService;
import com.embabel.common.ai.decision.DecisionSpec;
import com.embabel.common.ai.model.ServiceSelector;
import com.embabel.dice.proposition.Proposition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DecisionRevisionTests {

    @Test
    void aDecisionServiceFailureRequiresReview() {
        Ai ai = mock(Ai.class);
        @SuppressWarnings("unchecked")
        ServiceSelector<DecisionService> selector = mock(ServiceSelector.class);
        var service = mock(DecisionService.class);
        when(ai.decisions()).thenReturn(selector);
        when(selector.byRole("dice-revision")).thenReturn(service);
        when(service.ask(anyString(), any(DecisionSpec.class)))
                .thenThrow(new IllegalStateException("Service unavailable"));
        var incoming = Proposition.draft("north", "Phone is our preferred follow-up channel.", 0.9).toProposition();
        var existing = Proposition.draft("north", "Email is our preferred follow-up channel.", 0.9).toProposition();

        var result = new DecisionRevision(ai).classify(incoming, List.of(existing));

        assertThat(result.relation()).isEqualTo(DecisionRevision.Relation.REVIEW);
        assertThat(result.reason()).contains("unavailable");
        verify(selector).byRole("dice-revision");
    }
}
