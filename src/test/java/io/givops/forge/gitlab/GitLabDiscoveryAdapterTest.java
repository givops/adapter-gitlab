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

    @Test
    void groupFailureDoesNotGateProjectDiscoveryAndOtherHostingIsUnsupported() {
        var fixture = new GitLabTestFixture(request -> request.uri().getPath().endsWith("/groups")
                ? new Reply(403, Map.of(), "forbidden") : new Reply("[" + project(50, "user", "alice") + "]"));
        var adapter = adapter(fixture);
        assertCode(() -> adapter.groups(SERVER, new io.givops.forge.port.ForgeAdapter.Access(TOKEN, Hosting.SELF_MANAGED), "", 10, 1, 0, new Gate()), "gitlab_forbidden");
        assertThat(adapter.projects(SERVER, new io.givops.forge.port.ForgeAdapter.Access(TOKEN, Hosting.SELF_MANAGED), null, "", 10, 1, 0, new Gate()).items()).hasSize(1);
        for (Hosting hosting : Hosting.values()) {
            for (Operation operation : Operation.values()) {
                assertThat(adapter.supports(ForgeProvider.GITLAB, hosting, operation)).isEqualTo(hosting == Hosting.SELF_MANAGED);
                assertThat(adapter.supports(ForgeProvider.GITHUB, hosting, operation)).isFalse();
            }
        }
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
