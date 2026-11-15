package io.givops.forge.gitlab;


import io.givops.catalog.ForgeProvider;
import io.givops.forge.port.ForgeAdapter.Access;
import io.givops.forge.command.PipelineCommandProvider;
import io.givops.forge.command.PipelineCommandProvider.InputDefinition;
import io.givops.forge.config.ForgeProperties;
import io.givops.forge.model.ForgeException;
import io.givops.forge.port.ForgeRequestGate;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public class GitLabPipelineCommands implements PipelineCommandProvider {
    private static final String HEADS = "refs/heads/";
    private static final int MAX_PAYLOAD_BYTES = 65_536;
    private static final int MAX_RESPONSE_BYTES = 262_144;
    private static final String QUERY = """
            query PipelineCreationInputs($path: ID!, $ref: String!) {
              project(fullPath: $path) {
                id
                fullPath
                ciPipelineCreationInputs(ref: $ref) {
                  name
                  type
                  description
                  required
                  default
                  options
                  regex
                  rules { if }
                }
              }
            }
            """;
    private final GitLabTransport transport;
    private final ObjectMapper mapper;
    private final ForgeProperties properties;

    GitLabPipelineCommands(GitLabTransport transport, ObjectMapper mapper, ForgeProperties properties) {
        this.transport = transport;
        this.mapper = mapper;
        this.properties = properties;
    }

    @Override
    public boolean supports(Repository repository) {
        if (!ForgeProvider.GITLAB.equals(repository.provider())) return false;
        try {
            new GitLabUrls(repository.baseUrl());
            GitLabUrls.project(repository.externalId());
            GitLabUrls.project(repository.path());
            return repository.path().contains("/") && (numericId(repository.externalId())
                    || repository.externalId().equals(repository.path()));
        } catch (ForgeException exception) {
            return false;
        }
    }

    @Override
    public List<InputDefinition> definitions(Repository repository, Access credential, String fullRef) {
        if (!supports(repository) || fullRef == null || !fullRef.startsWith(HEADS) || fullRef.length() == HEADS.length())
            throw GitLabErrors.invalidConfiguration();
        requireSupportedInputs(repository, credential);
        String base = repository.baseUrl().toASCIIString().replaceFirst("/$", "");
        var request = Map.of("query", QUERY, "variables", Map.of("path", repository.path(), "ref", fullRef));
        var response = transport.post(URI.create(base + "/api/graphql"), credential.token(), payload(request),
                new Gate(properties.requestTimeout()), 200);
        if (response.status() == 401 || response.status() == 403) {
            throw new ForgeException("pipeline_inputs_forbidden", HttpStatus.FORBIDDEN,
                    GitLabMessages.format("forge.gitLabPipelineCommands.gitlab.denied.access.to.pipeline.input.definitions"));
        }
        if (response.status() == 404) throw unsupported();
        if (response.status() != 200) throw GitLabErrors.remoteStatus(response.status());
        try {
            JsonNode root = mapper.readTree(response.body());
            JsonNode errors = root.path("errors");
            if (!errors.isMissingNode() && (!errors.isArray() || !errors.isEmpty())) throw unsupported();
            JsonNode project = root.path("data").path("project");
            if (!repository.path().equals(GitLabPayload.text(project, "fullPath", true))
                    || numericId(repository.externalId()) && !("gid://gitlab/Project/" + repository.externalId())
                            .equals(GitLabPayload.text(project, "id", true))) {
                throw unsupported();
            }
            JsonNode specs = project.path("ciPipelineCreationInputs");
            if (!specs.isArray() || specs.size() > 50) throw unsupported();
            var definitions = new ArrayList<InputDefinition>();
            for (JsonNode spec : specs) definitions.add(definition(spec));
            return List.copyOf(definitions);
        } catch (JacksonException exception) {
            throw unsupported();
        } catch (ForgeException exception) {
            if ("gitlab_invalid_response".equals(exception.getCode())) throw unsupported();
            throw exception;
        }
    }

    @Override
    public Result create(Repository repository, Access credential, String fullRef, Map<String, JsonNode> inputs) {
        if (!supports(repository) || fullRef == null || !fullRef.startsWith(HEADS) || fullRef.length() == HEADS.length())
            return Result.failure("gitlab_configuration_invalid", false);
        if (!inputs.isEmpty()) {
            try { requireSupportedInputs(repository, credential); }
            catch (ForgeException failure) { return Result.failure(failure.getCode(), false); }
        }
        var urls = new GitLabUrls(repository.baseUrl());
        var request = inputs.isEmpty() ? Map.of("ref", fullRef) : Map.of("ref", fullRef, "inputs", inputs);
        byte[] body = payload(request);
        try {
            var response = transport.post(urls.detail(GitLabUrls.project(repository.externalId()) + "/pipeline"),
                    credential.token(), body, new Gate(properties.requestTimeout()), 201);
            if (response.status() != 201) {
                var failure = GitLabErrors.remoteStatus(response.status());
                return Result.failure(failure.getCode(), response.status() < 300
                        || response.status() == 408 || response.status() >= 500);
            }
            JsonNode pipeline = mapper.readTree(response.body());
            long id = pipeline.path("id").longValueOpt().orElse(0);
            long projectId = pipeline.path("project_id").longValueOpt().orElse(0);
            String sha = GitLabPayload.text(pipeline, "sha", true);
            String ref = GitLabPayload.text(pipeline, "ref", true);
            if (!pipeline.path("id").isIntegralNumber() || !pipeline.path("project_id").isIntegralNumber()
                    || id <= 0 || projectId <= 0 || !sha.matches("[0-9a-f]{40}|[0-9a-f]{64}")
                    || !fullRef.equals(ref) && !fullRef.substring(HEADS.length()).equals(ref)
                    || Boolean.TRUE.equals(GitLabPayload.flag(pipeline, "tag"))
                    || numericId(repository.externalId()) && !Long.toString(projectId).equals(repository.externalId())) {
                return Result.failure("gitlab_invalid_pipeline_receipt", true);
            }
            JsonNode link = pipeline.path("web_url");
            return Result.accepted(ref, sha, Long.toString(id),
                    safeWebUrl(repository, id, link.isTextual() ? link.stringValue() : null));
        } catch (ForgeException exception) {
            return Result.failure(exception.getCode(), true);
        } catch (JacksonException exception) {
            return Result.failure("gitlab_invalid_pipeline_receipt", true);
        }
    }

    private void requireSupportedInputs(Repository repository, Access credential) {
        GitLabVersions.Profile profile;
        try {
            profile = GitLabVersions.read(transport, new GitLabUrls(repository.baseUrl()), credential.token(),
                    new Gate(properties.requestTimeout()), mapper.reader());
        } catch (ForgeException failure) {
            if (Set.of("gitlab_not_found", "gitlab_forbidden", "gitlab_unauthorized", "gitlab_invalid_response")
                    .contains(failure.getCode())) return;
            throw failure;
        }
        if (profile.beforePipelineInputs()) throw unsupported();
    }

    private static InputDefinition definition(JsonNode spec) {
        JsonNode rules = spec.path("rules");
        if (!rules.isNull() && (!rules.isArray() || !rules.isEmpty())) throw unsupported();
        Boolean required = GitLabPayload.flag(spec, "required");
        if (required == null) throw unsupported();
        JsonNode defaultValue = spec.path("default");
        JsonNode choices = spec.path("options");
        if (defaultValue.isMissingNode() || !choices.isNull() && (!choices.isArray() || choices.size() > 100)) {
            throw unsupported();
        }
        var options = new ArrayList<JsonNode>();
        if (choices.isArray()) choices.forEach(options::add);
        return new InputDefinition(GitLabPayload.text(spec, "name", true),
                GitLabPayload.text(spec, "type", true).toLowerCase(Locale.ROOT),
                GitLabPayload.text(spec, "description", false), required, defaultValue.isNull() ? null : defaultValue,
                options, GitLabPayload.text(spec, "regex", false));
    }

    private byte[] payload(Object value) {
        byte[] bytes = mapper.writeValueAsBytes(value);
        if (bytes.length > MAX_PAYLOAD_BYTES) {
            throw new ForgeException("pipeline_inputs_too_large", HttpStatus.PAYLOAD_TOO_LARGE,
                    GitLabMessages.format("forge.gitLabPipelineCommands.the.pipeline.input.request.exceeds.the.supported.size"));
        }
        return bytes;
    }

    private static String safeWebUrl(Repository repository, long id, String url) {
        if (url == null || url.length() > 2048) return null;
        try {
            URI link = URI.create(url);
            URI base = repository.baseUrl();
            String basePath = base.getPath().replaceFirst("/$", "");
            String expectedPath = basePath + "/" + repository.path() + "/-/pipelines/" + id;
            return "https".equalsIgnoreCase(link.getScheme()) && base.getHost().equalsIgnoreCase(link.getHost())
                    && effectivePort(base) == effectivePort(link) && link.getRawUserInfo() == null
                    && link.getRawQuery() == null && link.getRawFragment() == null
                    && expectedPath.equals(link.getPath()) ? link.toASCIIString() : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static int effectivePort(URI uri) {
        return uri.getPort() == -1 ? 443 : uri.getPort();
    }

    private static boolean numericId(String id) {
        return id.matches("[1-9][0-9]*");
    }

    private static ForgeException unsupported() {
        return new ForgeException("pipeline_inputs_unsupported", HttpStatus.UNPROCESSABLE_CONTENT,
                GitLabMessages.format("forge.gitLabPipelineCommands.gitlab.did.not.expose.supported.pipeline.input.definitions.for"));
    }

    private static final class Gate implements ForgeRequestGate {
        private final Duration timeout;
        private long bytes;

        private Gate(Duration timeout) { this.timeout = timeout; }

        @Override public Duration beforeRequest() { return timeout; }

        @Override public void receivedBytes(long count) {
            bytes += count;
            if (bytes > MAX_RESPONSE_BYTES) throw GitLabErrors.budgetExceeded();
        }

        @Override public void acceptedObjects(int count) {}

        @Override public void check() {}
    }
}
