package io.givops.forge.gitlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.givops.catalog.ForgeProvider;
import io.givops.forge.discovery.DiscoveryModels.Server;
import io.givops.forge.gitlab.GitLabTestFixture.Gate;
import io.givops.forge.gitlab.GitLabTestFixture.Reply;
import io.givops.forge.model.ForgeConnectionSettings.Hosting;
import io.givops.forge.model.ForgeException;
import io.givops.forge.port.ForgeDiscoveryAdapter.Operation;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class GitLabDiscoveryAdapterTest {
    private static final Server SERVER = new Server(URI.create("https://git.example.test/git"));
    private static final String TOKEN = "synthetic-discovery-token";

    @Test
    void groupsUseAuthenticatedOffsetPagingAndExposeSubgroupFullPath() {
        var fixture = new GitLabTestFixture(request -> {
            assertThat(request.uri().getPath()).isEqualTo("/git/api/v4/groups");
            assertThat(request.uri().getRawQuery()).contains("all_available=true", "top_level_only=false",
                    "order_by=id", "sort=asc", "search=Platform%20Team", "page=2", "per_page=10")
                    .doesNotContain("pagination=keyset");
            assertThat(request.headers().firstValue("PRIVATE-TOKEN")).contains(TOKEN);
            return new Reply(200, Map.of("X-Page", List.of("2"), "X-Next-Page", List.of("3")),
                    "[{\"id\":22,\"name\":\"Service Team\",\"full_path\":\"platform/service\",\"parent_id\":3}]");
        });
        var page = adapter(fixture).groups(SERVER, new io.givops.forge.port.ForgeAdapter.Access(TOKEN, Hosting.SELF_MANAGED), "Platform Team", 10, 2, 20, new Gate());
        assertThat(page.hasNext()).isTrue();
        assertThat(page.items()).singleElement().satisfies(group -> {
            assertThat(group.externalId()).isEqualTo("22");
            assertThat(group.parentExternalId()).isEqualTo("3");
            assertThat(group.fullPath()).isEqualTo("platform/service");
            assertThat(group.suggestedKey()).isEqualTo("platform-service-22");
        });
    }

    @Test
    void allProjectsTraverseIdRangesBeyondTheOffsetCeilingIncludingPersonalNamespaces() {
        var fixture = new GitLabTestFixture(request -> {
            assertThat(request.uri().getPath()).isEqualTo("/git/api/v4/projects");
            assertThat(request.uri().getRawQuery()).contains("id_after=50001", "order_by=id", "sort=asc", "page=1")
                    .doesNotContain("membership", "owned", "page=50001");
            return new Reply(200, Map.of("X-Next-Page", List.of("")), "[" + project(50002, "user", "alice") + "]");
        });
        var page = adapter(fixture).projects(SERVER, new io.givops.forge.port.ForgeAdapter.Access(TOKEN, Hosting.SELF_MANAGED), null, "", 100, 50001, 50001, new Gate());
        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.namespace().kind()).isEqualTo("user");
            assertThat(item.namespace().fullPath()).isEqualTo("alice");
            assertThat(item.sourceFingerprint()).hasSize(64);
        });
        assertThat(page.lastId()).isEqualTo(50002);
        assertThat(page.hasNext()).isFalse();
    }

    @Test
    void groupProjectsExcludeSharedAndSubgroupProjectsAndValidateNamespace() {
        var fixture = new GitLabTestFixture(request -> {
            assertThat(request.uri().getPath()).isEqualTo("/git/api/v4/groups/12/projects");
            assertThat(request.uri().getRawQuery()).contains("with_shared=false", "include_subgroups=false", "page=1");
            return new Reply("[" + project(50, "group", "team/subgroup") + "]");
        });
        assertThat(adapter(fixture).projects(SERVER, new io.givops.forge.port.ForgeAdapter.Access(TOKEN, Hosting.SELF_MANAGED), "12", "", 10, 1, 0, new Gate()).items()).hasSize(1);
        var wrong = new GitLabTestFixture(request -> new Reply("[" + project(50, "user", "alice") + "]"));
        assertCode(() -> adapter(wrong).projects(SERVER, new io.givops.forge.port.ForgeAdapter.Access(TOKEN, Hosting.SELF_MANAGED), "12", "", 10, 1, 0, new Gate()), "gitlab_invalid_response");
    }

    @Test
    void detailRevalidationUsesOnlyNumericIdsAndFingerprintDetectsRenames() {
        var fixture = new GitLabTestFixture(request -> {
            assertThat(request.uri().getRawPath()).isEqualTo("/git/api/v4/projects/50");
            assertThat(request.uri().getRawQuery()).isNull();
            return new Reply(project(50, "group", "team"));
        });
        var original = adapter(fixture).project(SERVER, new io.givops.forge.port.ForgeAdapter.Access(TOKEN, Hosting.SELF_MANAGED), "50", new Gate());
        var renamed = new GitLabTestFixture(request -> new Reply(project(50, "group", "renamed-team")));
        assertThat(adapter(renamed).project(SERVER, new io.givops.forge.port.ForgeAdapter.Access(TOKEN, Hosting.SELF_MANAGED), "50", new Gate()).sourceFingerprint())
                .isNotEqualTo(original.sourceFingerprint());
        assertCode(() -> adapter(fixture).project(SERVER, new io.givops.forge.port.ForgeAdapter.Access(TOKEN, Hosting.SELF_MANAGED), "team/repo", new Gate()), "invalid_discovery_id");
        assertThat(fixture.requests).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://evil.example.test/git/team/repo.git", "http://git.example.test/git/team/repo.git",
            "https://secret@git.example.test/git/team/repo.git", "https://git.example.test/other/repo.git",
            "https://git.example.test/git/../repo.git", "https://git.example.test/git/team/repo.git?token=secret"})
    void rejectsUnsafeRemoteCloneUrls(String clone) {
        var fixture = new GitLabTestFixture(request -> new Reply(project(50, "group", "team")
                .replace("https://git.example.test/git/team/repo.git", clone)));
        assertCode(() -> adapter(fixture).project(SERVER, new io.givops.forge.port.ForgeAdapter.Access(TOKEN, Hosting.SELF_MANAGED), "50", new Gate()), "gitlab_invalid_response");
    }

    @Test
    void rejectsOutOfOrderIdsOverfullResponsesAndChangedContinuationDestinations() {
        var unordered = new GitLabTestFixture(request -> new Reply("[" + project(2, "group", "team")
                + "," + project(1, "group", "team") + "]"));
        assertCode(() -> adapter(unordered).projects(SERVER, new io.givops.forge.port.ForgeAdapter.Access(TOKEN, Hosting.SELF_MANAGED), null, "", 10, 1, 0, new Gate()), "gitlab_invalid_pagination");
        assertCode(() -> adapter(unordered).projects(SERVER, new io.givops.forge.port.ForgeAdapter.Access(TOKEN, Hosting.SELF_MANAGED), null, "", 1, 1, 0, new Gate()), "gitlab_invalid_response");
        var malicious = new GitLabTestFixture(request -> new Reply(200, Map.of("Link", List.of(
                "<https://evil.example.test/git/api/v4/projects?page=2>; rel=\"next\"")),
                "[" + project(2, "group", "team") + "]"));
        assertCode(() -> adapter(malicious).projects(SERVER, new io.givops.forge.port.ForgeAdapter.Access(TOKEN, Hosting.SELF_MANAGED), null, "", 10, 1, 0, new Gate()), "gitlab_invalid_pagination");
        assertThat(malicious.requests).hasSize(1);
    }

    @Test
    void offsetCeilingFailsClearlyInsteadOfReturningAnEmptyPage() {
        var fixture = new GitLabTestFixture(request -> { throw new AssertionError("No outbound request expected"); });
        assertCode(() -> adapter(fixture).groups(SERVER, new io.givops.forge.port.ForgeAdapter.Access(TOKEN, Hosting.SELF_MANAGED), "", 100, 501, 50000, new Gate()), "discovery_pagination_limit");
        assertCode(() -> adapter(fixture).projects(SERVER, new io.givops.forge.port.ForgeAdapter.Access(TOKEN, Hosting.SELF_MANAGED), "12", "", 100, 501, 50000, new Gate()), "discovery_pagination_limit");
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = Hosting.class, names = {"SELF_MANAGED", "SAAS"})
    void groupFailureDoesNotGateProjectDiscoveryAndOtherHostingIsUnsupported(Hosting supportedHosting) {
        var fixture = new GitLabTestFixture(request -> request.uri().getPath().endsWith("/groups")
                ? new Reply(403, Map.of(), "forbidden") : new Reply("[" + project(50, "user", "alice") + "]"));
        var adapter = adapter(fixture);
        assertCode(() -> adapter.groups(SERVER, new io.givops.forge.port.ForgeAdapter.Access(TOKEN, supportedHosting), "", 10, 1, 0, new Gate()), "gitlab_forbidden");
        assertThat(adapter.projects(SERVER, new io.givops.forge.port.ForgeAdapter.Access(TOKEN, supportedHosting), null, "", 10, 1, 0, new Gate()).items()).hasSize(1);
        for (Hosting hosting : Hosting.values()) {
            for (Operation operation : Operation.values()) {
                assertThat(adapter.supports(ForgeProvider.GITLAB, hosting, operation)).isEqualTo(hosting == Hosting.SELF_MANAGED || hosting == Hosting.SAAS);
                assertThat(adapter.supports(ForgeProvider.GITHUB, hosting, operation)).isFalse();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"group", "user"})
    void saasGlobalProjectsAndImportRevalidationAlwaysRequireMembership(String kind) {
        var fixture = new GitLabTestFixture(request -> {
            assertThat(request.uri().getPath()).isEqualTo("/git/api/v4/projects");
            assertThat(request.uri().getRawQuery()).contains("membership=true", "order_by=id", "sort=asc", "page=1")
                    .doesNotContain("visibility=", "owned=");
            assertThat(request.headers().firstValue("PRIVATE-TOKEN")).contains(TOKEN);
            return new Reply(200, Map.of("X-Next-Page", List.of("")), "[" + project(50, kind, "team/deep/leaf") + "]");
        });
        var adapter = adapter(fixture);
        var access = new io.givops.forge.port.ForgeAdapter.Access(TOKEN, Hosting.SAAS);
        assertThat(adapter.projects(SERVER, access, null, "", 10, 1, 0, new Gate()).items()).hasSize(1);
        assertThat(adapter.project(SERVER, access, "50", new Gate()).namespace().kind()).isEqualTo(kind);
        assertThat(fixture.requests.getLast().uri().getRawQuery()).contains("id_after=49", "id_before=51", "per_page=1");
    }

    @Test
    void saasCraftedPublicIdCannotUseReadableProjectDetailAsImportEligibility() {
        var fixture = new GitLabTestFixture(request -> {
            assertThat(request.uri().getPath()).isEqualTo("/git/api/v4/projects");
            assertThat(request.uri().getRawQuery()).contains("membership=true", "id_after=49", "id_before=51");
            return new Reply("[]");
        });
        assertCode(() -> adapter(fixture).project(SERVER,
                new io.givops.forge.port.ForgeAdapter.Access(TOKEN, Hosting.SAAS), "50", new Gate()), "gitlab_forbidden");
        assertThat(fixture.requests).hasSize(1);
    }

    @Test
    void saasGroupCollectionsExcludeUnrelatedPublicGroupsAndGroupProjectsRequireEffectiveAccess() {
        var fixture = new GitLabTestFixture(request -> {
            String path = request.uri().getPath();
            if (path.endsWith("/projects")) {
                assertThat(request.uri().getRawQuery()).contains("min_access_level=10", "with_shared=false", "include_subgroups=false")
                        .doesNotContain("membership=");
                return new Reply("[" + project(50, "group", "team/leaf") + "]");
            }
            assertThat(request.uri().getRawQuery()).contains("all_available=false", "search=leaf", "order_by=id");
            return new Reply("[{\"id\":12,\"name\":\"Leaf\",\"full_path\":\"team/leaf\",\"parent_id\":3}]");
        });
        var adapter = adapter(fixture);
        var access = new io.givops.forge.port.ForgeAdapter.Access(TOKEN, Hosting.SAAS);
        assertThat(adapter.groups(SERVER, access, "leaf", 10, 1, 0, new Gate()).items()).hasSize(1);
        assertThat(adapter.subgroups(SERVER, access, "3", "leaf", 10, 1, 0, new Gate()).items()).hasSize(1);
        assertThat(adapter.projects(SERVER, access, "12", "", 10, 1, 0, new Gate()).items()).hasSize(1);
        assertThat(fixture.requests.get(1).uri().getPath()).endsWith("/groups/3/subgroups");
    }

    @Test
    void knownAncestryReadsPreserveExactNamesAndLargeIdsWithoutListingEmbeddedProjects() {
        var fixture = new GitLabTestFixture(request -> {
            assertThat(request.uri().getPath()).endsWith("/groups/9223372036854775807");
            assertThat(request.uri().getRawQuery()).isEqualTo("with_projects=false");
            return new Reply("{\"id\":9223372036854775807,\"name\":\"Exact Team\",\"full_path\":\"root/team\",\"parent_id\":1}");
        });
        var group = adapter(fixture).group(SERVER, new io.givops.forge.port.ForgeAdapter.Access(TOKEN, Hosting.SAAS),
                "9223372036854775807", new Gate());
        assertThat(group.name()).isEqualTo("Exact Team");
        assertThat(group.parentExternalId()).isEqualTo("1");
        assertThat(group.externalId()).isEqualTo("9223372036854775807");
    }

    @Test
    void hierarchyRejectsWrongParentsIdentityMismatchAndDeniedAncestors() {
        var wrong = new GitLabTestFixture(request -> new Reply("[{\"id\":12,\"name\":\"Team\",\"full_path\":\"root/team\",\"parent_id\":4}]"));
        var access = new io.givops.forge.port.ForgeAdapter.Access(TOKEN, Hosting.SAAS);
        assertCode(() -> adapter(wrong).subgroups(SERVER, access, "3", "", 10, 1, 0, new Gate()), "gitlab_invalid_response");
        var mismatch = new GitLabTestFixture(request -> new Reply("{\"id\":12,\"name\":\"Team\",\"full_path\":\"root/team\",\"parent_id\":4}"));
        assertCode(() -> adapter(mismatch).group(SERVER, access, "3", new Gate()), "gitlab_invalid_response");
        var denied = new GitLabTestFixture(request -> new Reply(403, Map.of(), "private remote diagnostic"));
        assertCode(() -> adapter(denied).group(SERVER, access, "3", new Gate()), "gitlab_forbidden");
    }

    @Test
    void rejectsAnonymousInvalidHostingAndMalformedHierarchyInputsBeforeAnyRequest() {
        var fixture = new GitLabTestFixture(request -> { throw new AssertionError("No request expected"); });
        var adapter = adapter(fixture);
        var access = new io.givops.forge.port.ForgeAdapter.Access(TOKEN, Hosting.SAAS);
        for (Hosting hosting : List.of(Hosting.SELF_MANAGED, Hosting.SAAS)) {
            var anonymous = new io.givops.forge.port.ForgeAdapter.Access((String) null, hosting);
            assertCode(() -> adapter.groups(SERVER, anonymous, "", 10, 1, 0, new Gate()), "gitlab_unauthorized");
            assertCode(() -> adapter.projects(SERVER, anonymous, null, "", 10, 1, 0, new Gate()), "gitlab_unauthorized");
            assertCode(() -> adapter.group(SERVER, anonymous, "1", new Gate()), "gitlab_unauthorized");
            assertCode(() -> adapter.project(SERVER, anonymous, "1", new Gate()), "gitlab_unauthorized");
        }
        assertCode(() -> adapter.group(SERVER, access, "01", new Gate()), "invalid_discovery_id");
        assertCode(() -> adapter.subgroups(SERVER, access, "1/2", "", 10, 1, 0, new Gate()), "invalid_discovery_id");
        assertCode(() -> adapter.subgroups(SERVER, access, "1", "", 101, 1, 0, new Gate()), "gitlab_configuration_invalid");
        assertCode(() -> adapter.subgroups(SERVER, access, "1", "", 10, 0, 0, new Gate()), "gitlab_configuration_invalid");
        assertCode(() -> adapter.groups(SERVER, new io.givops.forge.port.ForgeAdapter.Access(TOKEN, Hosting.UNKNOWN),
                "", 10, 1, 0, new Gate()), "gitlab_configuration_invalid");
        assertThat(fixture.requests).isEmpty();
    }

    @Test
    void saasContinuationCannotDropMembershipOrBroadenSubgroupCollections() {
        var projects = new GitLabTestFixture(request -> new Reply(200, Map.of("Link", List.of(
                "</git/api/v4/projects?membership=false&page=2>; rel=next")), "[" + project(50, "user", "alice") + "]"));
        var access = new io.givops.forge.port.ForgeAdapter.Access(TOKEN, Hosting.SAAS);
        assertCode(() -> adapter(projects).projects(SERVER, access, null, "", 10, 1, 0, new Gate()), "gitlab_invalid_pagination");
        var groups = new GitLabTestFixture(request -> new Reply(200, Map.of("Link", List.of(
                "</git/api/v4/groups/3/subgroups?all_available=true&page=2>; rel=next")),
                "[{\"id\":12,\"name\":\"Team\",\"full_path\":\"root/team\",\"parent_id\":3}]"));
        assertCode(() -> adapter(groups).subgroups(SERVER, access, "3", "", 10, 1, 0, new Gate()), "gitlab_invalid_pagination");
    }

    @Test
    void membershipVerificationHandlesMaximumIdAndRejectsUnexpectedResponseIds() {
        var access = new io.givops.forge.port.ForgeAdapter.Access(TOKEN, Hosting.SAAS);
        var fixture = new GitLabTestFixture(request -> {
            assertThat(request.uri().getRawQuery()).contains("membership=true", "id_after=9223372036854775806", "per_page=1")
                    .doesNotContain("id_before=");
            return new Reply("[" + project(Long.MAX_VALUE, "user", "owner") + "]");
        });
        assertThat(adapter(fixture).project(SERVER, access, Long.toString(Long.MAX_VALUE), new Gate()).externalId())
                .isEqualTo(Long.toString(Long.MAX_VALUE));
        var wrong = new GitLabTestFixture(request -> new Reply("[" + project(51, "group", "team") + "]"));
        assertCode(() -> adapter(wrong).project(SERVER, access, "50", new Gate()), "gitlab_invalid_response");
    }

    @Test
    void hierarchyUsesTheRequestGateAndRetainsResponseByteAndObjectBounds() {
        var fixture = new GitLabTestFixture(request -> new Reply("{\"id\":12,\"name\":\"Team\",\"full_path\":\"root/team\",\"parent_id\":3}"));
        var access = new io.givops.forge.port.ForgeAdapter.Access(TOKEN, Hosting.SAAS);
        var gate = new Gate();
        adapter(fixture).group(SERVER, access, "12", gate);
        assertThat(gate.requests).isEqualTo(1);
        assertThat(gate.objects).isEqualTo(1);
        assertThat(gate.bytes.get()).isPositive();
        var byteLimited = new GitLabDiscoveryAdapter(new GitLabTransport(fixture.client, 8), JsonMapper.builder().build());
        assertCode(() -> byteLimited.group(SERVER, access, "12", new Gate()), "forge_budget_exhausted");
        var stopped = new Gate() {
            @Override public java.time.Duration beforeRequest() { throw GitLabErrors.budgetExceeded(); }
        };
        int requests = fixture.requests.size();
        assertCode(() -> adapter(fixture).group(SERVER, access, "12", stopped), "forge_budget_exhausted");
        assertThat(fixture.requests).hasSize(requests);
        var overfull = new GitLabTestFixture(request -> new Reply("[{\"id\":12,\"name\":\"Team\",\"full_path\":\"root/team\",\"parent_id\":3},"
                + "{\"id\":13,\"name\":\"Other\",\"full_path\":\"root/other\",\"parent_id\":3}]"));
        assertCode(() -> adapter(overfull).subgroups(SERVER, access, "3", "", 1, 1, 0, new Gate()), "gitlab_invalid_response");
    }

    @Test
    void capabilityInspectionPreservesSelfManagedPathIdentityAndChecksSaasMembership() {
        var fixture = new GitLabTestFixture(request -> {
            String path = request.uri().getPath();
            if (path.endsWith("/metadata")) return new Reply("{\"version\":\"19.0.0\"}");
            if (path.endsWith("/projects")) {
                assertThat(request.uri().getRawQuery()).contains("membership=true", "id_after=49", "id_before=51");
                return new Reply("[]");
            }
            if (path.endsWith("/merge_requests")) return new Reply("[]");
            assertThat(path).endsWith("/projects/team/service");
            return new Reply("{\"id\":50}");
        });
        var inspector = new GitLabForgeCapabilities(new GitLabTransport(fixture.client, 1024 * 1024),
                JsonMapper.builder().build(), java.time.Clock.systemUTC());
        var source = new io.givops.forge.model.ForgeModels.Source(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                "synthetic", ForgeProvider.GITLAB, SERVER.baseUrl(), 1, "team/service");
        assertThat(inspector.inspect(source, "forge.discovery",
                new io.givops.forge.port.ForgeAdapter.Access(TOKEN, Hosting.SELF_MANAGED), new Gate()).state())
                .isEqualTo(io.givops.adapters.CapabilityInspection.State.AVAILABLE);
        var numeric = new io.givops.forge.model.ForgeModels.Source(source.repositoryId(), source.forgeId(),
                "synthetic", ForgeProvider.GITLAB, SERVER.baseUrl(), 1, "50");
        assertThat(inspector.inspect(numeric, "forge.discovery",
                new io.givops.forge.port.ForgeAdapter.Access(TOKEN, Hosting.SAAS), new Gate()).state())
                .isEqualTo(io.givops.adapters.CapabilityInspection.State.FORBIDDEN);
        int requests = fixture.requests.size();
        assertThat(inspector.inspect(numeric, "forge.group-hierarchy",
                new io.givops.forge.port.ForgeAdapter.Access((String) null, Hosting.SAAS), new Gate()).state())
                .isEqualTo(io.givops.adapters.CapabilityInspection.State.FORBIDDEN);
        assertThat(fixture.requests).hasSize(requests);
    }

    private static GitLabDiscoveryAdapter adapter(GitLabTestFixture fixture) {
        return new GitLabDiscoveryAdapter(new GitLabTransport(fixture.client, 1024 * 1024), JsonMapper.builder().build());
    }

    private static String project(long id, String kind, String namespace) {
        return """
                {"id":%d,"name":"Repository","path_with_namespace":"%s/repo",
                 "http_url_to_repo":"https://git.example.test/git/%s/repo.git",
                 "web_url":"https://git.example.test/git/%s/repo",
                 "namespace":{"id":12,"kind":"%s","name":"Exact Team Name","full_path":"%s"}}
                """.formatted(id, namespace, namespace, namespace, kind, namespace);
    }

    private static void assertCode(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ForgeException.class,
                exception -> assertThat(exception.getCode()).isEqualTo(code));
    }
}
