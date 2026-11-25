package example.dice.governed;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@Service
final class SupportQuestions {

    private final ObjectProvider<SupportQuestionAgent> agent;

    SupportQuestions(ObjectProvider<SupportQuestionAgent> agent) {
        this.agent = agent;
    }

    String answer(Workflow.State snapshot, List<AgUi.Message> conversation) {
        if (snapshot.claims().isEmpty()) {
            return "I don’t have any support policy in this workspace yet. Would you like to record a source to start?";
        }
        var live = agent.getIfAvailable();
        if (live != null) {
            try {
                var answer = live.answer(snapshot.claims(), conversation);
                if (answer == null || answer.claimIds() == null
                        || answer.claimIds().stream().anyMatch(id -> snapshot.claims().stream().noneMatch(c -> c.id().equals(id)))) {
                    throw new IllegalStateException("The answer referenced a claim outside the workspace.");
                }
                if (answer.claimIds().isEmpty()) {
                    return redirect(snapshot);
                }
                if (answer.text() == null || answer.text().isBlank()) {
                    throw new IllegalStateException("The answer did not contain text.");
                }
                return answer.text();
            } catch (RuntimeException error) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "The support assistant could not complete that request. Try again.");
            }
        }
        return guided(snapshot, conversation);
    }

    private static String guided(Workflow.State snapshot, List<AgUi.Message> conversation) {
        var question = conversation.getLast().content().toLowerCase(Locale.ROOT).strip();
        if (question.matches(".*\\b(what|which)\\b.*\\b(knows|on file|in memory|have recorded)\\b.*")) {
            return snapshot.claims().stream().map(Workflow.Claim::text).limit(5).collect(Collectors.joining("\n"));
        }
        var topic = topic(question);
        if (topic.isEmpty() && question.matches("(why|how so|what about that|what does that mean|which source)[?.!]*")) {
            for (int i = conversation.size() - 2; topic.isEmpty() && i >= 0; i--) {
                if ("user".equals(conversation.get(i).role())) {
                    topic = topic(conversation.get(i).content().toLowerCase(Locale.ROOT));
                }
            }
        }
        var selectedTopic = topic;
        var claims = snapshot.claims().stream()
                .filter(claim -> !selectedTopic.isEmpty() && topic(claim.text().toLowerCase(Locale.ROOT)).equals(selectedTopic))
                .toList();
        return claims.isEmpty() ? redirect(snapshot)
                : claims.stream().map(Workflow.Claim::text).collect(Collectors.joining("\n"));
    }

    private static String redirect(Workflow.State snapshot) {
        var hasPolicy = snapshot.claims().stream().anyMatch(Workflow.Claim::pinned);
        var direction = hasPolicy ? "what refund policy is in effect" : "what this workspace currently knows";
        return "I can’t answer that from this support record. Would you like to know " + direction + "?";
    }

    private static String topic(String question) {
        if (question.matches(".*\\b(refund\\w*|return\\w*|policy|refund window)\\b.*")) return "refund";
        if (question.matches(".*\\b(contact|channel|follow[- ]?up)\\b.*")
                || question.matches(".*\\b(prefer\\w*|support)\\b.*\\b(email|phone)\\b.*")) return "contact";
        return "";
    }
}
