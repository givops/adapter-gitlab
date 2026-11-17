package io.givops.forge.gitlab;

import static org.assertj.core.api.Assertions.*;
import io.givops.forge.discovery.DiscoveryModels.Server;
import io.givops.forge.model.ForgeConnectionSettings.Hosting;
import io.givops.forge.model.ForgeProvisioningModels.CreateRepositoryCommand;
import io.givops.forge.port.ForgeAdapter.Access;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class GitLabRepositoriesTest {
    static final JsonMapper JSON=JsonMapper.builder().build();
    static final Server SERVER=new Server(URI.create("https://gitlab.example.test"));
    static final Access ACCESS=new Access("synthetic-token",Hosting.SAAS);
    static String project(boolean empty,String branch) {return "{\"id\":42,\"name\":\"fixture\",\"path_with_namespace\":\"team/nested/fixture\",\"description\":\"Run marker\",\"web_url\":\"https://gitlab.example.test/team/nested/fixture\",\"http_url_to_repo\":\"https://gitlab.example.test/team/nested/fixture.git\",\"visibility\":\"private\",\"empty_repo\":"+empty+",\"default_branch\":"+(branch==null?"null":"\""+branch+"\"")+"}";}
    @Test void createsNestedPrivateEmptyRepositoryAndReadsCanonicalIdentity() {
        var created=new AtomicBoolean();
        var fixture=new GitLabTestFixture(r -> {
            String path=r.uri().getPath();
            if(path.equals("/api/v4/namespaces/team")) return new GitLabTestFixture.Reply("{\"id\":5,\"full_path\":\"team\",\"parent_id\":null}");
            if(path.equals("/api/v4/namespaces/team/nested")) return new GitLabTestFixture.Reply(404,Map.of(),"");
            if(path.equals("/api/v4/groups")) {assertThat(r.method()).isEqualTo("POST");return new GitLabTestFixture.Reply(201,Map.of(),"{\"id\":6,\"full_path\":\"team/nested\",\"parent_id\":5}");}
            if(path.equals("/api/v4/projects")) {created.set(true);return new GitLabTestFixture.Reply(201,Map.of(),project(true,null));}
            return new GitLabTestFixture.Reply(project(true,null));
        });
        var adapter=new GitLabRepositories(new GitLabTransport(fixture.client,1_048_576),JSON);
        var result=adapter.createRepository(SERVER,new CreateRepositoryCommand("team/nested","fixture","Run marker",null,true,true),ACCESS,new GitLabTestFixture.Gate());
        assertThat(created).isTrue();assertThat(result.externalId()).isEqualTo("42");assertThat(result.fullPath()).isEqualTo("team/nested/fixture");assertThat(result.empty()).isTrue();assertThat(result.defaultBranch()).isNull();
        assertThat(fixture.requests).extracting(HttpRequest::method).containsExactly("GET","GET","POST","POST","GET");
        assertThat(fixture.requests.get(3).bodyPublisher().orElseThrow().contentLength()).isPositive();
    }
    @Test void malformedCreatedProjectIsSanitizedAndNeverReplayed() {
        for(String body:java.util.List.of("{\"private-note\":\"provider-private-sentinel\"", "{\"id\":42,\"id\":43,\"private-note\":\"provider-private-sentinel\"}", "{\"id\":42} provider-private-sentinel")) {
            var fixture=new GitLabTestFixture(r -> r.method().equals("POST")
                    ? new GitLabTestFixture.Reply(201,Map.of(),body)
                    : new GitLabTestFixture.Reply("{\"id\":5,\"full_path\":\"team\",\"parent_id\":null}"));
            var adapter=new GitLabRepositories(new GitLabTransport(fixture.client,1024),JSON);
            assertThatThrownBy(() -> adapter.createRepository(SERVER,new CreateRepositoryCommand("team","fixture",null,null,true,false),ACCESS,new GitLabTestFixture.Gate()))
                    .isInstanceOfSatisfying(io.givops.forge.model.ForgeException.class,failure -> {
                        assertThat(failure.getCode()).isEqualTo("gitlab_invalid_response");
                        assertThat(failure.getMessage()).doesNotContain("provider-private-sentinel");
                        assertThat(failure.getCause()).isNull();
                    });
            assertThat(fixture.requests).extracting(HttpRequest::method).containsExactly("GET","POST");
        }
    }
    @Test void supportsOnlyCurrentUserPersonalNamespaceAndNeverCreatesItAsAGroup() {
        var fixture=new GitLabTestFixture(r -> {
            if(r.uri().getPath().equals("/api/v4/namespaces/n.champenois")) return new GitLabTestFixture.Reply("{\"id\":7,\"kind\":\"user\",\"full_path\":\"n.champenois\",\"parent_id\":null}");
            if(r.uri().getPath().equals("/api/v4/user")) return new GitLabTestFixture.Reply("{\"id\":9,\"username\":\"other\"}");
            throw new AssertionError(r.uri());
        });
        var adapter=new GitLabRepositories(new GitLabTransport(fixture.client,1024),JSON);
        assertThatThrownBy(() -> adapter.createRepository(SERVER,new CreateRepositoryCommand("n.champenois","fixture",null,null,true,true),ACCESS,new GitLabTestFixture.Gate())).isInstanceOf(RuntimeException.class);
        assertThat(fixture.requests).extracting(HttpRequest::method).containsExactly("GET","GET");
    }
    @Test void refusesInitializationAndForeignCanonicalUrls() {
        var fixture=new GitLabTestFixture(r -> new GitLabTestFixture.Reply(project(true,null).replace("https://gitlab.example.test/team","https://foreign.example.test/team")));
        var adapter=new GitLabRepositories(new GitLabTransport(fixture.client,1_048_576),JSON);
        assertThatThrownBy(() -> adapter.createRepository(SERVER,new CreateRepositoryCommand("team/nested","fixture",null,"prod",true,false),ACCESS,new GitLabTestFixture.Gate())).isInstanceOf(RuntimeException.class);
        assertThat(fixture.requests).isEmpty();
        assertThatThrownBy(() -> adapter.findRepository(SERVER,"team/nested","fixture",ACCESS,new GitLabTestFixture.Gate())).isInstanceOf(RuntimeException.class);
    }
    @Test void deniedLookupIsNotMissingAndNeverWrites() {
        var fixture=new GitLabTestFixture(r -> new GitLabTestFixture.Reply(403,Map.of(),"sensitive"));
        var adapter=new GitLabRepositories(new GitLabTransport(fixture.client,1024),JSON);
        assertThatThrownBy(() -> adapter.findRepository(SERVER,"team/nested","fixture",ACCESS,new GitLabTestFixture.Gate())).hasMessageNotContaining("sensitive");
        assertThat(fixture.requests).singleElement().satisfies(r -> assertThat(r.method()).isEqualTo("GET"));
    }
    @Test void setsPushedDefaultBranchWithFreshReadback() {
        var changed=new AtomicBoolean();
        var fixture=new GitLabTestFixture(r -> {if(r.method().equals("PUT"))changed.set(true);return new GitLabTestFixture.Reply(project(false,changed.get()?"prod":"main"));});
        var adapter=new GitLabRepositories(new GitLabTransport(fixture.client,1024),JSON);
        adapter.setDefaultBranch(SERVER,"42","team/nested/fixture","prod",ACCESS,new GitLabTestFixture.Gate());
        assertThat(fixture.requests).extracting(HttpRequest::method).containsExactly("GET","PUT","GET");
    }
}
