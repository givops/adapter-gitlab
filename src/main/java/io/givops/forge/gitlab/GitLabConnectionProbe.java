package io.givops.forge.gitlab;


import io.givops.catalog.ForgeProvider;
import io.givops.forge.port.ForgeAdapter.Access;
import io.givops.forge.admin.ApiCheckOperations;
import io.givops.forge.admin.ApiCheckOperations.Observation;
import io.givops.forge.model.ForgeException;
import io.givops.forge.port.ForgeApiProbe;
import io.givops.integrations.checks.CheckBudget;
import io.givops.forge.port.ForgeCheckInput;
import io.givops.integrations.checks.CheckOutcome;
import io.givops.forge.port.ForgeCheckInput.Scope;
import io.givops.integrations.checks.OperationStatus;
import java.net.URI;
import java.time.Clock;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.http.HttpStatus;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

class GitLabConnectionProbe implements ForgeApiProbe {
    private final GitLabTransport transport;
    private final ObjectReader json;
    private final Clock clock;

    GitLabConnectionProbe(GitLabTransport transport, JsonMapper mapper, Clock clock) {
        this.transport = transport;
        this.json = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION);
        this.clock = clock;
    }

    @Override
    public ForgeProvider provider() { return ForgeProvider.GITLAB; }

    @Override
    public void validate(ForgeCheckInput input) {
        new GitLabUrls(input.baseUrl());
        if (input.repository() != null) GitLabUrls.project(input.repository().externalId());
    }

    @Override
    public CheckOutcome probe(ForgeCheckInput input, CheckBudget budget) {
        var urls = new GitLabUrls(input.baseUrl());
        var operations = new ApiCheckOperations(clock.instant());
        String token = input.credentials().get("apiToken");
        if (input.scope() == Scope.FORGE_API) {
            operations.observe("metadata", GitLabMessages.format("forge.gitLabConnectionProbe.the.gitlab.metadata.endpoint.responded"),
                    () -> read(urls.metadata(), token, budget, true, node -> GitLabPayload.text(node, "version", true)));
        } else {
            String externalId = input.repository().externalId();
            String project = GitLabUrls.project(externalId);
            operations.observe("metadata", GitLabMessages.format("forge.gitLabConnectionProbe.the.project.metadata.matched.the.saved.project.identity"),
                    () -> read(urls.detail(project), token, budget, false, node -> {
                        GitLabPayload.requireObject(node);
                        String received = externalId.matches("[1-9][0-9]*") ? node.path("id").asText()
                                : GitLabPayload.text(node, "path_with_namespace", true);
                        if (!externalId.equalsIgnoreCase(received)) throw new ForgeException(
                                "gitlab_repository_identity_mismatch", HttpStatus.BAD_GATEWAY,
                                GitLabMessages.format("forge.gitLabConnectionProbe.the.gitlab.project.does.not.match.the.saved.repository"));
                    }));
            operations.observe("openMergeRequests", GitLabMessages.format("forge.gitLabConnectionProbe.the.first.page.of.open.merge.requests.was.readable"),
                    () -> read(urls.uri(project + "/merge_requests", Map.of("state", "opened"), 1, 1), token, budget,
                            false, GitLabConnectionProbe::requireOnePage));
            operations.observe("pipelineList", GitLabMessages.format("forge.gitLabConnectionProbe.the.first.page.of.project.pipelines.was.readable"),
                    () -> read(urls.uri(project + "/pipelines", Map.of(), 1, 1), token, budget,
                            false, GitLabConnectionProbe::requireOnePage));
        }
        return operations.outcome();
    }

    private Observation read(URI uri, String token, CheckBudget budget, boolean optional, Consumer<JsonNode> validate) {
        var response = transport.getForCheck(uri, token, budget);
        if (response.status() != 200) {
            var failure = GitLabErrors.remoteStatus(response.status());
            return new Observation(optional && response.status() == 404 ? OperationStatus.UNKNOWN : OperationStatus.FAILED,
                    failure.getCode(), response.headers(), response.status());
        }
        if (response.headers().firstValue("Content-Type").orElse("").split(";", 2)[0].strip().equalsIgnoreCase("text/html")) {
            throw GitLabErrors.invalidResponse().withHttpResponse(response.status(), "text/html");
        }
        try {
            JsonNode body = json.readTree(response.body());
            if (body == null || body.isMissingNode()) throw GitLabErrors.invalidResponse();
            validate.accept(body);
        } catch (JacksonException exception) {
            throw GitLabErrors.invalidResponse().withHttpResponse(response.status(), response.headers().firstValue("Content-Type").orElse(null));
        }
        return null;
    }

    private static void requireOnePage(JsonNode node) {
        if (node == null || !node.isArray() || node.size() > 1) throw GitLabErrors.invalidResponse();
    }
}
