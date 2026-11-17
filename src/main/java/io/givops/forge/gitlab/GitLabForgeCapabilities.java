package io.givops.forge.gitlab;

import io.givops.adapters.CapabilityInspection;
import io.givops.adapters.CapabilityInspection.State;
import io.givops.catalog.ForgeProvider;
import io.givops.forge.model.ForgeException;
import io.givops.forge.model.ForgeConnectionSettings.Hosting;
import io.givops.forge.model.ForgeModels.Source;
import io.givops.forge.port.ForgeAdapter.Access;
import io.givops.forge.port.ForgeCapabilityInspector;
import io.givops.forge.port.ForgeRequestGate;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.json.JsonMapper;

final class GitLabForgeCapabilities implements ForgeCapabilityInspector {
    private final GitLabTransport transport;
    private final JsonMapper mapper;
    private final Clock clock;
    GitLabForgeCapabilities(GitLabTransport transport, JsonMapper mapper, Clock clock) {
        this.transport = transport; this.mapper = mapper; this.clock = clock;
    }
    @Override public ForgeProvider provider() { return ForgeProvider.GITLAB; }
    @Override public CapabilityInspection inspect(Source source, String capability, Access access, ForgeRequestGate gate) {
        boolean discovery = capability.equals("forge.discovery") || capability.equals("forge.group-hierarchy");
        if (discovery && access.hosting() != Hosting.SELF_MANAGED && access.hosting() != Hosting.SAAS)
            return result(capability, State.UNSUPPORTED, "discovery_hosting_unsupported", null, source);
        if (discovery && !GitLabCredentials.validToken(access.token()))
            return result(capability, State.FORBIDDEN, "gitlab_unauthorized", null, source);
        var urls = new GitLabUrls(source.baseUrl());
        String version = null; State state; String reason;
        try {
            var profile = GitLabVersions.read(transport, urls, access.token(), gate, mapper.reader());
            version = profile.version();
            if (capability.equals("forge.pipeline-create") && profile.beforePipelineInputs())
                return result(capability, State.UNSUPPORTED, "pipeline_inputs_require_gitlab_17_10", version, source);
        } catch (ForgeException failure) { /* Metadata does not determine permission for project operations. */ }
        if (!Set.of("forge.read", "forge.probe", "forge.discovery", "forge.group-hierarchy", "forge.repository-create", "forge.repository-identity", "forge.pipeline-create").contains(capability))
            return result(capability, State.UNSUPPORTED, "operation_not_implemented", version, source);
        try {
            if (capability.equals("forge.repository-create")) {
                if (!GitLabCredentials.validToken(access.token())) return result(capability,State.FORBIDDEN,"gitlab_unauthorized",version,source);
                transport.get(urls.detail("/user"),access.token(),gate);
                return result(capability,State.UNKNOWN,"namespace_and_create_permission_require_explicit_command",version,source);
            }
            if (source.projectId() == null) { state = State.UNKNOWN; reason = "project_not_selected"; }
            else if (discovery && access.hosting() == Hosting.SAAS) {
                if (!source.projectId().matches("[1-9][0-9]{0,18}"))
                    return result(capability, State.UNKNOWN, "numeric_project_identity_required", version, source);
                new GitLabDiscoveryAdapter(transport, mapper).project(
                        new io.givops.forge.discovery.DiscoveryModels.Server(source.baseUrl()), access, source.projectId(), gate);
                state = State.AVAILABLE; reason = "project_discovery_scope_verified";
            } else {
                var response = transport.get(urls.detail(GitLabUrls.project(source.projectId())), access.token(), gate);
                var row = mapper.readTree(response.body()); GitLabPayload.requireObject(row); gate.acceptedObjects(1);
                if (capability.equals("forge.pipeline-create")) {
                    if ("disabled".equals(row.path("builds_access_level").asText())) { state = State.DISABLED; reason = "project_ci_disabled"; }
                    else { state = State.UNKNOWN; reason = "pipeline_schema_and_ref_permission_require_inspection"; }
                } else {
                    transport.get(urls.uri(GitLabUrls.project(source.projectId()) + "/merge_requests", Map.of("state", "opened"), 1, 1), access.token(), gate);
                    state = State.AVAILABLE; reason = "project_endpoint_readable";
                }
            }
        } catch (ForgeException failure) {
            state = switch (failure.getCode()) {
                case "gitlab_unauthorized", "gitlab_forbidden" -> State.FORBIDDEN;
                case "gitlab_timeout", "gitlab_rate_limited", "gitlab_unavailable", "gitlab_transport_failed" -> State.TEMPORARILY_UNAVAILABLE;
                default -> State.UNKNOWN;
            };
            reason = failure.getCode();
        } catch (tools.jackson.core.JacksonException failure) { state = State.UNKNOWN; reason = "gitlab_invalid_response"; }
        return result(capability, state, reason, version, source);
    }
    private CapabilityInspection result(String capability, State state, String reason, String version, Source source) {
        return new CapabilityInspection("GITLAB", capability, state, reason, version, "gitlab-rest-v4",
                source.forgeId() + ":" + source.forgeRevision() + ":" + source.projectId(), clock.instant());
    }
}
