package example.dice.governed;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("governed.demo")
record DemoAccessProperties(
        @DefaultValue("desk-demo") String deskToken,
        @DefaultValue("reviewer-demo") String reviewerToken,
        @DefaultValue("beta-demo") String betaToken) {
}
