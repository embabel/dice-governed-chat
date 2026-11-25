package example.dice.governed;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

class ProgressionTests {
    private static final String DISPUTE = "Support leads can approve refunds within 60 days.";
    private static final String DESK = "desk-demo";
    private static final String REVIEWER = "reviewer-demo";
    private static final String BETA = "beta-demo";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path folder;

    @Test
    void disputedPolicySurvivesRestartAndOnlyAReviewerCanChangeTheBeliefSet() throws Exception {
        String reviewId;
        String beforeApproval;

        try (var app = start()) {
            JsonNode initial = app.json("GET", "acme/state", DESK, null, 200);
            assertThat(claims(initial, true)).anyMatch(text -> text.contains("30 days"));

            var reply = app.say("acme", DESK, "dispute-1", DISPUTE);
            assertThat(reply.body()).contains("TEXT_MESSAGE_CONTENT", "STATE_SNAPSHOT", "RUN_FINISHED");
            assertThat(reply.headers().firstValue("Content-Type")).hasValueSatisfying(
                    contentType -> assertThat(contentType).startsWith("text/event-stream"));
            assertThat(app.say("acme", DESK, "dispute-1", DISPUTE).body()).contains("already processed");
            app.say("acme", DESK, "dispute-1", "Different content with the same identity", 409);

            JsonNode disputed = app.json("GET", "acme/state", DESK, null, 200);
            assertThat(claims(disputed, true)).anyMatch(text -> text.contains("30 days"));
            assertThat(disputed.path("reviews").size()).isOne();
            reviewId = disputed.path("reviews").get(0).path("id").asString();
            assertThat(disputed.path("reviews").get(0).path("target").path("text").asString()).contains("30 days");
            beforeApproval = disputed.path("observedAt").asString();
            assertThat(app.json("GET", "acme/sources/dispute-1", DESK, null, 200).path("text").asString())
                    .isEqualTo(DISPUTE);
            app.json("POST", "acme/reviews/" + reviewId + "/approve", DESK, null, 403);
            app.json("GET", "beta/state", REVIEWER, null, 404);
            app.json("GET", "beta/sources/dispute-1", BETA, null, 404);
        }

        try (var app = start()) {
            JsonNode resumed = app.json("GET", "acme/state", REVIEWER, null, 200);
            assertThat(resumed.path("reviews").get(0).path("id").asString()).isEqualTo(reviewId);
            JsonNode approved = app.json("POST", "acme/reviews/" + reviewId + "/approve", REVIEWER, null, 200);
            assertThat(approved.path("reviews").size()).isZero();
            assertThat(approved.path("approvals").get(0).path("id").asString()).isEqualTo(reviewId);
            assertThat(claims(approved, true)).anyMatch(text -> text.contains("60 days"))
                    .noneMatch(text -> text.contains("30 days"));
            assertThat(app.json("GET", "acme/sources/dispute-1", REVIEWER, null, 200).path("text").asString())
                    .isEqualTo(DISPUTE);
            app.json("POST", "acme/reviews/" + reviewId + "/approve", REVIEWER, null, 404);

            JsonNode prior = app.json("GET", "acme/history?asOf=" + beforeApproval, REVIEWER, null, 200);
            assertThat(claims(prior, true)).anyMatch(text -> text.contains("30 days"))
                    .noneMatch(text -> text.contains("60 days"));
            JsonNode beta = app.json("GET", "beta/state", BETA, null, 200);
            assertThat(beta.path("approvals").size()).isZero();
            assertThat(claims(beta, true)).anyMatch(text -> text.contains("30 days"))
                    .noneMatch(text -> text.contains("60 days"));
            app.json("GET", "acme/history?asOf=2000-01-01T00:00:00Z", REVIEWER, null, 422);

            app.say("acme", DESK, "pref-1", "Email is our preferred follow-up channel.");
            app.say("acme", DESK, "pref-2", "Phone is our preferred follow-up channel.");
            JsonNode preference = app.json("GET", "acme/state", DESK, null, 200);
            assertThat(claims(preference, false)).anyMatch(text -> text.contains("Email"))
                    .noneMatch(text -> text.contains("Phone"));
            assertThat(preference.path("reviews").get(0).path("text").asString()).contains("Phone");
        }

        try (var app = start()) {
            var checkpoint = app.json("GET", "acme/state", DESK, null, 200).path("approvals").get(0);
            assertThat(checkpoint.path("id").asString()).isEqualTo(reviewId);
            var earlier = app.json("GET", "acme/history?asOf=" + checkpoint.path("prior").asString(),
                    DESK, null, 200);
            var later = app.json("GET", "acme/history?asOf=" + checkpoint.path("approvedAt").asString(),
                    DESK, null, 200);
            assertThat(claims(earlier, true)).containsExactly("Support leads can approve refunds within 30 days.");
            assertThat(claims(later, true)).containsExactly(DISPUTE);
        }
    }

