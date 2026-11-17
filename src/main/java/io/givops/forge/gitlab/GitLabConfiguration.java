package io.givops.forge.gitlab;

import io.givops.adapters.ContextSigner;
import io.givops.adapters.CredentialHost;
import io.givops.forge.config.ForgeProperties;
import io.givops.ticketing.config.TicketProperties;
import java.time.Clock;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.json.JsonMapper;

/** One installable library; optional host domains may be configured independently. */
@AutoConfiguration
@EnableConfigurationProperties(GitLabProperties.class)
@org.springframework.context.annotation.Import({GitLabConfiguration.ForgeConfiguration.class, GitLabConfiguration.TicketConfiguration.class})
public class GitLabConfiguration {
    @Bean GitLabTicketDefinition gitLabTicketDefinition(GitLabProperties properties) { return new GitLabTicketDefinition(properties); }
    @Bean GitLabForgeDefinition gitLabForgeDefinition(GitLabProperties properties) { return new GitLabForgeDefinition(properties); }

    @ConditionalOnBean(ForgeProperties.class)
    static class ForgeConfiguration {
        @Bean GitLabTransport gitLabForgeTransport(ForgeProperties limits) { return new GitLabTransport(limits.requestTimeout(), limits.maxResponseBytes()); }
        @Bean GitLabAdapter gitLabAdapter(ForgeProperties limits, GitLabTransport gitLabForgeTransport, JsonMapper json, Clock clock) {
            return new GitLabAdapter(limits, gitLabForgeTransport, json, clock);
        }
        @Bean GitLabDiscoveryAdapter gitLabDiscoveryAdapter(GitLabTransport gitLabForgeTransport, JsonMapper json) {
            return new GitLabDiscoveryAdapter(gitLabForgeTransport, json);
        }
        @Bean GitLabConnectionProbe gitLabConnectionProbe(GitLabTransport gitLabForgeTransport, JsonMapper json, Clock clock) {
            return new GitLabConnectionProbe(gitLabForgeTransport, json, clock);
        }
        @Bean GitLabPipelineCommands gitLabPipelineCommands(GitLabTransport gitLabForgeTransport, JsonMapper json, ForgeProperties limits) {
            return new GitLabPipelineCommands(gitLabForgeTransport, json, limits);
        }
    @Bean GitLabRepositories gitLabProvisioningAdapter(GitLabTransport gitLabForgeTransport, JsonMapper mapper) { return new GitLabRepositories(gitLabForgeTransport,mapper); }
        @Bean GitLabIdentity gitLabIdentity(GitLabTransport gitLabForgeTransport, JsonMapper json) { return new GitLabIdentity(gitLabForgeTransport, json); }
        @Bean GitLabForgeCapabilities gitLabForgeCapabilities(GitLabTransport gitLabForgeTransport, JsonMapper json, Clock clock) {
            return new GitLabForgeCapabilities(gitLabForgeTransport, json, clock);
        }
    }
    @ConditionalOnBean({CredentialHost.class, ContextSigner.class, TicketProperties.class})
    static class TicketConfiguration {
        @Bean GitLabTransport gitLabTicketTransport(TicketProperties limits) { return new GitLabTransport(limits.requestTimeout(), limits.maxResponseBytes()); }
        @Bean GitLabTickets gitLabTickets(CredentialHost host, ContextSigner signer, TicketProperties limits,
                                         GitLabTransport gitLabTicketTransport, JsonMapper json, Clock clock, GitLabTicketDefinition definition) {
            return new GitLabTickets(new GitLabCredentials(host, signer), limits, gitLabTicketTransport, json, clock, definition);
        }
    }
}
