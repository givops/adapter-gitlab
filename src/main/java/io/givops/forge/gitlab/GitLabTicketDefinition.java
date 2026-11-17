package io.givops.forge.gitlab;

import io.givops.adapters.CredentialField;
import io.givops.adapters.CredentialRequest;
import io.givops.adapters.RuntimeCredential;
import io.givops.ticketing.model.TicketModels.TicketProvider;
import io.givops.ticketing.model.TicketException.Code;
import io.givops.ticketing.port.TicketProviderDefinition;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** GitLab project paths preserve case; numeric issue references are project scoped. */
public final class GitLabTicketDefinition implements TicketProviderDefinition {
    static final String PROJECT_PATH = "[A-Za-z0-9_][A-Za-z0-9_.-]*(?:/[A-Za-z0-9_][A-Za-z0-9_.-]*)+";
    private final GitLabProperties properties;
    public GitLabTicketDefinition(GitLabProperties properties) { this.properties = properties; }
    @Override public TicketProvider provider() { return TicketProvider.GITLAB; }
    @Override public String displayName() { return "GitLab"; }
    @Override public String adapterId() { return "io.givops:adapter-gitlab"; }
    @Override public String adapterVersion() { return "0.3.0-SNAPSHOT"; }
    @Override public List<String> capabilities() {
        return List.of("ticketing.read", "ticketing.navigation", "ticketing.browse", "ticketing.content", "ticketing.version-membership", "ticketing.project-create",
                "ticketing.version-create", "ticketing.issue-create");
    }
    @Override public List<String> apiProfiles() { return List.of("gitlab-rest-v4"); }
    @Override public List<CredentialField> credentialFields() {
        return List.of(new CredentialField("apiToken", "API token", CredentialField.Type.SECRET, true, 4096));
    }
    @Override public Map<String,String> commandLabels() {
        return Map.of("projectKey", "Project slug", "projectName", "Project name", "projectOwner", "Namespace ID (optional)",
                "projectResource", "Project", "versionResource", "Milestone", "issueResource", "Issue");
    }
    @Override public Optional<IssueReference> issueReference(URI candidate, URI baseUrl) {
        if (candidate == null || baseUrl == null || !java.util.Objects.equals(candidate.getHost(), baseUrl.getHost())
                || candidate.getPort() != baseUrl.getPort() || !"https".equalsIgnoreCase(candidate.getScheme())
                || candidate.getRawUserInfo() != null) return Optional.empty();
        String prefix = baseUrl.getRawPath().replaceAll("/+$", "");
        if (!candidate.getRawPath().startsWith(prefix + "/")) return Optional.empty();
        URI relative = URI.create(candidate.getRawPath().substring(prefix.length())
                + (candidate.getRawQuery() == null ? "" : "?" + candidate.getRawQuery())
                + (candidate.getRawFragment() == null ? "" : "#" + candidate.getRawFragment()));
        return issueReference(relative).map(ref -> new IssueReference(ref.issueKey(), prefix));
    }
    @Override public String createProjectKeyPattern() { return "[A-Za-z0-9_][A-Za-z0-9_.-]{0,254}"; }
    @Override public String projectKeyPattern() { return PROJECT_PATH; }
    @Override public String externalIdPattern() { return "[1-9][0-9]{0,18}|" + PROJECT_PATH; }
    @Override public URI normalizeBaseUrl(URI value) {
        try { new GitLabUrls(value); }
        catch (io.givops.forge.model.ForgeException failure) {
            throw GitLabCredentials.error(Code.INVALID_CONFIGURATION, "GitLab requires an HTTPS instance base URL with a safe optional context path.");
        }
        return URI.create(value.toString().replaceAll("/+$", ""));
    }
    @Override public void validateProject(String externalId, String projectKey) {
        validateProjectKey(projectKey);
        if (externalId == null || !externalId.matches(externalIdPattern()))
            throw GitLabCredentials.error(Code.INVALID_CONFIGURATION, "GitLab requires a project ID or path.");
        try { GitLabUrls.project(externalId); }
        catch (io.givops.forge.model.ForgeException failure) { throw GitLabCredentials.error(Code.INVALID_CONFIGURATION, "Invalid GitLab project identity."); }
    }
    @Override public void validateProjectKey(String value) {
        if (value == null || value.length() > 512 || !value.matches(PROJECT_PATH))
            throw GitLabCredentials.error(Code.INVALID_CONFIGURATION, "GitLab requires the complete project path.");
        try { GitLabUrls.project(value); }
        catch (io.givops.forge.model.ForgeException failure) { throw GitLabCredentials.error(Code.INVALID_CONFIGURATION, "Invalid GitLab project path."); }
    }
    @Override public Optional<String> normalizeIssueKey(String token, String projectKey) {
        if (token == null || projectKey == null) return Optional.empty();
        String local = token.startsWith(projectKey + "#") ? token.substring(projectKey.length()) : token;
        return local.matches("#[1-9][0-9]{0,18}") ? Optional.of(projectKey + local) : Optional.empty();
    }
    @Override public Optional<IssueReference> issueReference(URI candidate) {
        if (candidate == null || candidate.getRawQuery() != null || candidate.getRawFragment() != null) return Optional.empty();
        var matcher = java.util.regex.Pattern.compile("/(" + PROJECT_PATH + ")/-/issues/([1-9][0-9]{0,18})")
                .matcher(candidate.getRawPath() == null ? "" : candidate.getRawPath());
        return matcher.matches() ? Optional.of(new IssueReference(matcher.group(1) + "#" + matcher.group(2), "")) : Optional.empty();
    }
    @Override public Map<String,String> validateCredentials(CredentialRequest target, Map<String,String> fields) {
        if (target == null || !"GITLAB".equals(target.provider()) || fields == null || fields.size() != 1
                || !GitLabCredentials.validToken(fields.get("apiToken")))
            throw GitLabCredentials.error(Code.INVALID_CONFIGURATION, "GitLab requires an API token.");
        normalizeBaseUrl(target.baseUrl());
        return Map.copyOf(fields);
    }
    @Override public Optional<RuntimeCredential> runtimeCredentials(CredentialRequest target) {
        var instance = properties.instances().get(target.instanceKey());
        if (instance == null) return Optional.empty();
        String base;
        try { base = normalizeBaseUrl(URI.create(instance.expectedBaseUrl())).toString(); }
        catch (RuntimeException failure) { base = ""; }
        return Optional.of(new RuntimeCredential(Map.of("apiToken", java.util.Objects.toString(instance.token(), "")),
                Map.of("baseUrl", base, "instanceKey", target.instanceKey()), instance.accessRevision()));
    }
}
