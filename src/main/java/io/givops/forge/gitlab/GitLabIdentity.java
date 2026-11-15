package io.givops.forge.gitlab;

import io.givops.catalog.ForgeProvider;
import io.givops.forge.port.ForgeRepositoryIdentity;
import io.givops.forge.port.ForgeRequestGate;
import java.net.URI;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

final class GitLabIdentity implements ForgeRepositoryIdentity {
    private final GitLabTransport transport;
    private final JsonMapper mapper;
    GitLabIdentity(GitLabTransport transport, JsonMapper mapper) { this.transport = transport; this.mapper = mapper; }
    @Override public ForgeProvider provider() { return ForgeProvider.GITLAB; }
    @Override public Identity lookup(URI baseUrl, String path, ForgeRequestGate gate) {
        var urls = new GitLabUrls(baseUrl);
        var response = transport.get(urls.detail(GitLabUrls.project(path)), null, gate);
        try {
            var row = mapper.readTree(response.body());
            GitLabPayload.requireObject(row);
            String actual = GitLabPayload.text(row, "path_with_namespace", true);
            if (!path.equals(actual) || !row.path("id").isIntegralNumber() || row.path("id").longValueOpt().orElse(0) <= 0)
                throw GitLabErrors.invalidResponse();
            gate.acceptedObjects(1);
            return new Identity(row.path("id").asText(), actual);
        } catch (JacksonException failure) { throw GitLabErrors.invalidResponse(); }
    }
}
