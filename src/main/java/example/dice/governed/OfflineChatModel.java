package example.dice.governed;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Recorded decision and extraction behavior for the runnable offline story. */
final class OfflineChatModel implements ChatModel {

    private static final JsonMapper JSON = new JsonMapper();
    private static final Pattern EXTRACTION_TEXT = Pattern.compile("<text>(.*?)</text>", Pattern.DOTALL);
    private static final Pattern POLICY = Pattern.compile(
            "Support leads can approve refunds within (30|45|60|90) days\\.", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONTACT = Pattern.compile(
            "(Email|Phone) is our preferred follow-up channel\\.", Pattern.CASE_INSENSITIVE);
    private static final Pattern CANDIDATE = Pattern.compile("(?m)^Candidate (\\d+): (.+)$");

    @Override
    public ChatResponse call(Prompt prompt) {
        var input = prompt.getUserMessage().getText().strip();
        var extracted = EXTRACTION_TEXT.matcher(input);
        String reply;
        if (input.startsWith("{\"input\":\"New proposition:")) {
            reply = decide(input);
        } else if (extracted.find()) {
            reply = extract(extracted.group(1).strip());
        } else {
            reply = "Offline mode uses the sample conversation. Choose a sample message to test memory and review.";
        }
        return new ChatResponse(List.of(new Generation(new AssistantMessage(reply))));
    }

    private static String extract(String text) {
        var policy = POLICY.matcher(text);
        var contact = CONTACT.matcher(text);
        if (policy.matches()) {
            return proposition(text, policy.group(1) + " days", "RefundPolicy", 0.96);
        }
        if (contact.matches()) {
            return proposition(text, contact.group(1), "ContactPreference", 0.94);
        }
        if ("I heard we might allow refunds within 90 days.".equalsIgnoreCase(text)) {
            return proposition(text, "90 days", "RefundPolicy", 0.28);
        }
        return "{\"propositions\":[]}";
    }

    private static String decide(String envelope) {
        try {
            var input = JSON.readTree(envelope).path("input").asText();
            var incoming = input.lines().findFirst().orElseThrow().substring("New proposition: ".length());
            var candidates = CANDIDATE.matcher(input);
            var answers = candidates.results().map(candidate -> Map.of(
                    "question", "q" + candidate.group(1), "verdict", "SELECTED",
                    "categoryId", relation(incoming, candidate.group(2)))).toList();
            return JSON.writeValueAsString(Map.of("answers", answers));
        } catch (JsonProcessingException malformed) {
            return "{\"answers\":[]}";
        }
    }

    private static String relation(String incoming, String existing) {
        if (incoming.equalsIgnoreCase(existing)) {
            return "duplicate";
        }
        if (CONTACT.matcher(incoming).matches() && CONTACT.matcher(existing).matches()) {
            return "contradicts";
        }
        return "unrelated";
    }

    private static String proposition(String text, String span, String type, double confidence) {
        try {
            return JSON.writeValueAsString(Map.of("propositions", List.of(Map.of(
                    "text", text, "confidence", confidence, "importance", 0.7, "decay", 0,
                    "mentions", List.of(Map.of("span", span, "type", type, "role", "OBJECT")),
                    "reasoning", "The sample message states the claim."))));
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Could not encode offline sample reply");
        }
    }
}
