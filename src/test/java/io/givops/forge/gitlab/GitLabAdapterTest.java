package io.givops.forge.gitlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.givops.catalog.ForgeProvider;
import io.givops.forge.config.ForgeProperties;
import io.givops.forge.gitlab.GitLabProperties.Hosting;
import io.givops.forge.gitlab.GitLabProperties.Instance;
import io.givops.forge.gitlab.GitLabTestFixture.Gate;
import io.givops.forge.gitlab.GitLabTestFixture.Reply;
import io.givops.forge.model.ForgeConnectionSettings;
import io.givops.forge.model.ForgeException;
import io.givops.forge.model.ForgeModels.Branch;
import io.givops.forge.model.ForgeModels.CachedAssociations;
import io.givops.forge.model.ForgeModels.CapabilityState;
import io.givops.forge.model.ForgeModels.ChangeRequest;
import io.givops.forge.model.ForgeModels.ChangeRequestKey;
import io.givops.forge.model.ForgeModels.PipelineKey;
import io.givops.forge.model.ForgeModels.RefreshInput;
import io.givops.forge.model.ForgeModels.Selection;
import io.givops.forge.model.ForgeModels.Settings;
import io.givops.forge.model.ForgeModels.Source;
import io.givops.forge.model.ForgeModels.Snapshot;
import io.givops.forge.port.ForgeAdapter.Access;
import io.givops.forge.port.ForgeRequestGate;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class GitLabAdapterTest {
    private static final String HOST = "https://git.example.test";
    private static final URI BASE = URI.create(HOST + "/git/");
    private static final String API = "/git/api/v4";
    private static final String PROJECT = API + "/projects/team%2Fservice";
    private static final String SHA = "a".repeat(40);
    private static final String OTHER_SHA = "b".repeat(40);
    private static final String TOKEN = "synthetic-test-token";
    private static final String METADATA_17 = "{\"version\":\"17.0.0-ee\",\"enterprise\":true}";
    private static final Instant NOW = Instant.parse("2026-11-07T08:00:00Z");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final UUID REPOSITORY_ID = UUID.randomUUID();
    private static final UUID FORGE_ID = UUID.randomUUID();

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void readsAllStatesAcrossPagesAndKeepsExactBranchAndCanonicalMrPipelinesDistinct(boolean authenticated) {
        String token = authenticated ? TOKEN : null;
        String draft = """
                ,"draft":true,"source_project_id":25,"target_project_id":10,
                "labels":["promotion","release::production"],"author":{"id":8,"username":"dev"},
                "assignees":[{"id":9}],"reviewers":[{"id":12,"name":"Reviewer"}]
                """;
        var fixture = new GitLabTestFixture(request -> {
            assertThat(request.headers().firstValue("PRIVATE-TOKEN").orElse(null)).isEqualTo(token);
            assertThat(request.headers().firstValue("Authorization")).isEmpty();
            String path = request.uri().getRawPath();
            String query = request.uri().getRawQuery();
            if (path.equals(API + "/metadata")) {
                return authenticated ? new Reply(METADATA_17) : new Reply(401, Map.of(), "Unauthorized");
            }
            if (path.equals(PROJECT + "/merge_requests")) {
                assertThat(query).contains("state=opened");
                if (query.endsWith("page=1")) {
                    return next("[" + mr(10, 1, "opened", "Current draft", draft) + "]",
                            HOST + PROJECT + "/merge_requests?state=opened&per_page=100&page=2");
                }
                return new Reply("[" + mr(10, 2, "opened", "Second", ",\"head_pipeline\":"
                        + pipeline(25, 123, "refs/merge-requests/2/head", SHA)) + "]");
            }
            if (path.equals(PROJECT + "/repository/commits/" + SHA + "/merge_requests")) {
                assertThat(query).doesNotContain("state=");
                if (query.endsWith("page=1")) {
                    return new Reply(200, Map.of("X-Next-Page", List.of("2")), "["
                            + mr(10, 1, "closed", "Conflicting history", "")
                            + "," + mr(25, 1, "merged", "Fork merge", ",\"squash_commit_sha\":\"" + SHA + "\"") + "]");
                }
                return new Reply("[" + mr(30, 7, "locked", "Other project", ",\"head_pipeline\":null") + "]");
            }
            if (path.equals(PROJECT + "/repository/commits/" + OTHER_SHA + "/merge_requests")) {
                return new Reply("[]");
            }
            if (path.equals(API + "/projects/10/merge_requests/1")) {
                return new Reply(mr(10, 1, "opened", "Current draft",
                        draft + ",\"detailed_merge_status\":\"discussions_not_resolved\",\"sha\":\"" + SHA + "\",\"head_pipeline\":"
                                + pipeline(25, 21, "refs/merge-requests/1/merge", OTHER_SHA)));
            }
            if (path.equals(API + "/projects/25/merge_requests/1")) {
                return new Reply(mr(25, 1, "merged", "Fork merge",
                        ",\"squash_commit_sha\":\"" + SHA + "\",\"head_pipeline\":null"));
            }
            if (path.equals(PROJECT + "/pipelines")) {
                assertThat(query).contains("order_by=id", "sort=desc", "per_page=20", "page=1");
                if (query.contains("ref=release%2Fstable%2Bbuild")) {
                    assertThat(query).contains("sha=" + SHA);
                    return next("[" + pipeline(10, 55, "release/stable+build", SHA) + "]",
                            HOST + PROJECT + "/pipelines?page=2");
                }
                assertThat(query).contains("ref=old", "sha=" + OTHER_SHA);
                return new Reply("[]");
            }
            if (path.equals(PROJECT + "/pipelines/55")) {
                return new Reply(pipelineDetail(10, 55, "release/stable+build", SHA, false));
            }
            if (path.equals(API + "/projects/10/merge_requests/1/discussions")) {
                if (query.endsWith("page=1")) {
                    return new Reply(200, Map.of("X-Next-Page", List.of("2")), """
                            [{"id":"first","individual_note":false,"notes":[
                              {"system":false,"resolvable":true,"resolved":false},
                              {"system":false,"resolvable":true,"resolved":false}]},
                             {"id":"resolved","individual_note":false,"notes":[
                              {"system":false,"resolvable":true,"resolved":true}]}]
                            """);
                }
                return new Reply("""
                        [{"id":"second","individual_note":false,"notes":[
                           {"system":false,"resolvable":true,"resolved":false}]},
                         {"id":"system","individual_note":false,"notes":[{"system":true}]},
                         {"id":"comment","individual_note":true,"notes":[
                           {"system":false,"resolvable":true,"resolved":false}]}]
                        """);
            }
            if (path.equals(API + "/projects/10/merge_requests/1/approvals")) {
                return new Reply("{\"approved_by\":[{\"user\":{\"id\":12}}]}");
            }
            if (path.equals(API + "/projects/10/merge_requests/2/discussions")) {
                return new Reply(403, Map.of(), "Forbidden");
            }
            if (path.equals(API + "/projects/10/merge_requests/2/approvals")) {
                return new Reply(404, Map.of(), "Unavailable");
            }
            throw new AssertionError("Unexpected request: " + request.uri());
        });
        var gate = new Gate();
        var adapter = authenticated ? adapter(fixture) : adapter(fixture, 20, Map.of());
        var snapshot = adapter.read(input(BASE, true, List.of(SHA, OTHER_SHA),
                List.of(new Branch("refs/heads/release/stable+build", SHA),
                        new Branch("refs/heads/old", OTHER_SHA)), Map.of()), gate);

        assertThat(snapshot.capabilities().version()).isEqualTo(authenticated ? "17.0.0-ee" : null);
        assertThat(snapshot.capabilities().edition()).isEqualTo(authenticated ? "EE" : "UNKNOWN");
        assertThat(snapshot.capabilities().hosting())
                .isEqualTo(authenticated ? Hosting.SELF_MANAGED.name() : Hosting.UNKNOWN.name());
        assertThat(snapshot.capabilities().operations().get("metadata").state())
                .isEqualTo(authenticated ? CapabilityState.AVAILABLE : CapabilityState.FORBIDDEN);
        assertThat(snapshot.capabilities().tier()).isEqualTo("UNKNOWN");
        assertThat(snapshot.changeRequests()).extracting(ChangeRequest::key)
                .containsExactly(new ChangeRequestKey("10", "1"), new ChangeRequestKey("10", "2"), new ChangeRequestKey("25", "1"), new ChangeRequestKey("30", "7"));
        var open = snapshot.changeRequests().getFirst();
        assertThat(open.state()).isEqualTo("opened");
        assertThat(open.title()).isEqualTo("Current draft");
        assertThat(open.draft()).isTrue();
        assertThat(open.labels()).containsExactly("promotion", "release::production");
        assertThat(open.author().username()).isEqualTo("dev");
        assertThat(open.assignees()).hasSize(1);
        assertThat(open.reviewers()).hasSize(1);
        assertThat(open.headPipeline().key()).isEqualTo(new PipelineKey("25", "21"));
        assertThat(open.sha()).isEqualTo(SHA);
        assertThat(open.headPipeline().sha()).isEqualTo(OTHER_SHA);
        assertThat(open.headPipeline().status()).isEqualTo("provider_future_status");
        assertThat(open.taskSignals()).satisfies(signals -> {
            assertThat(signals.unresolvedDiscussionCount()).isEqualTo(2);
            assertThat(signals.approvalsKnown()).isTrue();
            assertThat(signals.approvedProviderIds()).containsExactly("12");
            assertThat(signals.mergeStatus()).isEqualTo("discussions_not_resolved");
        });
        assertThat(snapshot.changeRequests().get(1).headPipeline().key()).isEqualTo(new PipelineKey("25", "123"));
        assertThat(snapshot.changeRequests().get(1).taskSignals()).satisfies(signals -> {
            assertThat(signals.unresolvedDiscussionCount()).isNull();
            assertThat(signals.approvalsKnown()).isFalse();
        });
        assertThat(snapshot.changeRequests().get(2).taskSignals()).isNull();
        assertThat(snapshot.changeRequests().get(2).squashCommitSha()).isEqualTo(SHA);
        assertThat(snapshot.associations().getFirst().changeRequests())
                .containsExactly(new ChangeRequestKey("10", "1"), new ChangeRequestKey("25", "1"), new ChangeRequestKey("30", "7"));
        assertThat(snapshot.associations().getLast().changeRequests()).isEmpty();
        assertThat(snapshot.branchPipelines().getFirst().pipeline().key()).isEqualTo(new PipelineKey("10", "55"));
        assertThat(snapshot.branchPipelines().getLast().pipeline()).isNull();
        assertThat(fixture.requests).hasSize(16);
        assertThat(gate.requests).isEqualTo(fixture.requests.size());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" \t "})
    void preservesConfiguredHostingWhenTheTokenIsAbsent(String token) {
        var fixture = metadataFixture(new Reply(METADATA_17));
        var adapter = adapter(fixture, 20, Map.of("example", new Instance(token, Hosting.DEDICATED)));
        var snapshot = adapter.read(input(BASE, true, List.of(), List.of(), Map.of()), new Gate());

        assertThat(snapshot.capabilities().hosting()).isEqualTo(Hosting.DEDICATED.name());
        assertThat(fixture.requests).hasSize(2).allSatisfy(request -> {
            assertThat(request.headers().firstValue("PRIVATE-TOKEN")).isEmpty();
            assertThat(request.headers().firstValue("Authorization")).isEmpty();
        });
    }

    @Test
    void reusesCachedObservationsAndNeverReadsOutsideSelectedHistory() {
        var old = NOW.minusSeconds(30);
        var stale = GitLabPayload.changeRequest(JSON.readTree(mr(10, 1, "closed", "Cached", "")), old);
        var historical = GitLabPayload.changeRequest(JSON.readTree(mr(10, 2, "merged", "Historical", "")), old);
        var fixture = new GitLabTestFixture(request -> switch (request.uri().getRawPath()) {
            case API + "/metadata" -> new Reply(METADATA_17);
            case PROJECT + "/merge_requests" -> new Reply("[" + mr(10, 1, "opened", "Fresh",
                    ",\"head_pipeline\":" + pipeline(10, 3, "main", SHA)) + "]");
            case API + "/projects/10/merge_requests/1/discussions" -> new Reply("[]");
            case API + "/projects/10/merge_requests/1/approvals" -> new Reply("{\"approved_by\":[]}");
            default -> throw new AssertionError("Unexpected remote lookup");
        });
        var snapshot = adapter(fixture).read(input(BASE, true, List.of(SHA), List.of(), Map.of(
                SHA, new CachedAssociations(List.of(stale, historical), old),
                OTHER_SHA, new CachedAssociations(List.of(stale), old))), new Gate());

        assertThat(snapshot.associations()).singleElement().satisfies(association -> {
            assertThat(association.sha()).isEqualTo(SHA);
            assertThat(association.observedAt()).isEqualTo(old);
        });
        assertThat(snapshot.changeRequests()).extracting(ChangeRequest::title).containsExactly("Fresh", "Historical");
        assertThat(snapshot.changeRequests().getLast().observedAt()).isEqualTo(old);
        assertThat(snapshot.capabilities().operations().get("commitAssociations").state())
                .isEqualTo(CapabilityState.UNKNOWN);
        assertThat(fixture.requests).hasSize(4);
        assertThat(snapshot.changeRequests().getFirst().taskSignals()).satisfies(signals -> {
            assertThat(signals.unresolvedDiscussionCount()).isZero();
            assertThat(signals.approvalsKnown()).isTrue();
            assertThat(signals.approvedProviderIds()).isEmpty();
        });
    }

    @Test
    void refusesToTreatMissingResolutionEvidenceAsZeroThreads() {
        var fixture = new GitLabTestFixture(request -> switch (request.uri().getRawPath()) {
            case API + "/metadata" -> new Reply(METADATA_17);
            case PROJECT + "/merge_requests" ->
                    new Reply("[" + mr(10, 1, "opened", "Observed MR", ",\"head_pipeline\":null") + "]");
            case API + "/projects/10/merge_requests/1/discussions" -> new Reply("""
                    [{"id":"unknown","individual_note":false,"notes":[{"system":false,"resolved":false}]}]
                    """);
            default -> throw new AssertionError("Unexpected request");
        });
        assertCode(() -> adapter(fixture).read(input(BASE, true, List.of(), List.of(), Map.of()), new Gate()),
                "gitlab_invalid_response");
    }

    @ParameterizedTest
    @MethodSource("requiredAccessFailures")
    void rejectsAnonymousAccessFailuresOnRequiredEndpoints(String failedPath, int status, String code) {
        var replies = Map.of(
                API + "/metadata", new Reply(401, Map.of(), "Unauthorized"),
                PROJECT + "/merge_requests", new Reply("[" + mr(10, 1, "opened", "Current", "") + "]"),
                PROJECT + "/repository/commits/" + SHA + "/merge_requests", new Reply("[]"),
                API + "/projects/10/merge_requests/1", new Reply(mr(10, 1, "opened", "Current", ",\"head_pipeline\":null")),
                PROJECT + "/pipelines", new Reply("[" + pipeline(10, 55, "main", SHA) + "]"));
        var fixture = new GitLabTestFixture(request -> {
            String path = request.uri().getRawPath();
            if (path.equals(failedPath)) {
                return new Reply(status, Map.of(), TOKEN);
            }
            assertThat(replies).containsKey(path);
            return replies.get(path);
        });
        var input = input(BASE, true, List.of(SHA), List.of(new Branch("refs/heads/main", SHA)), Map.of());
        assertThatThrownBy(() -> adapter(fixture, 20, Map.of()).read(input, new Gate()))
                .isInstanceOfSatisfying(ForgeException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(code);
                    assertThat(error.getMessage()).doesNotContain(TOKEN, HOST);
                    assertThat(error.getCause()).isNull();
                });
        assertThat(fixture.requests).extracting(request -> request.uri().getRawPath())
                .doesNotHaveDuplicates().last().isEqualTo(failedPath);
    }

    private static Stream<Arguments> requiredAccessFailures() {
        return Stream.of(PROJECT + "/merge_requests", PROJECT + "/repository/commits/" + SHA + "/merge_requests",
                API + "/projects/10/merge_requests/1", PROJECT + "/pipelines", PROJECT + "/pipelines/55")
                .flatMap(path -> Stream.of(Arguments.of(path, 401, "gitlab_unauthorized"),
                        Arguments.of(path, 403, "gitlab_forbidden")));
    }

    @Test
    void rejectsInvalidConfiguredTokenBeforeOutboundAdmission() {
        var fixture = metadataFixture(new Reply(METADATA_17));
        var adapter = adapter(fixture, 20, Map.of("example", new Instance(TOKEN + "\ninvalid", Hosting.SELF_MANAGED)));
        var gate = new Gate();
        assertCode(() -> adapter.read(input(BASE, true, List.of(), List.of(), Map.of()), gate),
                "gitlab_configuration_invalid");
        assertThat(fixture.requests).isEmpty();
        assertThat(gate.requests).isZero();
    }

    @Test
    void newerInstancePlanAddsEvidenceWithoutChangingReadsOrResults() {
        var baseline = licenseFixture(METADATA_17);
        var newer = licenseFixture("""
                {"version":"19.6.0-ee","enterprise":true,"plan":"premium"}
                """);
        var namespace = licenseFixture("""
                {"version":"19.6.0-ee","enterprise":true,"plan":null}
                """);
        var input = input(BASE, true, List.of(), List.of(), Map.of());
        var older = adapter(baseline).read(input, new Gate());
        var licensed = adapter(newer).read(input, new Gate());
        var unknown = adapter(namespace).read(input, new Gate());
        assertThat(licensed.capabilities().tier()).isEqualTo("premium");
        assertThat(licensed.capabilities().operations().get("instanceLicensePlan").evidence())
                .isEqualTo("metadata_instance_plan");
        assertThat(unknown.capabilities().tier()).isEqualTo("UNKNOWN");
        assertThat(older.changeRequests()).isEqualTo(licensed.changeRequests()).isEqualTo(unknown.changeRequests());
        assertThat(older.changeRequests()).extracting(ChangeRequest::key).containsExactly(new ChangeRequestKey("10", "3"));
        assertThat(older.associations()).isEqualTo(licensed.associations()).isEqualTo(unknown.associations());
        assertThat(baseline.requests).extracting(request -> request.uri())
                .containsExactlyElementsOf(newer.requests.stream().map(request -> request.uri()).toList());
    }

    @ParameterizedTest
    @CsvSource({"401,FORBIDDEN", "403,FORBIDDEN", "404,UNKNOWN", "429,TEMPORARILY_UNAVAILABLE",
            "503,TEMPORARILY_UNAVAILABLE"})
    void unavailableOptionalMetadataDoesNotClaimUnsupported(int status, CapabilityState expected) {
        var fixture = metadataFixture(new Reply(status, Map.of(), "Untrusted provider error body"));
        var snapshot = adapter(fixture).read(input(BASE, true, List.of(), List.of(), Map.of()), new Gate());
        assertThat(snapshot.capabilities().operations().get("metadata").state()).isEqualTo(expected);
        assertThat(snapshot.capabilities().version()).isNull();
        assertThat(snapshot.capabilities().tier()).isEqualTo("UNKNOWN");
        assertThat(fixture.requests).hasSize(status == 404 ? 3 : 2);
    }

    @ParameterizedTest
    @CsvSource({"401,gitlab_unauthorized", "403,gitlab_forbidden", "404,gitlab_not_found",
            "429,gitlab_rate_limited", "503,gitlab_unavailable", "302,gitlab_redirect"})
    void requiredRequestFailuresAreSanitizedAndNeverReturnedAsEmpty(int status, String code) {
        var fixture = new GitLabTestFixture(request -> request.uri().getRawPath().endsWith("/metadata")
                ? new Reply(METADATA_17)
                : new Reply(status, Map.of("Location", List.of("https://other.example/" + TOKEN)), TOKEN));
        assertThatThrownBy(() -> adapter(fixture).read(input(BASE, true, List.of(), List.of(), Map.of()), new Gate()))
                .isInstanceOfSatisfying(ForgeException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(code);
                    assertThat(error.getMessage()).doesNotContain(TOKEN, "other.example", HOST);
                    assertThat(error.getCause()).isNull();
                });
        assertThat(fixture.requests).hasSize(2).allSatisfy(request ->
                assertThat(request.headers().firstValue("PRIVATE-TOKEN")).contains(TOKEN));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "[] {}", "[null]", "[{\"project_id\":10,\"iid\":1}]", "broken"})
    void malformedRequiredCollectionFails(String body) {
        var fixture = new GitLabTestFixture(request -> request.uri().getRawPath().endsWith("/metadata")
                ? new Reply(METADATA_17) : new Reply(body));
        assertCode(() -> adapter(fixture).read(input(BASE, true, List.of(), List.of(), Map.of()), new Gate()),
                "gitlab_invalid_response");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://other.example/git/api/v4/projects/team%2Fservice/merge_requests?page=2",
            "https://git.example.test:8443/git/api/v4/projects/team%2Fservice/merge_requests?page=2",
            "https://user@git.example.test/git/api/v4/projects/team%2Fservice/merge_requests?page=2",
            "/git/api/v4/projects/team%2Fservice/pipelines?page=2",
            "/git/api/v4/projects/team/service/merge_requests?page=2",
            "/git/api/v4/projects/team%2Fservice/../team%2Fservice/merge_requests?page=2",
            "?page=2&state=all",
            "?page=3",
            "?page=1"
    })
    void rejectsUntrustedOrAmbiguousNextLinksWithoutSendingCredentials(String link) {
        var fixture = new GitLabTestFixture(request -> request.uri().getRawPath().endsWith("/metadata")
                ? new Reply(METADATA_17) : next("[" + mr(10, 1, "opened", "First", "") + "]", link));
        assertCode(() -> adapter(fixture).read(input(BASE, true, List.of(), List.of(), Map.of()), new Gate()),
                "gitlab_invalid_pagination");
        assertThat(fixture.requests).hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://git.example.test/git", "https://git.example.test/git%2Fprefix",
            "https://git.example.test/git%5Cprefix", "https://git.example.test/%252e%252e",
            "https://git.example.test/git/../prefix", "https://git.example.test/git//prefix",
            "https://git.example.test/git?x=1", "https://user@git.example.test/git"})
    void rejectsInvalidConfiguredBaseBeforeOutboundAdmission(String base) {
        var fixture = metadataFixture(new Reply(METADATA_17));
        var gate = new Gate();
        assertCode(() -> adapter(fixture).read(input(URI.create(base), true, List.of(), List.of(), Map.of()), gate),
                "gitlab_configuration_invalid");
        assertThat(fixture.requests).isEmpty();
        assertThat(gate.requests).isZero();
    }

    @Test
    void disabledAndDeniedAdmissionMakeNoRequests() {
        var fixture = metadataFixture(new Reply(METADATA_17));
        var adapter = adapter(fixture);
        assertCode(() -> adapter.read(input(BASE, false, List.of(), List.of(), Map.of()), new Gate()), "forge_disabled");
        var gate = new Gate() {
            @Override
            public Duration beforeRequest() {
                throw GitLabErrors.budgetExceeded();
            }
        };
        assertCode(() -> adapter.read(input(BASE, true, List.of(), List.of(), Map.of()), gate), "forge_budget_exhausted");
        assertThat(fixture.requests).isEmpty();
    }

    @Test
    void pageLimitDoesNotPublishAnIncompleteCollection() {
        var fixture = new GitLabTestFixture(request -> request.uri().getRawPath().endsWith("/metadata")
                ? new Reply(METADATA_17) : next("[" + mr(10, 1, "opened", "First", "") + "]", "?page=2"));
        assertCode(() -> adapter(fixture, 1).read(input(BASE, true, List.of(), List.of(), Map.of()), new Gate()),
                "forge_budget_exhausted");
        assertThat(fixture.requests).hasSize(2);
    }

    @Test
    void exactPipelineReadUsesOnlyScopedPipelineEndpointsAndObservesCompleteTagOnlyAbsence() {
        var fixture = new GitLabTestFixture(request -> switch (request.uri().getRawPath()) {
            case PROJECT + "/pipelines" -> {
                assertThat(request.uri().getQuery()).contains("ref=release/next", "sha=" + OTHER_SHA);
                yield new Reply("[" + pipeline(10, 22, "release/next", OTHER_SHA) + "]");
            }
            case PROJECT + "/pipelines/22" -> new Reply(pipelineDetail(10, 22, "release/next", OTHER_SHA, true));
            default -> throw new AssertionError("Unexpected request: " + request.uri());
        });
        var limits = new ForgeProperties(Duration.ofSeconds(120), Duration.ofSeconds(10),
                300, 20, 2_097_152, 16_777_216, 500, 200, 5000, 10000, 4,
                Duration.ofMinutes(5), Duration.ofMinutes(15), 50);
        var adapter = new GitLabAdapter(limits, new GitLabTransport(fixture.client, limits.maxResponseBytes()), JSON,
                Clock.fixed(NOW, ZoneOffset.UTC));
        var input = input(BASE, true, List.of(), List.of(), Map.of());
        var observed = adapter.readBranchPipeline(input.source(), input.settings(),
                new Branch("refs/heads/release/next", OTHER_SHA), new Gate(),
                new Access(TOKEN, ForgeConnectionSettings.Hosting.SELF_MANAGED));

        assertThat(observed.sha()).isEqualTo(OTHER_SHA);
        assertThat(observed.pipeline()).isNull();
        assertThat(observed.pipelineCoverage().state().name()).isEqualTo("OBSERVED");
        assertThat(fixture.requests).hasSize(2);
    }

    @Test
    void rejectsPipelineForAChangedBranchHead() {
        var fixture = new GitLabTestFixture(request -> switch (request.uri().getRawPath()) {
            case API + "/metadata" -> new Reply(METADATA_17);
            case PROJECT + "/merge_requests" -> new Reply("[]");
            case PROJECT + "/pipelines" -> new Reply("[" + pipeline(10, 1, "main", OTHER_SHA) + "]");
            default -> throw new AssertionError("Unexpected request");
        });
        assertCode(() -> adapter(fixture).read(input(BASE, true, List.of(),
                List.of(new Branch("refs/heads/main", SHA)), Map.of()), new Gate()), "gitlab_invalid_response");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void tagPipelinesDoNotStandInForBranchPipelinesWithSameRefAndSha(boolean allTags) {
        var fixture = new GitLabTestFixture(request -> switch (request.uri().getRawPath()) {
            case API + "/metadata" -> new Reply(METADATA_17);
            case PROJECT + "/merge_requests" -> new Reply("[]");
            case PROJECT + "/pipelines" -> {
                assertThat(request.uri().getRawQuery()).contains("ref=main", "sha=" + SHA);
                if (request.uri().getRawQuery().endsWith("page=1")) {
                    yield next("[" + pipeline(10, 22, "main", SHA) + "]", "?page=2");
                }
                yield new Reply("[" + pipeline(10, 21, "main", SHA) + "]");
            }
            case PROJECT + "/pipelines/22" -> new Reply(pipelineDetail(10, 22, "main", SHA, true));
            case PROJECT + "/pipelines/21" -> new Reply(pipelineDetail(10, 21, "main", SHA, allTags));
            default -> throw new AssertionError("Unexpected request");
        });
        var snapshot = adapter(fixture).read(input(BASE, true, List.of(),
                List.of(new Branch("refs/heads/main", SHA)), Map.of()), new Gate());
        var result = snapshot.branchPipelines().getFirst().pipeline();
        if (allTags) {
            assertThat(result).isNull();
        } else {
            assertThat(result.key()).isEqualTo(new PipelineKey("10", "21"));
        }
        assertThat(fixture.requests).hasSize(6);
    }

    @Test
    void branchCandidateMustBeVerifiedByMatchingDetailWithTagFlag() {
        var fixture = new GitLabTestFixture(request -> switch (request.uri().getRawPath()) {
            case API + "/metadata" -> new Reply(METADATA_17);
            case PROJECT + "/merge_requests" -> new Reply("[]");
            case PROJECT + "/pipelines" -> new Reply("[" + pipeline(10, 22, "main", SHA) + "]");
            case PROJECT + "/pipelines/22" -> new Reply(pipeline(10, 22, "main", SHA));
            default -> throw new AssertionError("Unexpected request");
        });
        assertCode(() -> adapter(fixture).read(input(BASE, true, List.of(),
                List.of(new Branch("refs/heads/main", SHA)), Map.of()), new Gate()), "gitlab_invalid_response");
    }

    @Test
    void detailCanObserveAnMrClosingAndAnExplicitlyAbsentHeadPipeline() {
        var fixture = new GitLabTestFixture(request -> switch (request.uri().getRawPath()) {
            case API + "/metadata" -> new Reply(METADATA_17);
            case PROJECT + "/merge_requests" -> new Reply("[" + mr(10, 1, "opened", "Open", "") + "]");
            case API + "/projects/10/merge_requests/1" ->
                    new Reply(mr(10, 1, "closed", "Recently closed", ",\"head_pipeline\":null"));
            default -> throw new AssertionError("Unexpected request");
        });
        var snapshot = adapter(fixture).read(input(BASE, true, List.of(), List.of(), Map.of()), new Gate());
        assertThat(snapshot.changeRequests()).singleElement().satisfies(mr -> {
            assertThat(mr.state()).isEqualTo("closed");
            assertThat(mr.headPipeline()).isNull();
        });
        assertThat(fixture.requests).hasSize(3);
    }

    @ParameterizedTest
    @CsvSource({"10,1,''", "25,1,',\"head_pipeline\":null'", "10,2,',\"head_pipeline\":null'"})
    void detailMustExposeHeadPipelineForTheRequestedMr(long project, long iid, String additionalFields) {
        var fixture = new GitLabTestFixture(request -> switch (request.uri().getRawPath()) {
            case API + "/metadata" -> new Reply(METADATA_17);
            case PROJECT + "/merge_requests" -> new Reply("[" + mr(10, 1, "opened", "Open", "") + "]");
            case API + "/projects/10/merge_requests/1" ->
                    new Reply(mr(project, iid, "opened", "Detail", additionalFields));
            default -> throw new AssertionError("Unexpected request");
        });
        assertCode(() -> adapter(fixture).read(input(BASE, true, List.of(), List.of(), Map.of()), new Gate()),
                "gitlab_invalid_response");
    }

    private static GitLabTestFixture metadataFixture(Reply metadata) {
        return new GitLabTestFixture(request -> switch (request.uri().getRawPath()) {
            case API + "/metadata", API + "/version" -> metadata;
            case PROJECT + "/merge_requests" -> new Reply("[]");
            default -> throw new AssertionError("Unexpected request");
        });
    }

    private static GitLabTestFixture licenseFixture(String metadata) {
        return new GitLabTestFixture(request -> switch (request.uri().getRawPath()) {
            case API + "/metadata" -> new Reply(metadata);
            case PROJECT + "/merge_requests" ->
                    new Reply("[" + mr(10, 3, "opened", "Observed MR", ",\"head_pipeline\":null") + "]");
            case API + "/projects/10/merge_requests/3/discussions" -> new Reply("[]");
            case API + "/projects/10/merge_requests/3/approvals" -> new Reply("{\"approved_by\":[]}");
            default -> throw new AssertionError("Unexpected request");
        });
    }

    private static FixtureAdapter adapter(GitLabTestFixture fixture) {
        return adapter(fixture, 20);
    }

    private static FixtureAdapter adapter(GitLabTestFixture fixture, int maxPages) {
        return adapter(fixture, maxPages, Map.of("example", new Instance(TOKEN, Hosting.SELF_MANAGED)));
    }

    private static FixtureAdapter adapter(GitLabTestFixture fixture, int maxPages, Map<String, Instance> instances) {
        var limits = new ForgeProperties(Duration.ofSeconds(120), Duration.ofSeconds(10),
                300, maxPages, 2_097_152, 16_777_216, 500, 200, 5000, 10000, 4,
                Duration.ofMinutes(5), Duration.ofMinutes(15), 50);
        var adapter = new GitLabAdapter(limits, new GitLabTransport(fixture.client, limits.maxResponseBytes()), JSON,
                Clock.fixed(NOW, ZoneOffset.UTC));
        var configured = instances.getOrDefault("example", new Instance(null, Hosting.UNKNOWN));
        var access = new Access(configured.token(), ForgeConnectionSettings.Hosting.valueOf(configured.hosting().name()));
        return (input, gate) -> adapter.read(input, gate, access);
    }

    private interface FixtureAdapter {
        Snapshot read(RefreshInput input, ForgeRequestGate gate);
    }

    private static RefreshInput input(URI base, boolean enabled, List<String> commits, List<Branch> branches,
                                      Map<String, CachedAssociations> cache) {
        return new RefreshInput(new Source(REPOSITORY_ID, FORGE_ID, "example", ForgeProvider.GITLAB,
                base, 1, "team/service"), new Settings(REPOSITORY_ID, 1, enabled, 30, 100),
                new Selection(UUID.randomUUID(), NOW, null, NOW, NOW.minus(Duration.ofDays(30)), commits, branches),
                cache);
    }

    private static Reply next(String body, String href) {
        return new Reply(200, Map.of("Link", List.of("<" + href + ">; rel=\"next\", <?page=1>; rel=\"first\"")), body);
    }

    private static String mr(long project, long iid, String state, String title, String additionalFields) {
        return """
                {"project_id":%d,"iid":%d,"id":%d,"state":"%s","title":"%s"%s}
                """.formatted(project, iid, project * 100 + iid, state, title, additionalFields);
    }

    private static String pipeline(long project, long id, String ref, String sha) {
        return """
                {"project_id":%d,"id":%d,"ref":"%s","sha":"%s","status":"provider_future_status"}
                """.formatted(project, id, ref, sha);
    }

    private static String pipelineDetail(long project, long id, String ref, String sha, boolean tag) {
        return """
                {"project_id":%d,"id":%d,"ref":"%s","sha":"%s","status":"provider_future_status","tag":%s}
                """.formatted(project, id, ref, sha, tag);
    }

    static void assertCode(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ForgeException.class,
                error -> assertThat(error.getCode()).isEqualTo(code));
    }
}
