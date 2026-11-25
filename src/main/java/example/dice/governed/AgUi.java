package example.dice.governed;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Encodes the finite stream for a governed chat turn using AG-UI events. */
final class AgUi {
    private final JsonMapper json;

    AgUi(JsonMapper json) {
        this.json = json;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Run(String threadId, String runId, List<Message> messages, ForwardedProps forwardedProps) {

        Run(String threadId, String runId, List<Message> messages) {
            this(threadId, runId, messages, null);
        }

        boolean isQuestion() {
            var intent = forwardedProps == null ? null : forwardedProps.intent();
            if (intent == null || "record".equals(intent)) {
                return false;
            }
            if ("ask".equals(intent)) {
                return true;
            }
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose ask or record for the chat intent.");
        }

        List<Message> conversation() {
            var turns = messages.subList(Math.max(0, messages.size() - 13), messages.size());
            if (turns.stream().anyMatch(turn -> turn == null
                    || !("user".equals(turn.role()) || "assistant".equals(turn.role()))
                    || blank(turn.content()) || turn.content().length() > 8_000)) {
                throw badRun();
            }
            return List.copyOf(turns);
        }
        Message userMessage() {
            if (blank(threadId) || blank(runId) || messages == null || messages.isEmpty()) {
                throw badRun();
            }
            Message message = messages.getLast();
            if (message == null || !"user".equals(message.role()) || blank(message.id())
                    || message.id().length() > 128 || blank(message.content()) || message.content().length() > 8_000) {
                throw badRun();
            }
            return message;
        }

        private static boolean blank(String value) {
            return value == null || value.isBlank();
        }

        private static ResponseStatusException badRun() {
            return new ResponseStatusException(HttpStatus.BAD_REQUEST, "A run needs one final user message");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ForwardedProps(String intent) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Message(String id, String role, String content) {
    }

    String success(Run run, String reply, Object state) {
        String messageId = UUID.randomUUID().toString();
        return event(Map.of("type", "RUN_STARTED", "threadId", run.threadId(), "runId", run.runId()))
                + event(Map.of("type", "TEXT_MESSAGE_START", "messageId", messageId, "role", "assistant"))
                + event(Map.of("type", "TEXT_MESSAGE_CONTENT", "messageId", messageId, "delta", reply))
                + event(Map.of("type", "TEXT_MESSAGE_END", "messageId", messageId))
                + event(Map.of("type", "STATE_SNAPSHOT", "snapshot", state))
                + event(Map.of("type", "RUN_FINISHED", "threadId", run.threadId(), "runId", run.runId()));
    }

    private String event(Map<String, ?> payload) {
        return "data: " + json.writeValueAsString(payload) + "\n\n";
    }
}
