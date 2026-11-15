package io.givops.forge.gitlab;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties("givops.forge.gitlab")
public record GitLabProperties(Map<String, Instance> instances) {
    public GitLabProperties {
        instances = instances == null ? Map.of() : Map.copyOf(instances);
    }

    public enum Hosting { SAAS, SELF_MANAGED, DEDICATED, UNKNOWN }

    public record Instance(String token, Hosting hosting, String expectedBaseUrl, String accessRevision) {
        public Instance(String token, Hosting hosting) { this(token, hosting, null, null); }

        @ConstructorBinding
        public Instance {
            expectedBaseUrl = expectedBaseUrl == null ? null : expectedBaseUrl.replaceAll("/+$", "");
            hosting = hosting == null ? Hosting.UNKNOWN : hosting;
        }

        @Override
        public String toString() {
            return "Instance[hosting=" + hosting + "]";
        }
    }
}
