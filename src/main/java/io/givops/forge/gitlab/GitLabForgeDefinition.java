package io.givops.forge.gitlab;

import io.givops.adapters.CredentialField;
import io.givops.adapters.CredentialRequest;
import io.givops.adapters.RuntimeCredential;
import io.givops.catalog.ForgeProvider;
import io.givops.forge.model.ForgeConnectionSettings.Hosting;
import io.givops.forge.port.ForgeProviderDefinition;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class GitLabForgeDefinition implements ForgeProviderDefinition {
    private final GitLabTicketDefinition shared;
    public GitLabForgeDefinition(GitLabProperties properties) { shared = new GitLabTicketDefinition(properties); }
    @Override public ForgeProvider provider() { return ForgeProvider.GITLAB; }
    @Override public String displayName() { return "GitLab"; }
    @Override public String adapterId() { return shared.adapterId(); }
    @Override public String adapterVersion() { return shared.adapterVersion(); }
    @Override public List<String> capabilities() {
        return List.of("forge.read", "forge.probe", "forge.discovery", "forge.repository-identity", "forge.pipeline-create");
    }
    @Override public List<String> apiProfiles() { return List.of("gitlab-rest-v4", "gitlab-graphql-pipeline-inputs"); }
    @Override public List<CredentialField> credentialFields() { return shared.credentialFields(); }
    @Override public URI normalizeBaseUrl(URI value) { return shared.normalizeBaseUrl(value); }
    @Override public Map<String,String> validateCredentials(CredentialRequest target, Map<String,String> fields) {
        return shared.validateCredentials(target, fields);
    }
    @Override public Optional<RuntimeCredential> runtimeCredentials(CredentialRequest target) { return shared.runtimeCredentials(target); }
    @Override public Hosting defaultHosting() { return Hosting.SELF_MANAGED; }
    @Override public List<Hosting> hostingModes() { return List.of(Hosting.SELF_MANAGED, Hosting.SAAS, Hosting.DEDICATED, Hosting.UNKNOWN); }
    @Override public String repositoryPathPattern() { return GitLabTicketDefinition.PROJECT_PATH; }
    @Override public String externalIdPattern() { return shared.externalIdPattern(); }
    @Override public String commitPathTemplate() { return "/-/commit/{sha}"; }
    @Override public void validateRepository(String externalId, String repositoryPath) {
        shared.validateProject(externalId, repositoryPath);
    }
}
