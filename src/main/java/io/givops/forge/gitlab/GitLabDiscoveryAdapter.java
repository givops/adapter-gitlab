package io.givops.forge.gitlab;


import io.givops.catalog.ForgeProvider;
import io.givops.forge.discovery.DiscoveryModels.Namespace;
import io.givops.forge.discovery.DiscoveryModels.RemoteGroup;
import io.givops.forge.discovery.DiscoveryModels.RemotePage;
import io.givops.forge.discovery.DiscoveryModels.RemoteProject;
import io.givops.forge.discovery.DiscoveryModels.Server;
import io.givops.forge.discovery.DiscoveryValues;
import io.givops.forge.model.ForgeConnectionSettings.Hosting;
import io.givops.forge.port.ForgeDiscoveryAdapter;
import io.givops.forge.port.ForgeGroupHierarchyAdapter;
import io.givops.forge.port.ForgeRequestGate;
import io.givops.forge.port.ForgeAdapter.Access;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public class GitLabDiscoveryAdapter implements ForgeDiscoveryAdapter, ForgeGroupHierarchyAdapter {
    private final GitLabTransport transport;
    private final ObjectMapper json;

    GitLabDiscoveryAdapter(GitLabTransport transport, ObjectMapper json) {
        this.transport = transport;
        this.json = json;
    }

    @Override public Support support(ForgeProvider provider, Hosting hosting) {
        boolean supported = supports(provider, hosting);
        return new Support(supported, supported);
    }

    @Override public boolean supports(ForgeProvider provider, Hosting hosting) {
        return provider == ForgeProvider.GITLAB && (hosting == Hosting.SELF_MANAGED || hosting == Hosting.SAAS);
    }

    @Override
    public RemotePage<RemoteGroup> groups(Server server, Access access, String query, int limit, int page,
                                         long lastId, ForgeRequestGate gate) {
        String token = authenticated(access);
        validatePage(query, limit, page, lastId);
        var filters = filters(query);
        filters.put("all_available", Boolean.toString(access.hosting() != Hosting.SAAS));
        filters.put("top_level_only", "false");
        var urls = new GitLabUrls(server.baseUrl());
        // Authenticated /groups does not support GitLab keyset pagination.
        var response = readPage(urls, "/groups", filters, limit, page, token, gate);
        return groupPage(urls, "/groups", filters, response, limit, page, lastId, null, gate);
    }

    @Override
    public RemoteGroup group(Server server, Access access, String externalId, ForgeRequestGate gate) {
        String token = authenticated(access);
        DiscoveryValues.numericId(externalId);
        var urls = new GitLabUrls(server.baseUrl());
        // Known ancestry only: the host derives this ID from an admitted namespace/parent.
        var result = group(parse(transport.get(urls.detail("/groups/" + externalId + "?with_projects=false"), token, gate)));
        if (!externalId.equals(result.externalId())) throw GitLabErrors.invalidResponse();
        gate.acceptedObjects(1);
        return result;
    }

    @Override
    public RemotePage<RemoteGroup> subgroups(Server server, Access access, String parentExternalId,
                                             String query, int limit, int page, long lastId, ForgeRequestGate gate) {
        String token = authenticated(access);
        DiscoveryValues.numericId(parentExternalId);
        validatePage(query, limit, page, lastId);
        var filters = filters(query);
        filters.put("all_available", Boolean.toString(access.hosting() != Hosting.SAAS));
        var urls = new GitLabUrls(server.baseUrl());
        String path = "/groups/" + parentExternalId + "/subgroups";
        var response = readPage(urls, path, filters, limit, page, token, gate);
        return groupPage(urls, path, filters, response, limit, page, lastId, parentExternalId, gate);
    }

    private RemotePage<RemoteGroup> groupPage(GitLabUrls urls, String path, Map<String, String> filters,
                                             GitLabTransport.Response response, int limit, int page, long lastId,
                                             String parentId, ForgeRequestGate gate) {
        JsonNode rows = array(response, limit);
        var result = new ArrayList<RemoteGroup>();
        long previous = lastId;
        for (JsonNode row : rows) {
            previous = increasing(row, previous);
            var group = group(row);
            if (parentId != null && !parentId.equals(group.parentExternalId())) throw GitLabErrors.invalidResponse();
            result.add(group);
        }
        gate.acceptedObjects(result.size());
        boolean next = GitLabPages.hasNext(urls, urls.uri(path, filters, limit, page),
                response.headers(), filters, page, limit, result.size());
        return new RemotePage<>(result, next, previous);
    }

    private RemoteGroup group(JsonNode row) {
        String id = requiredId(row, "id");
        String name = text(row, "name", 1024);
        String path = text(row, "full_path", 512);
        String parent = optionalId(row, "parent_id");
        if (id.equals(parent)) throw GitLabErrors.invalidResponse();
        return new RemoteGroup(id, name, path, parent, DiscoveryValues.suggestedKey(path, id));
    }

    @Override
    public RemotePage<RemoteProject> projects(Server server, Access access, String groupId, String query,
                                             int limit, int page, long lastId, ForgeRequestGate gate) {
        String token = authenticated(access);
        validatePage(query, limit, page, lastId);
        var filters = filters(query);
        String path;
        int remotePage;
        if (groupId == null) {
            path = "/projects";
            if (access.hosting() == Hosting.SAAS) filters.put("membership", "true");
            // Range traversal always uses offset page 1, including beyond 50,000 visible projects.
            if (lastId > 0) filters.put("id_after", Long.toString(lastId));
            remotePage = 1;
        } else {
            DiscoveryValues.numericId(groupId);
            path = "/groups/" + groupId + "/projects";
            if (access.hosting() == Hosting.SAAS) filters.put("min_access_level", "10");
            filters.put("with_shared", "false");
            filters.put("include_subgroups", "false");
            remotePage = page;
        }
        var urls = new GitLabUrls(server.baseUrl());
        var response = readPage(urls, path, filters, limit, remotePage, token, gate);
        JsonNode rows = array(response, limit);
        var result = new ArrayList<RemoteProject>();
        long previous = lastId;
        for (JsonNode row : rows) {
            previous = increasing(row, previous);
            var project = project(server, row);
            if (groupId != null && (!groupId.equals(project.namespace().externalId())
                    || !"group".equals(project.namespace().kind()))) throw GitLabErrors.invalidResponse();
            result.add(project);
        }
        gate.acceptedObjects(result.size());
        boolean next = GitLabPages.hasNext(urls, urls.uri(path, filters, limit, remotePage),
                response.headers(), filters, remotePage, limit, result.size());
        return new RemotePage<>(result, next, previous);
    }

    @Override
    public RemoteProject project(Server server, Access access, String externalId, ForgeRequestGate gate) {
        String token = authenticated(access);
        DiscoveryValues.numericId(externalId);
        var urls = new GitLabUrls(server.baseUrl());
        JsonNode row;
        if (access.hosting() == Hosting.SAAS) {
            // GET /projects/:id only proves visibility. Verify current account membership
            // using an exact bounded numeric range, including inherited/personal membership.
            long id = DiscoveryValues.numericId(externalId);
            var filters = filters("");
            filters.put("membership", "true");
            filters.put("id_after", Long.toString(id - 1));
            if (id < Long.MAX_VALUE) filters.put("id_before", Long.toString(id + 1));
            var response = readPage(urls, "/projects", filters, 1, 1, token, gate);
            JsonNode rows = array(response, 1);
            GitLabPages.hasNext(urls, urls.uri("/projects", filters, 1, 1), response.headers(), filters, 1, 1, rows.size());
            if (rows.isEmpty()) throw GitLabErrors.remoteStatus(403);
            row = rows.get(0);
        } else {
            row = parse(transport.get(urls.detail("/projects/" + externalId), token, gate));
        }
        RemoteProject result = project(server, row);
        if (!externalId.equals(result.externalId())) throw GitLabErrors.invalidResponse();
        gate.acceptedObjects(1);
        return result;
    }

    private String authenticated(Access access) {
        if (access == null || !supports(ForgeProvider.GITLAB, access.hosting())) throw GitLabErrors.invalidConfiguration();
        if (!GitLabCredentials.validToken(access.token())) throw GitLabErrors.remoteStatus(401);
        return access.token();
    }

    private static void validatePage(String query, int limit, int page, long lastId) {
        if (query == null || query.length() > 200 || query.codePoints().anyMatch(Character::isISOControl)
                || limit < 1 || limit > 100 || page < 1 || lastId < 0) throw GitLabErrors.invalidConfiguration();
    }

    private GitLabTransport.Response readPage(GitLabUrls urls, String path, Map<String, String> filters,
                                              int limit, int page, String token, ForgeRequestGate gate) {
        if ((long) (page - 1) * limit >= 50_000) {
            throw DiscoveryValues.error("discovery_pagination_limit", HttpStatus.UNPROCESSABLE_CONTENT,
                    GitLabMessages.format("forge.gitLabDiscoveryAdapter.the.gitlab.offset.pagination.limit.was.reached.narrow.the"));
        }
        return transport.get(urls.uri(path, filters, limit, page), token, gate);
    }

    private static LinkedHashMap<String, String> filters(String query) {
        var filters = new LinkedHashMap<String, String>();
        filters.put("order_by", "id");
        filters.put("sort", "asc");
        if (!query.isEmpty()) filters.put("search", query);
        return filters;
    }

    private RemoteProject project(Server server, JsonNode row) {
        GitLabPayload.requireObject(row);
        String id = requiredId(row, "id");
        String name = text(row, "name", 1024);
        String path = text(row, "path_with_namespace", 512);
        String clone = safeUrl(server.baseUrl(), text(row, "http_url_to_repo", 2048));
        String web = safeUrl(server.baseUrl(), text(row, "web_url", 2048));
        JsonNode ns = row.get("namespace");
        GitLabPayload.requireObject(ns);
        String kind = text(ns, "kind", 20);
        if (!kind.equals("group") && !kind.equals("user")) throw GitLabErrors.invalidResponse();
        var namespace = new Namespace(requiredId(ns, "id"), kind, text(ns, "name", 1024), text(ns, "full_path", 512));
        String fingerprint = DiscoveryValues.hash(json.writeValueAsString(
                java.util.List.of(id, name, path, clone, web, namespace)));
        return new RemoteProject(id, name, path, clone, web, namespace, fingerprint,
                DiscoveryValues.suggestedKey(path, id));
    }

    private JsonNode array(GitLabTransport.Response response, int limit) {
        JsonNode rows = parse(response);
        if (!rows.isArray() || rows.size() > limit) throw GitLabErrors.invalidResponse();
        return rows;
    }

    private JsonNode parse(GitLabTransport.Response response) {
        try {
            JsonNode row = json.readTree(response.body());
            if (row == null) throw GitLabErrors.invalidResponse();
            return row;
        } catch (tools.jackson.core.JacksonException exception) {
            throw GitLabErrors.invalidResponse();
        }
    }

    private static long increasing(JsonNode row, long previous) {
        long value = Long.parseLong(requiredId(row, "id"));
        if (value <= previous) throw GitLabErrors.invalidPagination();
        return value;
    }

    private static String requiredId(JsonNode node, String field) {
        GitLabPayload.requireObject(node);
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0) {
            throw GitLabErrors.invalidResponse();
        }
        return Long.toString(value.longValue());
    }

    private static String optionalId(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : requiredId(node, field);
    }

    private static String text(JsonNode node, String field, int max) {
        String value = GitLabPayload.text(node, field, true);
        if (value.length() > max || value.codePoints().anyMatch(Character::isISOControl)) {
            throw GitLabErrors.invalidResponse();
        }
        return value;
    }

    private static String safeUrl(URI base, String value) {
        try {
            URI uri = URI.create(value);
            String prefix = base.getRawPath().replaceAll("/$", "") + "/";
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || !uri.getHost().equalsIgnoreCase(base.getHost()) || port(uri) != port(base)
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || !uri.getRawPath().startsWith(prefix) || !uri.normalize().equals(uri)
                    || uri.getRawPath().contains("%") || uri.getRawPath().contains("\\")) {
                throw GitLabErrors.invalidResponse();
            }
            return value;
        } catch (IllegalArgumentException exception) {
            throw GitLabErrors.invalidResponse();
        }
    }

    private static int port(URI uri) { return uri.getPort() == -1 ? 443 : uri.getPort(); }
}
