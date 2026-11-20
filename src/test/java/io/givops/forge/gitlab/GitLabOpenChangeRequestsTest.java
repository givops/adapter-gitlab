package io.givops.forge.gitlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.givops.adapters.CapabilityInspection.State;
import io.givops.catalog.ForgeProvider;
import io.givops.forge.config.ForgeProperties;
import io.givops.forge.gitlab.GitLabTestFixture.Gate;
import io.givops.forge.gitlab.GitLabTestFixture.Reply;
import io.givops.forge.model.ChangeRequestLifecycle;
import io.givops.forge.model.ForgeConnectionSettings.Hosting;
import io.givops.forge.model.ForgeException;
import io.givops.forge.model.ForgeModels.CiState;
import io.givops.forge.model.ForgeModels.PipelineCoverageState;
import io.givops.forge.model.ForgeModels.Source;
import io.givops.forge.port.ForgeAdapter.Access;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class GitLabOpenChangeRequestsTest {
    private static final String ENDPOINT = "https://git.example.test/git/api/v4/projects/team%2Fservice/merge_requests";
    private static final String QUERY = "state=opened&scope=all&wip=no&per_page=100&page=";
    private static final Instant NOW = Instant.parse("2026-11-20T05:00:00Z");
    private static final Source SOURCE = new Source(UUID.randomUUID(), UUID.randomUUID(), "gitlab",
            ForgeProvider.GITLAB, URI.create("https://git.example.test/git/"), 0, "team/service");
    private static final Access ACCESS = new Access("synthetic-token", Hosting.SELF_MANAGED);

    @ParameterizedTest
    @CsvSource({"200,AVAILABLE,project_endpoint_readable", "403,FORBIDDEN,HTTP 403 from the remote endpoint."})
    void inspectsTheDeclaredCapabilityWithTheScopedMergeRequestReadProbe(int status, State expected, String reason) {
        var fixture = new GitLabTestFixture(request -> {
            assertThat(request.method()).isEqualTo("GET");
            assertThat(request.headers().firstValue("PRIVATE-TOKEN")).contains("synthetic-token");
            return switch (request.uri().getRawPath()) {
                case "/git/api/v4/metadata" -> new Reply("{\"version\":\"17.9.0\"}");
                case "/git/api/v4/projects/team%2Fservice" -> new Reply("{}");
                case "/git/api/v4/projects/team%2Fservice/merge_requests" -> {
                    assertThat(request.uri().getRawQuery().split("&")).containsExactlyInAnyOrder(
                            "state=opened", "scope=all", "wip=no", "per_page=1", "page=1");
                    yield new Reply(status, Map.of(), status == 200 ? "[]" : "synthetic-provider-error");
                }
                default -> throw new AssertionError("Unexpected capability request: " + request.uri());
            };
        });
        assertThat(new GitLabForgeDefinition(new GitLabProperties(Map.of())).capabilities())
                .contains("forge.open-change-requests");
        var inspector = new GitLabForgeCapabilities(new GitLabTransport(fixture.client, 1024 * 1024),
                JsonMapper.builder().build(), Clock.fixed(NOW, ZoneOffset.UTC));
        var result = inspector.inspect(SOURCE, "forge.open-change-requests", ACCESS, new Gate());

        assertThat(result.state()).isEqualTo(expected);
        assertThat(result.reason()).isEqualTo(reason);
        assertThat(fixture.requests).extracting(request -> request.uri().getRawPath()).containsExactly(
                "/git/api/v4/metadata", "/git/api/v4/projects/team%2Fservice",
                "/git/api/v4/projects/team%2Fservice/merge_requests");
    }

    @Test void readsOnlyTheCollectionAcrossPagesAndAllTargetBranchesWithoutPipelineEnrichment() {
        var fixture = new GitLabTestFixture(request -> {
            assertThat(request.method()).isEqualTo("GET");
            assertThat(request.uri().getRawPath()).isEqualTo(URI.create(ENDPOINT).getRawPath());
            assertThat(request.headers().firstValue("PRIVATE-TOKEN")).contains("synthetic-token");
            if (request.uri().getRawQuery().equals(QUERY + "1")) {
                return new Reply(200, Map.of("Link", List.of("<" + ENDPOINT + "?page=2>; rel=\"next\"")),
                        "[" + mr(1, "opened", "main", ",\"draft\":false,\"head_pipeline\":{\"invalid\":true}") + "]");
            }
            assertThat(request.uri().getRawQuery()).isEqualTo(QUERY + "2");
            return new Reply("[" + mr(2, "opened", "release/2", ",\"work_in_progress\":false") + "]");
        });
        var gate = new Gate();
        var result = adapter(fixture, limits(10, 50)).readOpenChangeRequests(SOURCE, gate, ACCESS);

        assertThat(fixture.requests).extracting(request -> request.uri().toString())
                .containsExactly(ENDPOINT + "?" + QUERY + "1", ENDPOINT + "?" + QUERY + "2");
        assertThat(result).extracting(change -> change.targetBranch()).containsExactly("main", "release/2");
        assertThat(result).allSatisfy(change -> {
            assertThat(change.lifecycle()).isEqualTo(ChangeRequestLifecycle.OPEN);
            assertThat(change.observedAt()).isEqualTo(NOW);
            assertThat(change.headPipeline()).isNull();
            assertThat(change.pipelines()).isEmpty();
            assertThat(change.pipelineCandidates()).isEmpty();
            assertThat(change.pipelineCoverage().state()).isEqualTo(PipelineCoverageState.UNKNOWN);
            assertThat(change.taskSignals().ciSummary().state()).isEqualTo(CiState.UNKNOWN);
            assertThat(change.taskSignals().ciSummary().coverage()).isEqualTo(PipelineCoverageState.UNKNOWN);
            assertThat(change.taskSignals().approvalsKnown()).isFalse();
            assertThat(change.taskSignals().unresolvedDiscussionCount()).isNull();
        });
        assertThat(gate.requests).isEqualTo(2);
        assertThat(gate.objects).isEqualTo(2);
        assertThat(gate.bytes.get()).isPositive();
        assertThatThrownBy(result::clear).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void filtersClosedAndBothDraftFlagsEvenWhenTheServerIgnoresFilters() {
        var fixture = new GitLabTestFixture(request -> new Reply("["
                + mr(1, "opened", "main", ",\"draft\":true") + ","
                + mr(2, "opened", "main", ",\"work_in_progress\":true") + ","
                + mr(3, "opened", "main", ",\"draft\":false,\"work_in_progress\":true") + ","
                + mr(4, "closed", "main", ",\"draft\":false") + ","
                + mr(5, "merged", "main", ",\"draft\":false") + ","
                + mr(6, "locked", "main", ",\"draft\":false") + ","
                + mr(7, "opened", "main", ",\"draft\":false") + "]"));
        var gate = new Gate();
        assertThat(adapter(fixture, limits(10, 1)).readOpenChangeRequests(SOURCE, gate, ACCESS))
                .extracting(change -> change.key().localId()).containsExactly("7");
        assertThat(gate.objects).isEqualTo(7);
        assertThat(fixture.requests).hasSize(1);
    }

    @Test void successfulEmptyCollectionMakesOneRequest() {
        var fixture = new GitLabTestFixture(request -> new Reply("[]"));
        assertThat(adapter(fixture, limits(10, 50)).readOpenChangeRequests(SOURCE, new Gate(), ACCESS)).isEmpty();
        assertThat(fixture.requests).extracting(request -> request.uri().toString())
                .containsExactly(ENDPOINT + "?" + QUERY + "1");
    }

    @Test void fullPageWithoutContinuationHeadersProbesTheNextPage() {
        String page = IntStream.rangeClosed(1, 100).mapToObj(id -> mr(id, "opened", "main", ",\"draft\":false"))
                .collect(Collectors.joining(",", "[", "]"));
        var fixture = new GitLabTestFixture(request -> new Reply(request.uri().getRawQuery().endsWith("page=1") ? page : "[]"));
        assertThat(adapter(fixture, limits(10, 100)).readOpenChangeRequests(SOURCE, new Gate(), ACCESS)).hasSize(100);
        assertThat(fixture.requests).hasSize(2);
    }

    @ParameterizedTest
    @CsvSource({"401,gitlab_unauthorized", "403,gitlab_forbidden", "404,gitlab_not_found",
            "429,gitlab_rate_limited", "500,gitlab_unavailable"})
    void aFailedLaterPageFailsTheWholeReadWithoutFallbackOrRetry(int status, String code) {
        var fixture = new GitLabTestFixture(request -> request.uri().getRawQuery().endsWith("page=1")
                ? new Reply(200, Map.of("X-Next-Page", List.of("2")), "[" + mr(1, "opened", "main", ",\"draft\":false") + "]")
                : new Reply(status, Map.of(), "synthetic-provider-error"));
        assertFailure(adapter(fixture, limits(10, 50)), new Gate(), code);
        assertThat(fixture.requests).hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "[", "[{}]", "[] trailing", "[{\"state\":\"opened\"}]",
            "[{\"state\":\"opened\",\"draft\":\"false\"}]"})
    void invalidCollectionsAndUnknownDraftStateFail(String body) {
        var fixture = new GitLabTestFixture(request -> new Reply(body));
        assertFailure(adapter(fixture, limits(10, 50)), new Gate(), "gitlab_invalid_response");
        assertThat(fixture.requests).hasSize(1);
    }

    @Test void collectionCannotExceedItsPageSize() {
        String page = IntStream.rangeClosed(1, 101).mapToObj(id -> mr(id, "opened", "main", ",\"draft\":false"))
                .collect(Collectors.joining(",", "[", "]"));
        var fixture = new GitLabTestFixture(request -> new Reply(page));
        assertFailure(adapter(fixture, limits(10, 200)), new Gate(), "gitlab_invalid_response");
    }

    @Test void changeRequestBoundRejectsIncompleteResults() {
        var fixture = new GitLabTestFixture(request -> new Reply("["
                + mr(1, "opened", "main", ",\"draft\":false") + ","
                + mr(2, "opened", "release", ",\"draft\":false") + "]"));
        assertFailure(adapter(fixture, limits(10, 1)), new Gate(), "forge_budget_exhausted");
    }

    @Test void pageBoundRejectsIncompleteResults() {
        var fixture = new GitLabTestFixture(request -> new Reply(200, Map.of("X-Next-Page", List.of("2")),
                "[" + mr(1, "opened", "main", ",\"draft\":false") + "]"));
        assertFailure(adapter(fixture, limits(1, 50)), new Gate(), "forge_budget_exhausted");
        assertThat(fixture.requests).hasSize(1);
    }

    @Test void continuationCannotChangeTheScopeOrDestination() {
        var fixture = new GitLabTestFixture(request -> new Reply(200,
                Map.of("Link", List.of("<" + ENDPOINT + "?state=closed&scope=all&wip=no&page=2>; rel=\"next\"")),
                "[" + mr(1, "opened", "main", ",\"draft\":false") + "]"));
        assertFailure(adapter(fixture, limits(10, 50)), new Gate(), "gitlab_invalid_pagination");
        assertThat(fixture.requests).hasSize(1);
    }

    @Test void theHostGateCanRejectObjectsBeforeTheyArePublished() {
        var fixture = new GitLabTestFixture(request -> new Reply("[" + mr(1, "opened", "main", ",\"draft\":false") + "]"));
        var gate = new Gate() {
            @Override public void acceptedObjects(int count) { throw GitLabErrors.budgetExceeded(); }
        };
        assertFailure(adapter(fixture, limits(10, 50)), gate, "forge_budget_exhausted");
    }

    private static void assertFailure(GitLabAdapter adapter, Gate gate, String code) {
        assertThatThrownBy(() -> adapter.readOpenChangeRequests(SOURCE, gate, ACCESS))
                .isInstanceOfSatisfying(ForgeException.class, failure -> assertThat(failure.getCode()).isEqualTo(code));
    }

    private static GitLabAdapter adapter(GitLabTestFixture fixture, ForgeProperties limits) {
        return new GitLabAdapter(limits,
                new GitLabTransport(fixture.client, limits.maxResponseBytes()),
                JsonMapper.builder().build(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static ForgeProperties limits(int pages, int changes) {
        var base = GitLabContractTest.forgeLimits();
        return new ForgeProperties(base.refreshTimeout(), base.requestTimeout(), base.maxRequests(), pages,
                base.maxResponseBytes(), base.maxTotalBytes(), changes, base.maxBranches(), base.maxAssociations(),
                base.maxObjects(), base.maxConcurrentPerForge(), base.associationTtl(), base.freshness(), base.retainedAttempts());
    }

    private static String mr(int id, String state, String target, String fields) {
        return "{\"id\":" + (1000 + id) + ",\"iid\":" + id + ",\"project_id\":42,\"state\":\"" + state
                + "\",\"title\":\"Synthetic change " + id + "\",\"source_branch\":\"feature/" + id
                + "\",\"target_branch\":\"" + target + "\"" + fields + "}";
    }
}
