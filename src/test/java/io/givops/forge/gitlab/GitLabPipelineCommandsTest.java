package io.givops.forge.gitlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import io.givops.catalog.ForgeProvider;
import io.givops.forge.port.ForgeAdapter.Access;
import io.givops.forge.command.PipelineCommandProvider.Repository;
import io.givops.forge.config.ForgeProperties;
import io.givops.forge.model.ForgeException;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class GitLabPipelineCommandsTest {
    private static final Repository REPOSITORY = new Repository(ForgeProvider.GITLAB,
            URI.create("https://gitlab.example.test/internal"), "42", "team/repo");
    private static final String REF = "refs/heads/release/next";
    private static final String SHA = "b".repeat(40);
    private static final Access CREDENTIAL = new Access("offline-test-token", io.givops.forge.model.ForgeConnectionSettings.Hosting.SELF_MANAGED);
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void queriesProjectInputSchemaAndPostsTypedInputsToCreatePipeline() throws Exception {
        var fixture = new GitLabTestFixture(request -> {
            if (request.uri().getPath().endsWith("/metadata")) return new GitLabTestFixture.Reply("{\"version\":\"19.0.0\"}");
            if (request.uri().getPath().endsWith("/graphql")) {
                return new GitLabTestFixture.Reply(spec("""
                        [{"name":"release","type":"BOOLEAN","description":null,"required":false,
                          "default":false,"options":null,"regex":null,"rules":null}]
                        """));
            }
            return new GitLabTestFixture.Reply(201, Map.of(), """
                    {"id":17,"project_id":42,"ref":"release/next","tag":false,"sha":"%s",
                     "web_url":"https://gitlab.example.test/internal/team/repo/-/pipelines/17"}
                    """.formatted(SHA));
        });
        var provider = commands(fixture, Duration.ofSeconds(2));
        var definitions = provider.definitions(REPOSITORY, CREDENTIAL, REF);
        assertThat(definitions).singleElement().satisfies(input -> {
            assertThat(input.name()).isEqualTo("release");
            assertThat(input.type()).isEqualTo("boolean");
            assertThat(input.defaultValue().booleanValue()).isFalse();
        });
        var result = provider.create(REPOSITORY, CREDENTIAL, REF, Map.of("release", json.readTree("true")));
        assertThat(result.errorCode()).isNull();
        assertThat(result.actualSha()).isEqualTo(SHA);
        assertThat(result.actualRef()).isEqualTo("release/next");
        assertThat(result.pipelineId()).isEqualTo("17");
        assertThat(result.webUrl()).endsWith("/team/repo/-/pipelines/17");
        assertThat(fixture.requests.stream().filter(r -> r.method().equals("POST")).toList()).hasSize(2).allSatisfy(request -> {
            assertThat(request.method()).isEqualTo("POST");
            assertThat(request.headers().firstValue("PRIVATE-TOKEN")).contains(CREDENTIAL.token());
            assertThat(request.headers().firstValue("Content-Type")).contains("application/json");
            assertThat(request.uri().getRawQuery()).isNull();
        });
        HttpRequest discovery = fixture.requests.get(1);
        assertThat(discovery.uri().getPath()).isEqualTo("/internal/api/graphql");
        assertThat(body(discovery).path("variables").path("ref").stringValue()).isEqualTo(REF);
        HttpRequest launch = fixture.requests.getLast();
        assertThat(launch.uri().getPath()).isEqualTo("/internal/api/v4/projects/42/pipeline");
        assertThat(body(launch)).isEqualTo(json.readTree("""
                {"ref":"refs/heads/release/next","inputs":{"release":true}}
                """));
    }

    @Test
    void refusesUnknownSchemaAndDynamicRulesInsteadOfClaimingNoInputs() {
        for (String reply : List.of(spec("null"), """
                {"errors":[{"message":"The provider response may contain private data."}]}
                """, spec("""
                [{"name":"target","type":"STRING","required":true,"default":null,"options":null,
                  "rules":[{"if":"$[[ inputs.deploy ]]"}]}]
                """))) {
            var fixture = new GitLabTestFixture(request -> new GitLabTestFixture.Reply(reply));
            assertThatThrownBy(() -> commands(fixture, Duration.ofSeconds(2)).definitions(REPOSITORY, CREDENTIAL, REF))
                    .isInstanceOfSatisfying(ForgeException.class, failure -> {
                        assertThat(failure.getCode()).isEqualTo("pipeline_inputs_unsupported");
                        assertThat(failure.getMessage()).doesNotContain("private data");
                    });
        }
    }

    @Test
    void treatsTimeoutAsPossiblyAccepted() {
        var fixture = new GitLabTestFixture(request -> new GitLabTestFixture.Reply("unused"));
        doAnswer(call -> new CompletableFuture<>()).when(fixture.client).<byte[]>sendAsync(any(), any());
        var result = commands(fixture, Duration.ofMillis(10)).create(REPOSITORY, CREDENTIAL, REF, Map.of());
        assertThat(result.errorCode()).isEqualTo("gitlab_timeout");
        assertThat(result.possiblyAccepted()).isTrue();
    }

    @Test
    void gitLab17BeforeInputsDoesNotRequestUnsupportedGraphqlOrMutate() {
        var fixture = new GitLabTestFixture(request -> new GitLabTestFixture.Reply(GitLabTicketsTest.resource("metadata17")));
        var provider = commands(fixture, Duration.ofSeconds(2));
        assertThatThrownBy(() -> provider.definitions(REPOSITORY, CREDENTIAL, REF))
                .isInstanceOfSatisfying(ForgeException.class, failure -> assertThat(failure.getCode()).isEqualTo("pipeline_inputs_unsupported"));
        var result = provider.create(REPOSITORY, CREDENTIAL, REF, Map.of("target", json.readTree("true")));
        assertThat(result.errorCode()).isEqualTo("pipeline_inputs_unsupported");
        assertThat(result.possiblyAccepted()).isFalse();
        assertThat(fixture.requests).allSatisfy(request -> assertThat(request.uri().getPath()).endsWith("/metadata"));
    }

    @Test
    void metadataAbsenceFallsBackToVersionButAuthorizationFailureDoesNot() {
        var fixture = new GitLabTestFixture(request -> request.uri().getPath().endsWith("/metadata")
                ? new GitLabTestFixture.Reply(404, Map.of(), "absent")
                : new GitLabTestFixture.Reply(GitLabTicketsTest.resource("metadata19")));
        var profile = GitLabVersions.read(new GitLabTransport(fixture.client, 262_144), new GitLabUrls(REPOSITORY.baseUrl()),
                CREDENTIAL.token(), new GitLabTestFixture.Gate(), json.reader());
        assertThat(profile.version()).isEqualTo("19.0.0-ee"); assertThat(profile.endpoint()).isEqualTo("version");
        assertThat(fixture.requests).hasSize(2);
        var forbidden = new GitLabTestFixture(request -> new GitLabTestFixture.Reply(403, Map.of(), "forbidden"));
        assertThatThrownBy(() -> GitLabVersions.read(new GitLabTransport(forbidden.client, 262_144), new GitLabUrls(REPOSITORY.baseUrl()),
                CREDENTIAL.token(), new GitLabTestFixture.Gate(), json.reader())).isInstanceOf(ForgeException.class);
        assertThat(forbidden.requests).hasSize(1);
    }

    @Test
    void pipelineInputsBoundaryIsDocumentedMinorVersionNotAnInventedMajor19Threshold() {
        assertThat(new GitLabVersions.Profile("17.9.9-ee", true, null, "metadata").beforePipelineInputs()).isTrue();
        assertThat(new GitLabVersions.Profile("17.10.0-ee", true, null, "metadata").beforePipelineInputs()).isFalse();
        assertThat(new GitLabVersions.Profile("18.1.0", true, null, "metadata").beforePipelineInputs()).isFalse();
        assertThat(new GitLabVersions.Profile("19.0.0", true, null, "metadata").beforePipelineInputs()).isFalse();
    }

    private GitLabPipelineCommands commands(GitLabTestFixture fixture, Duration timeout) {
        var properties = new ForgeProperties(Duration.ofSeconds(120), timeout, 100, 10, 262_144, 262_144,
                50, 50, 500, 1000, 4, Duration.ofMinutes(5), Duration.ofMinutes(15), 50);
        return new GitLabPipelineCommands(new GitLabTransport(fixture.client, properties.maxResponseBytes()), json,
                properties);
    }

    private static String spec(String specs) {
        return """
                {"data":{"project":{"id":"gid://gitlab/Project/42","fullPath":"team/repo",
                                     "ciPipelineCreationInputs":%s}}}
                """.formatted(specs);
    }

    private JsonNode body(HttpRequest request) throws Exception {
        var bytes = new ByteArrayOutputStream();
        var result = new CompletableFuture<byte[]>();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            @Override public void onNext(ByteBuffer buffer) {
                byte[] part = new byte[buffer.remaining()];
                buffer.get(part);
                bytes.writeBytes(part);
            }
            @Override public void onError(Throwable error) { result.completeExceptionally(error); }
            @Override public void onComplete() { result.complete(bytes.toByteArray()); }
        });
        return json.readTree(result.get(2, TimeUnit.SECONDS));
    }
}
