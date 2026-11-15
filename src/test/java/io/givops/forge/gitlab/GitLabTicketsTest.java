package io.givops.forge.gitlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.givops.adapters.CapabilityInspection.State;
import io.givops.adapters.CredentialAccessException;
import io.givops.adapters.CredentialLease;
import io.givops.ticketing.config.TicketProperties;
import io.givops.ticketing.model.TicketException;
import io.givops.ticketing.model.TicketException.Code;
import io.givops.ticketing.model.TicketModels.*;
import io.givops.ticketing.model.TicketWriteModels.*;
import io.givops.ticketing.port.TicketProviderAdapter.Access;
import io.givops.ticketing.port.TicketRequestGate;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

class GitLabTicketsTest {
    static final URI BASE = URI.create("https://gitlab.example.test");
    static final Instant NOW = Instant.parse("2026-11-15T12:00:00Z");
    static final InstanceSource SOURCE = new InstanceSource(UUID.randomUUID(), "gitlab", TicketProvider.GITLAB, BASE, 1);
    static final GitLabTicketDefinition DEFINITION = new GitLabTicketDefinition(new GitLabProperties(Map.of()));
    final AtomicBoolean revoked = new AtomicBoolean();
    final AtomicBoolean locked = new AtomicBoolean();

    @Test void readsNativeIssueMetadataAndMilestoneMembershipWithoutInventingReleaseSemantics() {
        var fixture = fixture(this::normal);
        var adapter = adapter(fixture); var access = adapter.captureAccess(SOURCE);
        var result = adapter.read(input(Set.of("#7", "other/payments#7", "#99"), "November"), new Gate(), access).projects().getFirst();
        assertThat(result.project().externalProjectId()).isEqualTo("42");
        assertThat(result.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.issueKey()).isEqualTo("team/payments#7");
            assertThat(issue.identity()).isEqualTo(new IssueIdentity("42", "901"));
            assertThat(issue.description()).contains("duplicating payment");
            assertThat(issue.labels()).containsExactly("backend", "priority::high");
            assertThat(issue.assigneeAccountId()).isEqualTo("17");
            assertThat(issue.releaseVersionIds()).containsExactly("88");
        });
        assertThat(result.versions()).singleElement().satisfies(version -> {
            assertThat(version.released()).isFalse(); assertThat(version.archived()).isFalse();
            assertThat(version.nativeState()).isEqualTo("closed");
            assertThat(version.startDate()).isEqualTo(LocalDate.parse("2026-11-01"));
            assertThat(version.releaseDate()).isEqualTo(LocalDate.parse("2026-11-30"));
        });
        assertThat(result.releaseMembership().state()).isEqualTo(ReleaseMappingState.MATCHED);
        assertThat(result.unresolvedKeys()).containsExactlyInAnyOrder("other/payments#7", "#99");
        assertThat(fixture.requests).allSatisfy(request -> assertThat(request.headers().firstValue("PRIVATE-TOKEN")).contains("synthetic-token"));
        assertThat(adapter.current(SOURCE, access, true)).isTrue(); assertThat(locked).isTrue();
    }
    @Test void readsSelectedVersionMembershipAndRejectsUnknownOrExcessiveSelection() {
        var fixture = fixture(this::normal); var adapter = adapter(fixture); var access = adapter.captureAccess(SOURCE);
        var members = adapter.readVersionMemberships(SOURCE, query(Set.of(), null), access, new Gate(), versions -> Set.of("88"), 2, 5);
        assertThat(members.members().get("88")).hasSize(1);
        assertCode(() -> adapter.readVersionMemberships(SOURCE, query(Set.of(), null), access, new Gate(), versions -> Set.of("89"), 2, 5), Code.INVALID_CONFIGURATION);
        assertCode(() -> adapter.readVersionMemberships(SOURCE, query(Set.of(), null), access, new Gate(), versions -> Set.of("88"), 0, 5), Code.LIMIT_EXCEEDED);
    }
    @Test void issueNumbersRemainScopedAcrossTwoProjects() {
        var fixture = fixture(request -> {
            String path = request.uri().getPath();
            if (path.contains("/43")) {
                if (path.endsWith("/milestones")) return reply("[]");
                String resource = path.endsWith("/issues/7") ? resource("issue") : resource("project");
                return reply(resource.replace("42", "43").replace("901", "902").replace("team/payments", "other/payments"));
            }
            return normal(request);
        });
        var adapter = adapter(fixture);
        var other = new ProjectQuery(UUID.randomUUID(), "43", "other/payments", Set.of("#7"), null);
        var results = adapter.read(new ProviderInput(SOURCE, List.of(query(Set.of("#7"), null), other), NOW), new Gate()).projects();
        assertThat(results.stream().flatMap(result -> result.issues().stream()).map(IssueObservation::issueKey)).containsExactly("team/payments#7", "other/payments#7");
    }
    @Test void rejectsWrongProjectIdentityAndForeignIssueUrls() {
        for (String field : List.of("project", "issue")) {
            var fixture = fixture(request -> {
                var reply = normal(request);
                if (field.equals("project") && request.uri().getPath().equals("/api/v4/projects/42")) return reply(reply.body().replace("team/payments", "attacker/project"));
                if (field.equals("issue") && request.uri().getPath().endsWith("/issues/7")) return reply(reply.body().replace("https://gitlab.example.test", "https://evil.example.test"));
                return reply;
            });
            assertCode(() -> adapter(fixture).read(input(Set.of("#7"), null), new Gate()), Code.INVALID_RESPONSE);
        }
    }
    @Test void revokedCredentialStopsFurtherRequestsAndCannotPublish() {
        var fixture = fixture(request -> { revoked.set(true); return normal(request); });
        var adapter = adapter(fixture); Access access = adapter.captureAccess(SOURCE);
        assertCode(() -> adapter.read(input(Set.of("#7"), null), new Gate(), access), Code.SUPERSEDED);
        assertThat(fixture.requests).hasSize(1);
        assertThat(adapter.current(SOURCE, access, true)).isFalse();
    }
    @Test void capturedAccessCannotCrossInstancesOrDestinations() {
        var fixture = fixture(this::normal); var adapter = adapter(fixture); var access = adapter.captureAccess(SOURCE);
        var other = new InstanceSource(UUID.randomUUID(), "other", TicketProvider.GITLAB, URI.create("https://other.example.test"), 1);
        assertCode(() -> adapter.read(new ProviderInput(other, List.of(), NOW), new Gate(), access), Code.STALE_CONTEXT);
        assertThat(fixture.requests).isEmpty(); assertThat(access.toString()).doesNotContain("synthetic-token");
    }
    @Test void rejectsPaginationDestinationChangesAndDuplicateMilestones() {
        for (boolean duplicate : List.of(false, true)) {
            var fixture = fixture(request -> {
                if (request.uri().getPath().endsWith("/milestones")) {
                    if (duplicate) return reply("[" + resource("milestone") + "," + resource("milestone") + "]");
                    return new GitLabTestFixture.Reply(200, Map.of("Link", List.of("<https://evil.example.test/api/v4/projects/42/milestones?page=2>; rel=\"next\"")), "[" + resource("milestone") + "]");
                }
                return normal(request);
            });
            assertCode(() -> adapter(fixture).read(input(Set.of(), null), new Gate()), Code.INVALID_RESPONSE);
        }
    }
    @Test void followsBoundedPagesAndRefusesEndlessPaging() {
        var fixture = fixture(request -> {
            if (request.uri().getPath().endsWith("/milestones")) return new GitLabTestFixture.Reply(200,
                    Map.of("X-Next-Page", List.of(request.uri().getQuery().endsWith("page=1") ? "2" : "3")), "[" + resource("milestone") + "]");
            return normal(request);
        });
        assertCode(() -> adapter(fixture).read(input(Set.of(), null), new Gate()), Code.LIMIT_EXCEEDED);
        assertThat(fixture.requests).hasSize(3);
    }
    @ParameterizedTest @CsvSource({"401,AUTHENTICATION_FAILED", "403,AUTHORIZATION_FAILED", "429,RATE_LIMITED", "500,PROVIDER_ERROR", "302,PROVIDER_ERROR"})
    void mapsHttpFailuresWithoutLeakingBodies(int status, Code expected) {
        var fixture = fixture(request -> new GitLabTestFixture.Reply(status, Map.of(), "synthetic-sensitive-provider-body"));
        assertCode(() -> adapter(fixture).read(input(Set.of(), null), new Gate()), expected);
    }
    @ParameterizedTest @CsvSource({"200,AVAILABLE", "403,FORBIDDEN", "404,FORBIDDEN", "503,TEMPORARILY_UNAVAILABLE", "429,TEMPORARILY_UNAVAILABLE"})
    void capabilityEvidenceIsScopedAndDistinguishesFailures(int status, State expected) {
        var fixture = fixture(request -> {
            if (request.uri().getPath().endsWith("/metadata")) return reply(resource("metadata19"));
            if (request.uri().getPath().endsWith("/issues")) return new GitLabTestFixture.Reply(status, Map.of(), "[]");
            return normal(request);
        });
        var adapter = adapter(fixture);
        var result = adapter.inspect(SOURCE, "42", "ticketing.read", adapter.captureAccess(SOURCE), new Gate());
        assertThat(result.state()).isEqualTo(expected); assertThat(result.productVersion()).isEqualTo("19.0.0-ee");
        assertThat(result.apiProfile()).isEqualTo("gitlab-rest-v4"); assertThat(result.context()).isNotBlank();
    }
    @ParameterizedTest @CsvSource({"/projects/42", "/projects/42/milestones"})
    void hiddenBoundProjectOrProjectCollectionInvalidatesPrivateObservations(String hiddenPath) {
        var fixture = fixture(request -> request.uri().getPath().equals("/api/v4" + hiddenPath)
                ? new GitLabTestFixture.Reply(404, Map.of(), "synthetic-sensitive-provider-body") : normal(request));
        var adapter = adapter(fixture);
        assertCode(() -> adapter.read(input(Set.of("#7"), null), new Gate()), Code.AUTHORIZATION_FAILED);
        assertThat(fixture.requests).noneSatisfy(request -> assertThat(request.uri().getPath()).endsWith("/issues/7"));
        var capability = hiddenPath.endsWith("/milestones") ? "ticketing.version-membership" : "ticketing.read";
        assertThat(adapter.inspect(SOURCE, "42", capability, adapter.captureAccess(SOURCE), new Gate()).state())
                .isEqualTo(State.FORBIDDEN);
    }
    @Test void missingIndividualIssueAndMilestoneMembershipRetainNotFoundSemantics() {
        var fixture = fixture(this::normal);
        var missingIssue = adapter(fixture).read(input(Set.of("#99"), null), new Gate()).projects().getFirst();
        assertThat(missingIssue.unresolvedKeys()).containsExactly("#99");
        assertThat(missingIssue.issues()).isEmpty();
        var removedMilestone = fixture(request -> request.uri().getPath().endsWith("/milestones/88/issues")
                ? new GitLabTestFixture.Reply(404, Map.of(), "synthetic-sensitive-provider-body") : normal(request));
        assertCode(() -> adapter(removedMilestone).read(input(Set.of(), "November"), new Gate()), Code.NOT_FOUND);
    }
    @Test void capabilityInspectionDistinguishesDisabledUnsupportedAndUnknownWrites() {
        var fixture = fixture(this::normal); var adapter = adapter(fixture); var access = adapter.captureAccess(SOURCE);
        assertThat(adapter.inspect(SOURCE, "42", "ticketing.report", access, new Gate()).state()).isEqualTo(State.UNSUPPORTED);
        assertThat(adapter.inspect(SOURCE, "42", "ticketing.issue-create", access, new Gate()).state()).isEqualTo(State.UNKNOWN);
        var disabled = fixture(request -> reply(resource("project").replace("\"issues_enabled\":true", "\"issues_enabled\":false")));
        var disabledAdapter = adapter(disabled);
        assertThat(disabledAdapter.inspect(SOURCE, "42", "ticketing.read", disabledAdapter.captureAccess(SOURCE), new Gate()).state()).isEqualTo(State.DISABLED);
    }
    @Test void explicitlyCreatesNativeProjectMilestoneAndIssueOnce() {
        var fixture = fixture(request -> {
            if (request.method().equals("POST")) return new GitLabTestFixture.Reply(201, Map.of(), resource(request.uri().getPath().endsWith("/issues") ? "issue" : request.uri().getPath().endsWith("/milestones") ? "milestone" : "project"));
            return normal(request);
        });
        var adapter = adapter(fixture); var access = adapter.captureAccess(SOURCE);
        var project = adapter.createProject(SOURCE, new CreateProjectCommand("payments", "Payments", "Description", "5"), access, new Gate());
        assertThat(project.projectKey()).isEqualTo("team/payments");
        var version = adapter.createVersion(SOURCE, remote(), new CreateVersionCommand("November", false, false, LocalDate.parse("2026-11-30")), access, new Gate());
        assertThat(version.externalVersionId()).isEqualTo("88");
        var issue = adapter.createIssue(SOURCE, remote(), command(), access, new Gate());
        assertThat(issue.issueKey()).isEqualTo("team/payments#7");
        assertThat(fixture.requests.stream().filter(r -> r.method().equals("POST"))).hasSize(3);
    }
    @Test void refusesUnsupportedMilestoneReleaseAndIssueStateBeforeWriting() {
        var fixture = fixture(this::normal); var adapter = adapter(fixture); var access = adapter.captureAccess(SOURCE);
        assertCode(() -> adapter.createVersion(SOURCE, remote(), new CreateVersionCommand("release", true, false, null), access, new Gate()), Code.UNSUPPORTED_PROVIDER);
        var command = new CreateIssueCommand("Title", "Body", IssueKind.BUG, null, WorkflowState.DONE, null, PriorityLevel.NORMAL, null, List.of(), false, null);
        assertCode(() -> adapter.createIssue(SOURCE, remote(), command, access, new Gate()), Code.UNSUPPORTED_PROVIDER);
        assertThat(fixture.requests).isEmpty();
    }
    @Test void mutationRejectsAProjectFromAnotherInstanceBeforeHttp() {
        var fixture = fixture(this::normal); var adapter = adapter(fixture); var access = adapter.captureAccess(SOURCE);
        var foreign = new RemoteProjectRef(UUID.randomUUID(), UUID.randomUUID(), "42", "team/payments", "Payments", 1);
        assertCode(() -> adapter.createIssue(SOURCE, foreign, command(), access, new Gate()), Code.STALE_CONTEXT);
        assertCode(() -> adapter.createVersion(SOURCE, foreign, new CreateVersionCommand("November", false, false, null), access, new Gate()), Code.STALE_CONTEXT);
        assertThat(fixture.requests).isEmpty();
    }

    static CreateIssueCommand command() { return new CreateIssueCommand("Retry", "Details", IssueKind.BUG, null, WorkflowState.OPEN, null, PriorityLevel.HIGH, null, List.of("88"), true, null); }
    static RemoteProjectRef remote() { return new RemoteProjectRef(UUID.randomUUID(), SOURCE.instanceId(), "42", "team/payments", "Payments", 1); }
    static ProjectQuery query(Set<String> keys, String milestone) { return new ProjectQuery(UUID.randomUUID(), "42", "team/payments", keys, milestone); }
    static ProviderInput input(Set<String> keys, String milestone) { return new ProviderInput(SOURCE, List.of(query(keys, milestone)), NOW); }
    static GitLabTestFixture fixture(Function<HttpRequest,GitLabTestFixture.Reply> handler) { return new GitLabTestFixture(handler); }
    GitLabTickets adapter(GitLabTestFixture fixture) {
        var credentials = new GitLabCredentials(request -> new CredentialLease() {
            @Override public Map<String,String> fields() { return Map.of("apiToken", "synthetic-token"); }
            @Override public Map<String,String> destinations() { return Map.of("baseUrl", BASE.toString(), "instanceKey", "gitlab"); }
            @Override public String accessRevision() { return "revision-1"; }
            @Override public String mode() { return "managed"; }
            @Override public boolean legacyRuntime() { return false; }
            @Override public void requireCurrent(boolean lock) { if (lock) locked.set(true); if (revoked.get()) throw new CredentialAccessException(); }
        }, (provider, namespace, parts) -> Integer.toHexString(parts.hashCode()));
        return new GitLabTickets(credentials, limits(), new GitLabTransport(fixture.client, 1_048_576),
                JsonMapper.builder().build(), Clock.fixed(NOW, ZoneOffset.UTC), DEFINITION);
    }
    static TicketProperties limits() { return new TicketProperties(true, Duration.ofSeconds(30), Duration.ofSeconds(2), 50, 2,
            1_048_576, 4_194_304, 100, 100, 100, 100, 1_048_576, 10, 100, 1000, Duration.ofMinutes(15), 10, 100); }
    GitLabTestFixture.Reply normal(HttpRequest request) {
        String path = request.uri().getPath();
        if (path.endsWith("/metadata")) return reply(resource("metadata17"));
        if (path.endsWith("/milestones/88/issues")) return reply("[" + resource("issue") + "]");
        if (path.endsWith("/milestones")) return reply("[" + resource("milestone") + "]");
        if (path.endsWith("/issues/7")) return reply(resource("issue"));
        if (path.endsWith("/issues/99")) return new GitLabTestFixture.Reply(404, Map.of(), "not found");
        if (path.endsWith("/issues")) return reply("[]");
        if (path.endsWith("/user")) return reply("{\"id\":17}");
        return reply(resource("project"));
    }
    static GitLabTestFixture.Reply reply(String body) { return new GitLabTestFixture.Reply(body); }
    static String resource(String name) {
        try (var stream = GitLabTicketsTest.class.getResourceAsStream("/gitlab/" + name + ".json")) {
            return new String(java.util.Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException failure) { throw new AssertionError(failure); }
    }
    static void assertCode(Runnable action, Code code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(TicketException.class, failure -> {
            assertThat(failure.code()).isEqualTo(code); assertThat(failure.getMessage()).doesNotContain("synthetic-sensitive-provider-body");
        });
    }
    static class Gate implements TicketRequestGate {
        @Override public Duration beforeRequest() { return Duration.ofSeconds(2); }
        @Override public Duration remaining() { return Duration.ofSeconds(20); }
        @Override public void receivedBytes(long count) { }
        @Override public void acceptedObjects(int count) { }
        @Override public void check() { }
    }
}
