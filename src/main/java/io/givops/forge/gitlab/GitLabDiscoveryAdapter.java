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
import io.givops.forge.port.ForgeRequestGate;
import io.givops.forge.port.ForgeAdapter.Access;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public class GitLabDiscoveryAdapter implements ForgeDiscoveryAdapter {
    private final GitLabTransport transport;
    private final ObjectMapper json;

    GitLabDiscoveryAdapter(GitLabTransport transport, ObjectMapper json) {
        this.transport = transport;
        this.json = json;
    }

    @Override public Support support(ForgeProvider provider, Hosting hosting) {
        boolean supported = provider == ForgeProvider.GITLAB && hosting == Hosting.SELF_MANAGED;
        return new Support(supported, supported);
    }

    @Override
    public RemotePage<RemoteGroup> groups(Server server, Access access, String query, int limit, int page,
                                         long lastId, ForgeRequestGate gate) {
        String token = access.token();
        var filters = filters(query);
        filters.put("all_available", "true");
        filters.put("top_level_only", "false");
        var urls = new GitLabUrls(server.baseUrl());
        // Authenticated /groups does not support GitLab keyset pagination.
        var response = readPage(urls, "/groups", filters, limit, page, token, gate);
        JsonNode rows = array(response, limit);
        var result = new ArrayList<RemoteGroup>();
        long previous = lastId;
        for (JsonNode row : rows) {
            long id = increasing(row, previous);
            previous = id;
            String name = text(row, "name", 1024);
            String path = text(row, "full_path", 512);
            String parent = optionalId(row, "parent_id");
            result.add(new RemoteGroup(Long.toString(id), name, path, parent,
                    DiscoveryValues.suggestedKey(path, Long.toString(id))));
        }
        gate.acceptedObjects(result.size());
        boolean next = GitLabPages.hasNext(urls, urls.uri("/groups", filters, limit, page),
                response.headers(), filters, page, limit, result.size());
        return new RemotePage<>(result, next, previous);
    }

    @Override
    public RemotePage<RemoteProject> projects(Server server, Access access, String groupId, String query,
                                             int limit, int page, long lastId, ForgeRequestGate gate) {
        String token = access.token();
        var filters = filters(query);
        String path;
        int remotePage;
        if (groupId == null) {
            path = "/projects";
            // Range traversal always uses offset page 1, including beyond 50,000 visible projects.
            if (lastId > 0) filters.put("id_after", Long.toString(lastId));
            remotePage = 1;
        } else {
            DiscoveryValues.numericId(groupId);
            path = "/groups/" + groupId + "/projects";
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
        String token = access.token();
        DiscoveryValues.numericId(externalId);
        var urls = new GitLabUrls(server.baseUrl());
        var row = parse(transport.get(urls.detail("/projects/" + externalId), token, gate));
        RemoteProject result = project(server, row);
        if (!externalId.equals(result.externalId())) throw GitLabErrors.invalidResponse();
        gate.acceptedObjects(1);
        return result;
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
