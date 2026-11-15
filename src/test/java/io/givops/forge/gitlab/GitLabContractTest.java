package io.givops.forge.gitlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.givops.adapters.ContextSigner;
import io.givops.adapters.CredentialHost;
import io.givops.forge.config.ForgeProperties;
import io.givops.forge.model.ChangeRequestLifecycle;
import io.givops.forge.model.ForgeModels.ConflictState;
import io.givops.forge.model.ForgeModels.CiState;
import io.givops.forge.port.ForgeAdapter;
import io.givops.forge.port.ForgeProviderDefinition;
import io.givops.ticketing.config.TicketProperties;
import io.givops.ticketing.port.TicketProviderAdapter;
import io.givops.ticketing.port.TicketProviderDefinition;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;

class GitLabContractTest {
    private final GitLabTicketDefinition definition = GitLabTicketsTest.DEFINITION;
    @Test void scopesLocalQualifiedAndUrlReferencesWithoutCollisions() {
        assertThat(definition.normalizeIssueKey("#7", "team/payments")).contains("team/payments#7");
        assertThat(definition.normalizeIssueKey("other/payments#7", "team/payments")).isEmpty();
        assertThat(definition.normalizeIssueKey("team/payments#7", "team/payments")).contains("team/payments#7");
        assertThat(definition.normalizeIssueKey("#0", "team/payments")).isEmpty();
        var result = definition.issueReference(URI.create("https://gitlab.example.test/gitlab/team/payments/-/issues/7"),
                URI.create("https://gitlab.example.test/gitlab")).orElseThrow();
        assertThat(result.issueKey()).isEqualTo("team/payments#7"); assertThat(result.contextPath()).isEqualTo("/gitlab");
        assertThat(definition.issueReference(URI.create("https://evil.example.test/team/payments/-/issues/7"), GitLabTicketsTest.BASE)).isEmpty();
        assertThat(definition.issueReference(URI.create("https://gitlab.example.test/team/payments/-/issues/7?redirect=evil"))).isEmpty();
    }
    @Test void rejectsUnsafeBaseUrlsAndProjectPaths() {
        for (String value : java.util.List.of("http://gitlab.example.test", "https://token@gitlab.example.test", "https://gitlab.example.test/../other", "https://gitlab.example.test?token=secret"))
            assertThatThrownBy(() -> definition.normalizeBaseUrl(URI.create(value))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> definition.validateProject("42", "team/../secret")).isInstanceOf(RuntimeException.class);
    }
    @Test void packagesOneArtifactWithBothIndependentDomains() throws Exception {
        var metadata = new java.util.Properties();
        try (var stream = getClass().getResourceAsStream("/META-INF/givops-adapter.properties")) { metadata.load(stream); }
        assertThat(metadata.getProperty("domains")).isEqualTo("ticketing,forge");
        assertThat(metadata.getProperty("sdkApiVersion")).isEqualTo("0.2");
        assertThat(definition.capabilities()).contains("ticketing.read", "ticketing.project-create", "ticketing.version-create", "ticketing.issue-create").doesNotContain("ticketing.write");
        assertThat(definition.commandLabels().get("versionResource")).isEqualTo("Milestone");
        io.givops.adapters.testkit.AdapterTestkit.verifyDefinition(definition);
        io.givops.adapters.testkit.AdapterTestkit.verifyDefinition(new GitLabForgeDefinition(new GitLabProperties(Map.of())));
        definition.validateCreateProjectKey("new-project");
        assertThatThrownBy(() -> definition.validateProjectKey("new-project")).isInstanceOf(RuntimeException.class);
    }
    @Test void registersBothDomainsWithoutHostComponentScanning() {
        runner().withBean(ForgeProperties.class, GitLabContractTest::forgeLimits)
                .withBean(TicketProperties.class, GitLabTicketsTest::limits)
                .withBean(CredentialHost.class, () -> request -> { throw new AssertionError("Registration must not capture credentials"); })
                .withBean(ContextSigner.class, () -> (provider, namespace, parts) -> "context")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(ForgeAdapter.class).hasSingleBean(TicketProviderAdapter.class)
                            .hasSingleBean(ForgeProviderDefinition.class).hasSingleBean(TicketProviderDefinition.class);
                });
    }
    @Test void broadHostScanningDoesNotEvaluateOptionalDomainsBeforeHostBeans() {
        new ApplicationContextRunner().withUserConfiguration(ScanningHost.class)
                .withConfiguration(AutoConfigurations.of(GitLabConfiguration.class))
                .run(context -> assertThat(context).hasNotFailed()
                        .hasSingleBean(ForgeAdapter.class).hasSingleBean(TicketProviderAdapter.class)
                        .hasSingleBean(io.givops.forge.command.PipelineCommandProvider.class));
    }
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.context.annotation.ComponentScan(basePackageClasses = GitLabConfiguration.class,
            excludeFilters = @org.springframework.context.annotation.ComponentScan.Filter(
                    type = org.springframework.context.annotation.FilterType.ANNOTATION,
                    classes = org.springframework.boot.autoconfigure.AutoConfiguration.class))
    static class ScanningHost {
        @org.springframework.context.annotation.Bean ForgeProperties hostForgeProperties() { return forgeLimits(); }
        @org.springframework.context.annotation.Bean TicketProperties hostTicketProperties() { return GitLabTicketsTest.limits(); }
        @org.springframework.context.annotation.Bean JsonMapper hostMapper() { return JsonMapper.builder().build(); }
        @org.springframework.context.annotation.Bean Clock hostClock() { return Clock.systemUTC(); }
        @org.springframework.context.annotation.Bean CredentialHost hostCredentials() {
            return request -> { throw new AssertionError("Registration must not capture credentials"); };
        }
        @org.springframework.context.annotation.Bean ContextSigner hostSigner() { return (provider, namespace, parts) -> "context"; }
    }
    @Test void forgeDomainRunsWithoutTicketHost() {
        runner().withBean(ForgeProperties.class, GitLabContractTest::forgeLimits).run(context ->
                assertThat(context).hasNotFailed().hasSingleBean(ForgeAdapter.class).doesNotHaveBean(TicketProviderAdapter.class));
    }
    @Test void ticketDomainRunsWithoutForgeHost() {
        runner().withBean(TicketProperties.class, GitLabTicketsTest::limits)
                .withBean(CredentialHost.class, () -> request -> { throw new AssertionError(); })
                .withBean(ContextSigner.class, () -> (provider, namespace, parts) -> "context")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(TicketProviderAdapter.class).doesNotHaveBean(ForgeAdapter.class));
    }
    @Test void normalizesLifecycleAndTaskSignalsInsideTheProvider() {
        assertThat(GitLabPayload.lifecycle("opened")).isEqualTo(ChangeRequestLifecycle.OPEN);
        assertThat(GitLabPayload.lifecycle("merged")).isEqualTo(ChangeRequestLifecycle.MERGED);
        assertThat(GitLabPayload.lifecycle("closed")).isEqualTo(ChangeRequestLifecycle.CLOSED);
        assertThat(GitLabPayload.lifecycle("future-state")).isEqualTo(ChangeRequestLifecycle.UNKNOWN);
        var signals = GitLabPayload.signals(2, java.util.Set.of("1"), true, "conflict", null);
        assertThat(signals.conflictState()).isEqualTo(ConflictState.CONFLICT);
        assertThat(signals.ciSummary().state()).isEqualTo(CiState.UNKNOWN);
    }
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(GitLabConfiguration.class))
                .withBean(JsonMapper.class, () -> JsonMapper.builder().build()).withBean(Clock.class, Clock::systemUTC);
    }
    static ForgeProperties forgeLimits() {
        return new ForgeProperties(Duration.ofSeconds(30), Duration.ofSeconds(2), 100, 10, 262_144, 1_048_576,
                50, 50, 500, 1000, 4, Duration.ofMinutes(5), Duration.ofMinutes(15), 50);
    }
}