    @Test
    void onlyOneOfTwoServersCanApproveTheSameReview() throws Exception {
        try (var first = start(); var second = start();
             var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            first.say("acme", DESK, "dispute", DISPUTE);
            var before = first.json("GET", "acme/state", DESK, null, 200);
            var reviewId = before.path("reviews").get(0).path("id").asString();
            var route = "acme/reviews/" + reviewId + "/approve";
            var start = new CountDownLatch(1);

            var approvals = List.of(first, second).stream().map(app -> workers.submit(() -> {
                if (!start.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Test did not start both approvals");
                }
                return app.send("POST", route, REVIEWER, null, "application/json").statusCode();
            })).toList();
            start.countDown();

            assertThat(List.of(approvals.get(0).get(10, TimeUnit.SECONDS),
                    approvals.get(1).get(10, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 404);
            for (var app : List.of(first, second)) {
                var after = app.json("GET", "acme/state", REVIEWER, null, 200);
                assertThat(after.path("reviews").size()).isZero();
                assertThat(claims(after, true)).containsExactly(DISPUTE);
                var prior = app.json("GET", "acme/history?asOf=" + before.path("observedAt").asString(),
                        REVIEWER, null, 200);
                assertThat(claims(prior, true)).containsExactly("Support leads can approve refunds within 30 days.");
            }
        }
    }

    @Test
    void configuredDeskTokenReplacesTheDefaultAndKeepsItsPermissions() throws Exception {
        try (var app = start("--governed.demo.desk-token=custom-desk")) {
            app.json("GET", "acme/state", "custom-desk", null, 200);
            app.json("GET", "acme/state", DESK, null, 404);
            app.json("GET", "acme/state", REVIEWER, null, 200);
            app.json("GET", "beta/state", BETA, null, 200);
            app.json("POST", "acme/reviews/unavailable/approve", "custom-desk", null, 403);
        }
    }

    private static List<String> claims(JsonNode state, boolean pinned) {
        return StreamSupport.stream(state.path("claims").spliterator(), false)
                .filter(claim -> claim.path("pinned").asBoolean() == pinned)
                .map(claim -> claim.path("text").asString())
                .toList();
    }

    private RunningApp start(String... overrides) {
        var args = new ArrayList<>(List.of(
                "--server.port=0", "--governed.store.path=" + folder.resolve("chat.db"),
                "--spring.main.banner-mode=off", "--logging.level.root=WARN"));
        args.addAll(List.of(overrides));
        var context = new SpringApplicationBuilder(Application.class).run(args.toArray(String[]::new));
        var port = ((WebServerApplicationContext) context).getWebServer().getPort();
        return new RunningApp(context, port);
    }

    private record RunningApp(ConfigurableApplicationContext context, int port) implements AutoCloseable {
        private static final HttpClient CLIENT = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();

        JsonNode json(String method, String route, String key, Object body, int status)
                throws IOException, InterruptedException {
            return JSON.readTree(request(method, route, key, body, "application/json", status).body());
        }

        HttpResponse<String> say(String workspace, String key, String id, String text)
                throws IOException, InterruptedException {
            return say(workspace, key, id, text, 200);
        }

        HttpResponse<String> say(String workspace, String key, String id, String text, int status)
                throws IOException, InterruptedException {
            var run = Map.of("threadId", "test", "runId", id, "state", Map.of("workspace", "beta"),
                    "messages", List.of(Map.of("id", id, "role", "user", "content", text)));
            return request("POST", workspace + "/chat", key, run, "text/event-stream", status);
        }

        private HttpResponse<String> request(String method, String route, String key, Object body,
                                             String accept, int status) throws IOException, InterruptedException {
            var response = send(method, route, key, body, accept);
            assertThat(response.statusCode()).as("%s %s: %s", method, route, response.body()).isEqualTo(status);
            return response;
        }

        private HttpResponse<String> send(String method, String route, String key, Object body, String accept)
                throws IOException, InterruptedException {
            var uri = URI.create("http://127.0.0.1:" + port + "/api/workspaces/" + route);
            var request = HttpRequest.newBuilder(uri)
                    .header("Authorization", "Bearer " + key)
                    .header("Content-Type", "application/json")
                    .header("Accept", accept)
                    .timeout(Duration.ofSeconds(15))
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                    .build();
            return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        }

        @Override
        public void close() {
            context.close();
        }
    }
}
