package example.dice.governed;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransportContractTests {
    @Test
    void theDeskCanOnlyUseItsOwnWorkspaceAndCannotApprove() {
        var access = new DemoAccess(new DemoAccessProperties("desk-demo", "reviewer-demo", "beta-demo"));

        access.require("acme", "Bearer desk-demo", false);
        access.require("acme", "Bearer reviewer-demo", true);
        access.require("beta", "Bearer beta-demo", false);

        assertThatThrownBy(() -> access.require("acme", "Bearer desk-demo", true))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> access.require("beta", "Bearer reviewer-demo", false))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void theEventStreamCarriesOneAssistantMessageAndTheUpdatedWorkspace() {
        var mapper = JsonMapper.builder().build();
        var events = new AgUi(mapper).success(
                new AgUi.Run("chat-1", "run-1", List.of()),
                new SupportQuestions.Answer("Recorded.\nReady for review.", List.of()), Map.of("claims", List.of()));
        var frames = events.strip().split("\n\n");
        var payloads = java.util.Arrays.stream(frames)
                .map(frame -> {
                    assertThat(frame).startsWith("data: ");
                    return mapper.readTree(frame.substring(6));
                })
                .toList();

        assertThat(payloads.stream().map(node -> node.get("type").asString()))
                .containsExactly("RUN_STARTED", "TEXT_MESSAGE_START", "TEXT_MESSAGE_CONTENT",
                        "TEXT_MESSAGE_END", "STATE_SNAPSHOT", "RUN_FINISHED");
        assertThat(payloads.get(2).get("delta").asString()).isEqualTo("Recorded.\nReady for review.");
        assertThat(payloads.get(1).get("messageId")).isEqualTo(payloads.get(3).get("messageId"));
        assertThat(payloads.get(4).get("snapshot").get("claims").isArray()).isTrue();
    }
}
