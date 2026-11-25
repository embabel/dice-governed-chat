package example.dice.governed;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SupportQuestionsTest {

    @TempDir
    Path temp;

    @Test
    void answersAndRedirectsReadOnlyThroughAgUi() throws Exception {
        try (var context = new SpringApplicationBuilder(Application.class).profiles("offline")
                .web(WebApplicationType.NONE).run("--governed.store.path=" + temp.resolve("support.sqlite"),
                        "--spring.main.banner-mode=off", "--logging.level.root=WARN")) {
            var workflow = context.getBean(Workflow.class);
            var before = workflow.state("acme");
            var mvc = MockMvcBuilders.standaloneSetup(context.getBean(ChatHttp.class)).build();
            var json = JsonMapper.builder().build();

            for (var question : List.of("How long do customers have to ask for a refund?", "Who discovered Neptune?")) {
                var payload = json.writeValueAsString(new AgUi.Run("thread", "turn", List.of(
                        new AgUi.Message("utterance", "user", question)), new AgUi.ForwardedProps("ask")));
                var answer = mvc.perform(post("/api/workspaces/acme/chat").header("Authorization", "Bearer desk-demo")
                                .contentType("application/json").content(payload))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
                assertThat(answer).contains(question.contains("refund")
                        ? "approve refunds within 30 days" : "Would you like to know what refund policy is in effect?");
            }
            var after = workflow.state("acme");
            assertThat(after.claims()).isEqualTo(before.claims());
            assertThat(after.reviews()).isEqualTo(before.reviews());
        }
    }
}
