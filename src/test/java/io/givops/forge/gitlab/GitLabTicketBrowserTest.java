package io.givops.forge.gitlab;

import static io.givops.forge.gitlab.GitLabTicketsTest.*;
import static org.assertj.core.api.Assertions.assertThat;

import io.givops.ticketing.model.TicketBrowserModels.*;
import io.givops.ticketing.model.TicketException;
import io.givops.ticketing.model.TicketException.Code;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class GitLabTicketBrowserTest {
    private static final UUID TICKET_PROJECT_ID = UUID.randomUUID();
    private final GitLabTicketsTest support = new GitLabTicketsTest();

    @Test void describesMutableBrowsingWithContentAndNoAssetsAfterProjectValidation() {
        var fixture = fixture(support::normal); var adapter = support.adapter(fixture);
        var descriptor = adapter.describe(request(null), adapter.captureAccess(SOURCE), new Gate());
        assertThat(descriptor.queryFields()).containsExactly("text:key-title-literal-nfc", "statuses", "categories", "labels:any");
        assertThat(descriptor.lifecycles()).containsExactly(Lifecycle.ACTIVE);
        assertThat(descriptor.sorts()).contains("key-asc", "key-desc", "updated-asc", "updated-desc");
        assertThat(descriptor.snapshot()).isFalse(); assertThat(descriptor.content()).isTrue(); assertThat(descriptor.assets()).isFalse();
        assertThat(fixture.requests).singleElement().satisfies(http -> assertThat(http.uri().getPath()).isEqualTo("/api/v4/projects/42"));
    }
    @Test void combinesFamiliesWithAndAndValuesWithOrIncludingLabels() {
        String rows = rows(issue(901, 7, "Retry payment", "opened", "issue", "backend"),
                issue(902, 8, "Retry worker", "closed", "incident", "urgent"),
                issue(903, 9, "Retry other", "opened", "task", "backend"),
                issue(904, 10, "Different title", "opened", "issue", "backend"),
                issue(905, 11, "Retry unlabeled", "opened", "issue", "frontend"));
        var fixture = fixture(http -> collection(http) ? reply(rows) : support.normal(http));
        var adapter = support.adapter(fixture);
        var query = new BrowseQuery("retry", Lifecycle.ACTIVE, List.of("opened", "closed"), List.of("issue", "incident"),
                List.of("backend", "urgent"), "key-asc", 100, null);
        var page = adapter.list(request(null), query, adapter.captureAccess(SOURCE), new Gate());
        assertThat(page.items()).extracting(TicketSummary::displayKey).containsExactly("team/payments#7", "team/payments#8");
        assertThat(page.items()).extracting(item -> item.ref().externalIssueId()).containsExactly("901", "902");
        assertThat(page.items()).extracting(TicketSummary::outcome).containsExactly(Outcome.OPEN, Outcome.UNKNOWN);
        assertThat(page.items()).allSatisfy(item -> {
            assertThat(item.lifecycle()).isEqualTo(Lifecycle.ACTIVE); assertThat(item.contentAvailable()).isTrue();
            assertThat(item.revision()).isEqualTo(page.revision());
        });
        assertThat(page.exactTotal()).isEqualTo(2); assertThat(page.nextContinuation()).isNull();
        assertThat(page.revision().kind()).isEqualTo("gitlab-live"); assertThat(page.revision().immutable()).isFalse();
        assertThat(page.effectiveQuery()).isEqualTo(query);
        assertThat(fixture.requests).filteredOn(GitLabTicketBrowserTest::collection).singleElement().satisfies(http -> {
            assertThat(http.uri().getQuery()).contains("scope=all", "state=all", "order_by=created_at", "sort=asc");
            assertThat(http.uri().getQuery()).doesNotContain("labels=", "search=", "issue_type=");
        });
    }
    @Test void searchesOnlyLiteralNormalizedKeysAndTitlesAndSortsIidsNumerically() {
        var fixture = fixture(http -> collection(http) ? reply(rows(issue(903, 10, "Payment retry", "closed", "issue"),
                issue(901, 2, "Caf\u00e9 retry", "opened", "issue"), issue(902, 7, "Payment retry", "opened", "issue"))) : support.normal(http));
        var adapter = support.adapter(fixture); var access = adapter.captureAccess(SOURCE);
        var first = adapter.list(request(null), query("", "key-asc", 1, null), access, new Gate());
        assertThat(first.items()).extracting(TicketSummary::displayKey).containsExactly("team/payments#2");
        var second = adapter.list(request(first.revision()), query("", "key-asc", 1, first.nextContinuation()), access, new Gate());
        assertThat(second.items()).extracting(TicketSummary::displayKey).containsExactly("team/payments#7");
        var third = adapter.list(request(first.revision()), query("", "key-asc", 1, second.nextContinuation()), access, new Gate());
        assertThat(third.items()).extracting(TicketSummary::displayKey).containsExactly("team/payments#10");
        assertThat(third.nextContinuation()).isNull();
        assertThat(adapter.list(request(null), query("CAFE\u0301", "key-asc", 10, null), access, new Gate()).items()).hasSize(1);
        assertThat(adapter.list(request(null), query("#7", "key-asc", 10, null), access, new Gate()).items()).hasSize(1);
        assertThat(adapter.list(request(null), query("TICKET-BODY", "key-asc", 10, null), access, new Gate()).items()).isEmpty();
        assertThat(adapter.list(request(null), query(".*", "key-asc", 10, null), access, new Gate()).items()).isEmpty();
        assertThat(adapter.list(request(null), query("", "key-desc", 10, null), access, new Gate()).items())
                .extracting(TicketSummary::displayKey).containsExactly("team/payments#10", "team/payments#7", "team/payments#2");
    }
    @Test void followsValidatedRemotePagesBeforeLocalFilteringAndPagination() {
        var fixture = fixture(http -> {
            if (!collection(http)) return support.normal(http);
            if (http.uri().getQuery().endsWith("page=1")) return new GitLabTestFixture.Reply(200, Map.of("X-Next-Page", List.of("2")),
                    rows(issue(902, 10, "Later", "closed", "issue", "urgent")));
            return new GitLabTestFixture.Reply(200, Map.of("X-Next-Page", List.of("")), rows(issue(901, 7, "First", "opened", "issue", "backend")));
        });
        var adapter = support.adapter(fixture);
        var page = adapter.list(request(null), query("", "key-asc", 1, null), adapter.captureAccess(SOURCE), new Gate());
        assertThat(page.items()).extracting(TicketSummary::displayKey).containsExactly("team/payments#7");
        assertThat(page.exactTotal()).isEqualTo(2); assertThat(page.nextContinuation()).isNotNull();
        assertThat(fixture.requests).hasSize(3);
    }
    @Test void sortsUpdateTimestampsWithNumericKeyTieBreaksAndHandlesMissingNativeTypes() {
        var older = issue(901, 7, "Old", "opened", "issue").put("updated_at", "2026-01-01T00:00:00Z");
        var newer = issue(902, 10, "New", "opened", "issue").put("updated_at", "2026-02-01T00:00:00Z");
        var tied = issue(903, 2, "Tied", "opened", "issue").put("updated_at", "2026-02-01T00:00:00Z");
        var missing = issue(904, 11, "Missing", "opened", "issue").putNull("updated_at").putNull("issue_type");
        var fixture = fixture(http -> collection(http) ? reply(rows(newer, missing, older, tied)) : support.normal(http));
        var adapter = support.adapter(fixture); var access = adapter.captureAccess(SOURCE);
        assertThat(adapter.list(request(null), query("", "updated-asc", 100, null), access, new Gate()).items())
                .extracting(item -> item.ref().externalIssueId()).containsExactly("904", "901", "903", "902");
        assertThat(adapter.list(request(null), query("", "updated-desc", 100, null), access, new Gate()).items())
                .extracting(item -> item.ref().externalIssueId()).containsExactly("902", "903", "901", "904");
        var filter = new BrowseQuery("", Lifecycle.ACTIVE, List.of(), List.of("issue"), List.of(), "key-asc", 100, null);
        assertThat(adapter.list(request(null), filter, access, new Gate()).items()).hasSize(3);
    }
    @Test void resolvesGlobalIdentityThenFetchesAndValidatesTheProjectIidForDetailAndContent() {
        var fixture = fixture(http -> collection(http) ? reply(rows(issue(901, 7, "Handle payment retry", "opened", "issue"))) : support.normal(http));
        var adapter = support.adapter(fixture); var access = adapter.captureAccess(SOURCE);
        var detail = adapter.detail(request(null), ticket("901"), access, new Gate());
        assertThat(detail.ticket().ref()).isEqualTo(ticket("901"));
        assertThat(detail.ticket().displayKey()).isEqualTo("team/payments#7");
        assertThat(detail.assigneeId()).isEqualTo("17"); assertThat(detail.releaseVersionIds()).containsExactly("88");
        assertThat(detail.navigation().ticketUrl()).isEqualTo(URI.create(BASE + "/team/payments/-/issues/7"));
        var content = adapter.content(request(detail.ticket().revision()), ticket("901"), access, new Gate());
        assertThat(content.ref()).isEqualTo(ticket("901")); assertThat(content.format()).isEqualTo(ContentFormat.MARKDOWN);
        assertThat(content.text()).isEqualTo("TICKET-BODY-MUST-NOT-SURVIVE");
        assertThat(content.toString()).doesNotContain("TICKET-BODY"); assertThat(content.revision()).isEqualTo(detail.ticket().revision());
        assertThat(content.assets()).isEmpty(); assertThat(content.links()).isEmpty();
        assertThat(fixture.requests).noneSatisfy(http -> assertThat(http.uri().getPath()).endsWith("/issues/901"));
        assertThat(fixture.requests).filteredOn(http -> http.uri().getPath().endsWith("/issues/7")).hasSize(2);
        var later = new BrowserRequest(SOURCE, TICKET_PROJECT_ID, "42", "team/payments", detail.ticket().revision(), NOW.plusSeconds(10));
        assertThat(adapter.content(later, ticket("901"), access, new Gate()).revision()).isEqualTo(detail.ticket().revision());
    }
    @Test void retainsPathAliasInBrowserIdentityAndPreservesRawMarkdownWithoutFetchingLinks() {
        String body = "## Retry\n\n[external](https://elsewhere.example.test/file)\n![upload](/uploads/id/image.png)";
        ObjectNode row = issue(901, 7, "Retry", "opened", "issue").put("description", body);
        var fixture = fixture(http -> collection(http) ? reply(rows(row)) : http.uri().getPath().endsWith("/issues/7") ? reply(row.toString()) : support.normal(http));
        var adapter = support.adapter(fixture); var access = adapter.captureAccess(SOURCE);
        var request = new BrowserRequest(SOURCE, UUID.randomUUID(), "team/payments", "team/payments", null, NOW);
        var page = adapter.list(request, query("", "key-asc", 10, null), access, new Gate());
        var ref = new TicketRef(SOURCE.instanceId(), "team/payments", "901");
        assertThat(page.items().getFirst().ref()).isEqualTo(ref);
        var content = adapter.content(request, ref, access, new Gate());
        assertThat(content.text()).isEqualTo(body); assertThat(content.assets()).isEmpty();
        assertThat(fixture.requests).allSatisfy(http -> assertThat(http.uri().getHost()).isEqualTo(BASE.getHost()));
        assertThat(fixture.requests).hasSize(5);
    }
    @Test void treatsAnAbsentDescriptionAsEmptyContent() {
        ObjectNode row = issue(901, 7, "Retry", "opened", "issue").putNull("description");
        var fixture = fixture(http -> collection(http) ? reply(rows(row)) : http.uri().getPath().endsWith("/issues/7") ? reply(row.toString()) : support.normal(http));
        var adapter = support.adapter(fixture);
        assertThat(adapter.content(request(null), ticket("901"), adapter.captureAccess(SOURCE), new Gate()).text()).isEmpty();
    }
    @Test void rejectsForeignReferencesAndRevisionKindsValuesOrImmutabilityBeforeHttp() {
        var fixture = fixture(support::normal); var adapter = support.adapter(fixture); var access = adapter.captureAccess(SOURCE);
        for (var ref : List.of(new TicketRef(UUID.randomUUID(), "42", "901"), new TicketRef(SOURCE.instanceId(), "43", "901"))) {
            assertCode(() -> adapter.detail(request(null), ref, access, new Gate()), Code.STALE_CONTEXT);
            assertCode(() -> adapter.content(request(null), ref, access, new Gate()), Code.STALE_CONTEXT);
        }
        for (var revision : List.of(new SourceRevision("other", "value", false), new SourceRevision("gitlab-live", "wrong", false),
                new SourceRevision("gitlab-live", "wrong", true)))
            assertCode(() -> adapter.describe(request(revision), access, new Gate()), Code.STALE_CONTEXT);
        assertThat(fixture.requests).isEmpty();
    }
    @Test void rejectsAlteredValidRevisionAndChangedBindingOrAccessScope() {
        var fixture = fixture(support::normal); var adapter = support.adapter(fixture); var access = adapter.captureAccess(SOURCE);
        var revision = adapter.list(request(null), query("", "key-asc", 10, null), access, new Gate()).revision();
        fixture.requests.clear();
        assertCode(() -> adapter.describe(request(new SourceRevision(revision.kind(), revision.value(), true)), access, new Gate()), Code.STALE_CONTEXT);
        var otherBinding = new BrowserRequest(SOURCE, UUID.randomUUID(), "42", "team/payments", revision, NOW);
        assertCode(() -> adapter.describe(otherBinding, access, new Gate()), Code.STALE_CONTEXT);
        for (String id : List.of("0", "000901", "../901", "9223372036854775808"))
            assertCode(() -> adapter.detail(request(null), ticket(id), access, new Gate()), Code.INVALID_CONFIGURATION);
        assertThat(fixture.requests).isEmpty();
    }
    @Test void rejectsInvalidQueriesAndCursorsOrChangedCursorQueryScopeBeforeHttp() {
        var fixture = fixture(http -> collection(http) ? reply(rows(issue(901, 7, "First", "opened", "issue"), issue(902, 8, "Second", "opened", "issue"))) : support.normal(http));
        var adapter = support.adapter(fixture); var access = adapter.captureAccess(SOURCE);
        var first = adapter.list(request(null), query("", "key-asc", 1, null), access, new Gate());
        fixture.requests.clear();
        for (String cursor : List.of("2", "offset:0:" + "0".repeat(64), "https://evil.example.test/next", "offset:1:" + "0".repeat(64)))
            assertCode(() -> adapter.list(request(null), query("", "key-asc", 1, cursor), access, new Gate()), Code.INVALID_CONFIGURATION);
        assertCode(() -> adapter.list(request(null), query("changed", "key-asc", 1, first.nextContinuation()), access, new Gate()), Code.INVALID_CONFIGURATION);
        var other = new BrowserRequest(SOURCE, UUID.randomUUID(), "43", "other/payments", null, NOW);
        assertCode(() -> adapter.list(other, query("", "key-asc", 1, first.nextContinuation()), access, new Gate()), Code.INVALID_CONFIGURATION);
        assertCode(() -> adapter.list(request(null), query("", "unsupported", 1, null), access, new Gate()), Code.INVALID_CONFIGURATION);
        assertCode(() -> adapter.list(request(null), new BrowseQuery("", Lifecycle.ARCHIVED, List.of(), List.of(), List.of(), "key-asc", 1, null), access, new Gate()), Code.INVALID_CONFIGURATION);
        assertThat(fixture.requests).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings = {"id", "iid", "project_id", "web_url"})
    void refusesMismatchedFetchedIssueIdentityOrOrigin(String field) {
        ObjectNode changed = issue(901, 7, "Retry", "opened", "issue");
        if (field.equals("web_url")) changed.put(field, "https://evil.example.test/team/payments/-/issues/7");
        else changed.put(field, field.equals("id") ? 902 : field.equals("iid") ? 8 : 43);
        var fixture = fixture(http -> collection(http) ? reply(rows(issue(901, 7, "Retry", "opened", "issue")))
                : http.uri().getPath().endsWith("/issues/7") ? reply(changed.toString()) : support.normal(http));
        var adapter = support.adapter(fixture); var access = adapter.captureAccess(SOURCE);
        assertCode(() -> adapter.detail(request(null), ticket("901"), access, new Gate()), Code.INVALID_RESPONSE);
        assertCode(() -> adapter.content(request(null), ticket("901"), access, new Gate()), Code.INVALID_RESPONSE);
    }
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void rejectsDuplicateStableIdsOrIidsAcrossRemotePages(boolean globalId) {
        var fixture = fixture(http -> {
            if (!collection(http)) return support.normal(http);
            boolean first = http.uri().getQuery().endsWith("page=1");
            return new GitLabTestFixture.Reply(200, Map.of("X-Next-Page", List.of(first ? "2" : "")),
                    rows(issue(first || globalId ? 901 : 902, first || !globalId ? 7 : 8, "Retry", "opened", "issue")));
        });
        var adapter = support.adapter(fixture);
        assertCode(() -> adapter.list(request(null), query("", "key-asc", 10, null), adapter.captureAccess(SOURCE), new Gate()), Code.INVALID_RESPONSE);
    }
    @Test void distinguishesMissingIssueHiddenProjectAndDisabledIssues() {
        var empty = fixture(support::normal); var adapter = support.adapter(empty); var access = adapter.captureAccess(SOURCE);
        assertCode(() -> adapter.detail(request(null), ticket("901"), access, new Gate()), Code.NOT_FOUND);
        var hidden = fixture(http -> new GitLabTestFixture.Reply(404, Map.of(), "synthetic-sensitive-provider-body"));
        var denied = support.adapter(hidden);
        assertCode(() -> denied.content(request(null), ticket("901"), denied.captureAccess(SOURCE), new Gate()), Code.AUTHORIZATION_FAILED);
        var disabled = fixture(http -> reply(resource("project").replace("\"issues_enabled\":true", "\"issues_enabled\":false")));
        var unavailable = support.adapter(disabled);
        assertCode(() -> unavailable.describe(request(null), unavailable.captureAccess(SOURCE), new Gate()), Code.DISABLED);
    }
    @ParameterizedTest @CsvSource({"401,AUTHENTICATION_FAILED", "403,AUTHORIZATION_FAILED", "404,NOT_FOUND", "429,RATE_LIMITED", "500,PROVIDER_ERROR", "302,PROVIDER_ERROR"})
    void translatesContentDetailFailuresAndKeepsIndividualNotFoundSemantics(int status, Code expected) {
        var fixture = fixture(http -> collection(http) ? reply(rows(issue(901, 7, "Retry", "opened", "issue")))
                : http.uri().getPath().endsWith("/issues/7") ? new GitLabTestFixture.Reply(status, Map.of(), "synthetic-sensitive-provider-body") : support.normal(http));
        var adapter = support.adapter(fixture);
        assertCode(() -> adapter.content(request(null), ticket("901"), adapter.captureAccess(SOURCE), new Gate()), expected);
    }
    @Test void refusesChangedPaginationOriginBeforeIssuingAnotherRequest() {
        var fixture = fixture(http -> collection(http) ? new GitLabTestFixture.Reply(200,
                Map.of("Link", List.of("<https://evil.example.test/api/v4/projects/42/issues?page=2>; rel=\"next\"")), rows(issue(901, 7, "Retry", "opened", "issue"))) : support.normal(http));
        var adapter = support.adapter(fixture);
        assertCode(() -> adapter.list(request(null), query("", "key-asc", 10, null), adapter.captureAccess(SOURCE), new Gate()), Code.INVALID_RESPONSE);
        assertThat(fixture.requests).hasSize(2);
    }
    @Test void enforcesGateRevocationAndRequestPageByteBudgets() {
        var fixture = fixture(http -> { support.revoked.set(true); return support.normal(http); });
        var adapter = support.adapter(fixture); var access = adapter.captureAccess(SOURCE);
        assertCode(() -> adapter.list(request(null), query("", "key-asc", 10, null), access, new Gate()), Code.SUPERSEDED);
        assertThat(fixture.requests).hasSize(1);
        var fresh = new GitLabTicketsTest();
        var endless = fixture(http -> !collection(http) ? fresh.normal(http) : new GitLabTestFixture.Reply(200,
                Map.of("X-Next-Page", List.of(http.uri().getQuery().endsWith("page=1") ? "2" : "3")), rows(issue(901, 7, "Retry", "opened", "issue"))));
        var bounded = fresh.adapter(endless);
        assertCode(() -> bounded.list(request(null), query("", "key-asc", 10, null), bounded.captureAccess(SOURCE), new Gate()), Code.LIMIT_EXCEEDED);
        assertThat(endless.requests).hasSize(3);
        var huge = fixture(http -> collection(http) ? reply(rows(issue(901, 7, "Retry", "opened", "issue")))
                : http.uri().getPath().endsWith("/issues/7") ? reply(issue(901, 7, "Retry", "opened", "issue").put("description", "x".repeat(1_048_577)).toString()) : fresh.normal(http));
        var byteBounded = fresh.adapter(huge);
        assertCode(() -> byteBounded.content(request(null), ticket("901"), byteBounded.captureAccess(SOURCE), new Gate()), Code.LIMIT_EXCEEDED);
        var canceled = fixture(fresh::normal); var canceledAdapter = fresh.adapter(canceled);
        assertCode(() -> canceledAdapter.describe(request(null), canceledAdapter.captureAccess(SOURCE), new Gate() {
            @Override public void check() { throw new TicketException(Code.SUPERSEDED, "Synthetic canceled request."); }
        }), Code.SUPERSEDED);
        assertThat(canceled.requests).isEmpty();
    }

    static BrowserRequest request(SourceRevision revision) { return new BrowserRequest(SOURCE, TICKET_PROJECT_ID, "42", "team/payments", revision, NOW); }
    static TicketRef ticket(String id) { return new TicketRef(SOURCE.instanceId(), "42", id); }
    static BrowseQuery query(String text, String sort, int size, String continuation) { return new BrowseQuery(text, Lifecycle.ACTIVE, List.of(), List.of(), List.of(), sort, size, continuation); }
    static boolean collection(HttpRequest request) { return request.uri().getPath().endsWith("/issues"); }
    static ObjectNode issue(int id, int iid, String title, String state, String type, String... labels) {
        ObjectNode row = (ObjectNode) JsonMapper.builder().build().readTree(resource("issue"));
        row.put("id", id).put("iid", iid).put("title", title).put("state", state).put("issue_type", type)
                .put("web_url", BASE + "/team/payments/-/issues/" + iid);
        var values = row.putArray("labels"); for (String label : labels) values.add(label);
        return row;
    }
    static String rows(ObjectNode... rows) { return "[" + java.util.Arrays.stream(rows).map(ObjectNode::toString).collect(java.util.stream.Collectors.joining(",")) + "]"; }
}
