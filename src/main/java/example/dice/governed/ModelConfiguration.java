package example.dice.governed;

import com.embabel.agent.spi.LlmService;
import com.embabel.agent.spi.support.springai.SpringAiLlmService;
import com.embabel.common.ai.model.EmbeddingService;
import com.embabel.common.ai.model.SpringAiEmbeddingService;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.stream.IntStream;

@Configuration(proxyBeanMethods = false)
@Profile("!live")
class ModelConfiguration {

    @Bean
    LlmService<?> scriptedLlm() {
        return new SpringAiLlmService("scripted", "governed-chat", new OfflineChatModel());
    }

    @Bean
    EmbeddingService demoEmbeddings() {
        var model = new EmbeddingModel() {
            @Override
            public EmbeddingResponse call(EmbeddingRequest request) {
                return new EmbeddingResponse(IntStream.range(0, request.getInstructions().size())
                        .mapToObj(index -> new Embedding(vector(), index)).toList());
            }

            @Override
            public float[] embed(Document document) {
                return vector();
            }

            private float[] vector() {
                // The demo retrieves by workspace and explicit fields. Its offline fixture
                // supplies the required embedder without making similarity claims.
                return new float[] {1, 0, 0};
            }
        };
        return new SpringAiEmbeddingService("demo-embeddings", "governed-chat", model, 3, null);
    }
}
