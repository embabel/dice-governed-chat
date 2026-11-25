package example.dice.governed;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import java.util.Arrays;

@SpringBootApplication
@EnableConfigurationProperties(DemoAccessProperties.class)
public class Application {

    public static void main(String[] args) {
        var application = new SpringApplication(Application.class);
        var key = System.getenv("OPENAI_API_KEY");
        var configuredProfiles = System.getenv("SPRING_PROFILES_ACTIVE") != null
                || System.getProperty("spring.profiles.active") != null
                || Arrays.stream(args).anyMatch(arg -> arg.startsWith("--spring.profiles.active="));
        if (!configuredProfiles && key != null && !key.isBlank()) {
            application.setAdditionalProfiles("live");
        }
        application.run(args);
    }
}
