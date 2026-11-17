package io.givops.forge.gitlab;

import io.givops.catalog.ForgeProvider;
import io.givops.forge.discovery.DiscoveryModels.Server;
import io.givops.forge.model.ForgeConnectionSettings.Hosting;
import io.givops.forge.model.ForgeProvisioningModels.*;
import io.givops.forge.port.ForgeAdapter.Access;
import io.givops.forge.port.ForgeProvisioningAdapter;
import io.givops.forge.port.ForgeRequestGate;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

/** Empty private repository provisioning. Writes are attempted once; lookup never adopts a resource. */
public final class GitLabRepositories implements ForgeProvisioningAdapter {
    private final GitLabTransport transport;
    private final JsonMapper mapper;
    private final ObjectReader reader;
    GitLabRepositories(GitLabTransport transport, JsonMapper mapper) {
        this.transport=transport; this.mapper=mapper;
        this.reader=mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).with(StreamReadFeature.STRICT_DUPLICATE_DETECTION);
    }
    @Override public boolean supports(ForgeProvider provider, Hosting hosting) {
        return provider==ForgeProvider.GITLAB && (hosting==Hosting.SAAS || hosting==Hosting.SELF_MANAGED);
    }
    @Override public Repository createRepository(Server server, CreateRepositoryCommand command, Access access, ForgeRequestGate gate) {
        requireAccess(access); if(command==null) throw GitLabErrors.invalidConfiguration(); validate(command.namespacePath(),command.name());
        if(!command.privateRepository() || command.defaultBranch()!=null && !command.defaultBranch().isBlank()) throw GitLabErrors.invalidConfiguration();
        var urls=new GitLabUrls(server.baseUrl());
        String namespace=namespace(urls,command.namespacePath(),command.createNamespaces(),access,gate);
        var body=new LinkedHashMap<String,Object>();
        body.put("namespace_id",Long.parseLong(namespace)); body.put("path",command.name()); body.put("name",command.name());
        if(command.description()!=null) body.put("description",command.description());
        JsonNode created=createProject(transport,urls,access.token(),gate,mapper,body);
        String id=id(created,"id");
        Repository result=findRepository(server,command.namespacePath(),command.name(),access,gate).orElseThrow(GitLabErrors::invalidResponse);
        if(!id.equals(result.externalId()) || !result.privateRepository() || !result.empty()) throw GitLabErrors.invalidResponse();
        return result;
    }
    /** Shared with ticket project creation: no README, license, or implicit Git reference. */
    static JsonNode createProject(GitLabTransport transport,GitLabUrls urls,String token,ForgeRequestGate gate,JsonMapper mapper,Map<String,Object> body) {
        body.put("visibility","private"); body.put("initialize_with_readme",false); body.put("issues_enabled",true);
        byte[] bytes=mapper.writeValueAsBytes(body);if(bytes.length>65_536) throw GitLabErrors.invalidConfiguration();
        var response=transport.post(urls.detail("/projects"),token,bytes,gate,201);
        if(response.status()!=201) throw GitLabErrors.remoteStatus(response.status());
        try {
            var row=mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).with(StreamReadFeature.STRICT_DUPLICATE_DETECTION).readTree(response.body());
            GitLabPayload.requireObject(row); gate.acceptedObjects(1); return row;
        } catch(tools.jackson.core.JacksonException failure) { throw GitLabErrors.invalidResponse(); }
    }
    @Override public Optional<Repository> findRepository(Server server,String namespacePath,String name,Access access,ForgeRequestGate gate) {
        requireAccess(access); validate(namespacePath,name);
        var urls=new GitLabUrls(server.baseUrl()); String path=namespacePath+"/"+name;
        var response=transport.getForCheck(urls.detail(GitLabUrls.project(path)),access.token(),gate);
        if(response.status()==404) return Optional.empty();
        if(response.status()!=200) throw GitLabErrors.remoteStatus(response.status());
        var row=parse(response); gate.acceptedObjects(1);
        if(!path.equals(GitLabPayload.text(row,"path_with_namespace",true))) throw GitLabErrors.invalidResponse();
        String clone=GitLabPayload.text(row,"http_url_to_repo",true), web=GitLabPayload.text(row,"web_url",true);
        requireUrl(server.baseUrl(),web,path); requireUrl(server.baseUrl(),clone,path+".git");
        if(!row.path("empty_repo").isBoolean()) throw GitLabErrors.invalidResponse();
        return Optional.of(new Repository(id(row,"id"),path,namespacePath,name,GitLabPayload.text(row,"description",false),clone,web,
                GitLabPayload.text(row,"default_branch",false),"private".equals(GitLabPayload.text(row,"visibility",true)),row.path("empty_repo").booleanValue()));
    }
    @Override public void setDefaultBranch(Server server,String externalId,String fullPath,String branch,Access access,ForgeRequestGate gate) {
        requireAccess(access); if(!externalId.matches("[1-9][0-9]{0,18}") || !validBranch(branch)) throw GitLabErrors.invalidConfiguration();
        int slash=fullPath.lastIndexOf('/'); if(slash<1) throw GitLabErrors.invalidConfiguration();
        var before=findRepository(server,fullPath.substring(0,slash),fullPath.substring(slash+1),access,gate).orElseThrow(GitLabErrors::invalidResponse);
        if(!externalId.equals(before.externalId())) throw GitLabErrors.invalidResponse();
        if(branch.equals(before.defaultBranch())) return;
        io.givops.forge.model.ForgeException writeFailure=null;
        try {
            var response=transport.put(new GitLabUrls(server.baseUrl()).detail(GitLabUrls.project(externalId)),access.token(),mapper.writeValueAsBytes(Map.of("default_branch",branch)),gate);
            if(response.status()!=200) throw GitLabErrors.remoteStatus(response.status());
        } catch(io.givops.forge.model.ForgeException failure) {writeFailure=failure;}
        var actual=findRepository(server,fullPath.substring(0,slash),fullPath.substring(slash+1),access,gate).orElseThrow(GitLabErrors::invalidResponse);
        if(!externalId.equals(actual.externalId())) throw GitLabErrors.invalidResponse();
        if(!branch.equals(actual.defaultBranch())) {if(writeFailure!=null) throw writeFailure;throw GitLabErrors.invalidResponse();}
    }
    String existingNamespace(URI base,String namespacePath,String token,ForgeRequestGate gate) {
        validate(namespacePath,"repository");
        return namespace(new GitLabUrls(base),namespacePath,false,new Access(token,Hosting.SELF_MANAGED),gate);
    }
    private String namespace(GitLabUrls urls,String fullPath,boolean create,Access access,ForgeRequestGate gate) {
        String parent=null,path="";
        for(String part:fullPath.split("/")) {
            path=path.isEmpty()?part:path+"/"+part;
            var response=transport.getForCheck(urls.detail("/namespaces/"+encode(path)),access.token(),gate);
            JsonNode row;
            if(response.status()==404 && create) {
                var body=new LinkedHashMap<String,Object>(); body.put("path",part);body.put("name",part);body.put("visibility","private");
                if(parent!=null) body.put("parent_id",Long.parseLong(parent));
                var created=transport.post(urls.detail("/groups"),access.token(),mapper.writeValueAsBytes(body),gate,201);
                if(created.status()!=201) throw GitLabErrors.remoteStatus(created.status());
                row=parse(created);
            } else { if(response.status()!=200) throw GitLabErrors.remoteStatus(response.status()); row=parse(response); }
            if(!path.equals(GitLabPayload.text(row,"full_path",true))) throw GitLabErrors.invalidResponse();
            if("user".equals(GitLabPayload.text(row,"kind",false))) {
                // Personal namespaces already exist and cannot contain subgroups.
                var user=parse(transport.get(urls.detail("/user"),access.token(),gate));
                gate.acceptedObjects(1);
                if(!path.equals(fullPath) || !path.equals(GitLabPayload.text(user,"username",true))) throw GitLabErrors.invalidConfiguration();
                return id(row,"id");
            }
            String receivedParent=row.path("parent_id").isNull() || row.path("parent_id").isMissingNode()?null:id(row,"parent_id");
            if(!java.util.Objects.equals(parent,receivedParent)) throw GitLabErrors.invalidResponse();
            parent=id(row,"id"); gate.acceptedObjects(1);
        }
        return parent;
    }
    private JsonNode parse(GitLabTransport.Response response) { try { var row=reader.readTree(response.body()); GitLabPayload.requireObject(row); return row; } catch(tools.jackson.core.JacksonException e) { throw GitLabErrors.invalidResponse(); } }
    private static void requireAccess(Access access) { if(access==null || !GitLabCredentials.validToken(access.token())) throw GitLabErrors.invalidConfiguration(); }
    private static void validate(String namespace,String name) { if(namespace==null || !namespace.matches("[A-Za-z0-9_][A-Za-z0-9_.-]{0,99}(?:/[A-Za-z0-9_][A-Za-z0-9_.-]{0,99}){0,19}") || name==null || !name.matches("[A-Za-z0-9_-][A-Za-z0-9_.-]{0,99}") || name.endsWith(".git")) throw GitLabErrors.invalidConfiguration(); }
    private static String id(JsonNode row,String name) { var value=row.path(name); if(!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue()<1) throw GitLabErrors.invalidResponse(); return value.asText(); }
    private static String encode(String value) { return URLEncoder.encode(value,StandardCharsets.UTF_8).replace("+","%20"); }
    private static boolean validBranch(String branch) { return branch!=null && branch.matches("[A-Za-z0-9_][A-Za-z0-9_./-]{0,199}") && !branch.contains("..") && !branch.endsWith("/") && !branch.endsWith(".lock"); }
    private static void requireUrl(URI base,String value,String path) { if(!value.equals(base.toString().replaceAll("/+$","")+"/"+path)) throw GitLabErrors.invalidResponse(); }
}
