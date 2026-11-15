package io.givops.forge.gitlab;

import io.givops.adapters.CapabilityInspection;
import io.givops.forge.model.ForgeException;
import io.givops.forge.port.ForgeRequestGate;
import io.givops.ticketing.config.TicketProperties;
import io.givops.ticketing.model.TicketException;
import io.givops.ticketing.model.TicketException.Code;
import io.givops.ticketing.model.TicketModels.InstanceSource;
import io.givops.ticketing.model.TicketModels.IssueIdentity;
import io.givops.ticketing.model.TicketModels.IssueObservation;
import io.givops.ticketing.model.TicketModels.ProjectObservation;
import io.givops.ticketing.model.TicketModels.ProjectQuery;
import io.givops.ticketing.model.TicketModels.ProjectResult;
import io.givops.ticketing.model.TicketModels.ProviderInput;
import io.givops.ticketing.model.TicketModels.ProviderSnapshot;
import io.givops.ticketing.model.TicketModels.ReleaseMappingState;
import io.givops.ticketing.model.TicketModels.ReleaseMembership;
import io.givops.ticketing.model.TicketModels.TicketProvider;
import io.givops.ticketing.model.TicketModels.VersionObservation;
import io.givops.ticketing.model.TicketWriteModels.CreateIssueCommand;
import io.givops.ticketing.model.TicketWriteModels.CreateProjectCommand;
import io.givops.ticketing.model.TicketWriteModels.CreateVersionCommand;
import io.givops.ticketing.model.TicketWriteModels.RemoteProjectRef;
import io.givops.ticketing.model.TicketWriteModels.WorkflowState;
import io.givops.ticketing.port.TicketCapabilityInspector;
import io.givops.ticketing.port.TicketProviderAdapter;
import io.givops.ticketing.port.TicketRequestGate;
import io.givops.ticketing.port.TicketWriteAdapter;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

/** Native issues and project milestones; all HTTP uses the same bounded GitLab transport as forge reads. */
public final class GitLabTickets implements TicketProviderAdapter, TicketWriteAdapter, TicketCapabilityInspector {
    private static final int PAGE_SIZE = 100;
    private final GitLabCredentials credentials;
    private final TicketProperties limits;
    private final GitLabTransport transport;
    private final JsonMapper mapper;
    private final ObjectReader reader;
    private final Clock clock;
    private final GitLabTicketDefinition definition;

    GitLabTickets(GitLabCredentials credentials, TicketProperties limits, GitLabTransport transport,
                  JsonMapper mapper, Clock clock, GitLabTicketDefinition definition) {
        this.credentials = credentials; this.limits = limits; this.transport = transport;
        this.mapper = mapper; this.clock = clock; this.definition = definition;
        this.reader = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION);
    }
    @Override public TicketProvider provider() { return TicketProvider.GITLAB; }
    @Override public String authenticationContext(InstanceSource source) { return captureAccess(source).context(); }
    @Override public Access captureAccess(InstanceSource source) { return translate(() -> credentials.capture(source)); }
    @Override public boolean current(InstanceSource source, Access access, boolean locked) {
        try { credentials.require(source, access, locked); return true; }
        catch (TicketException failure) {
            if (failure.code() == Code.SUPERSEDED || failure.code() == Code.STALE_CONTEXT) return false;
            throw failure;
        }
    }
    @Override public void requireCurrent(InstanceSource source, Access access, boolean locked) {
        credentials.require(source, access, locked);
    }
    @Override public ProviderSnapshot read(ProviderInput input, TicketRequestGate gate) {
        return read(input, gate, captureAccess(input.source()));
    }
    @Override public ProviderSnapshot read(ProviderInput input, TicketRequestGate gate, Access access) {
        return translate(() -> {
            Session session = session(input.source(), access, gate);
            if (input.projects().size() > limits.maxBindings()) throw limit();
            var result = new ArrayList<ProjectResult>();
            for (ProjectQuery query : input.projects()) result.add(session.read(query));
            session.gate.check();
            return new ProviderSnapshot(result);
        });
    }
    @Override public VersionMemberships readVersionMemberships(InstanceSource source, ProjectQuery query, Access access,
            TicketRequestGate gate, Function<List<VersionObservation>, Set<String>> select, int maximumVersions, int maximumIssues) {
        return translate(() -> {
            Session session = session(source, access, gate);
            ProjectObservation project = session.project(query.externalProjectId(), query.projectKey());
            List<VersionObservation> versions = session.versions(project.externalProjectId());
            Set<String> selected = Set.copyOf(select.apply(versions));
            if (selected.size() > maximumVersions) throw limit();
            Set<String> known = versions.stream().map(VersionObservation::externalVersionId).collect(java.util.stream.Collectors.toSet());
            if (!known.containsAll(selected)) throw invalid();
            var members = new LinkedHashMap<String,List<IssueObservation>>();
            int count = 0;
            for (String id : selected) {
                List<IssueObservation> issues = session.members(project, id);
                count += issues.size();
                if (count > maximumIssues) throw limit();
                members.put(id, issues);
            }
            session.gate.check();
            return new VersionMemberships(members);
        });
    }
    @Override public ProjectObservation createProject(InstanceSource source, CreateProjectCommand command, Access access, TicketRequestGate gate) {
        return translate(() -> {
            definition.validateCreateProjectKey(command.projectKey());
            if (command.displayName() == null || command.displayName().isBlank()) throw invalid();
            // Namespace ID is explicit; no implicit repository is created by binding an existing project.
            var payload = new LinkedHashMap<String,Object>();
            payload.put("path", command.projectKey()); payload.put("name", command.displayName());
            if (command.description() != null) payload.put("description", command.description());
            if (command.leaderExternalId() != null && !command.leaderExternalId().isBlank())
                payload.put("namespace_id", numeric(command.leaderExternalId()));
            Session session = session(source, access, gate);
            ProjectObservation created = session.project(session.post("/projects", payload), null, null);
            if (!created.projectKey().endsWith("/" + command.projectKey())) throw GitLabErrors.invalidResponse();
            return created;
        });
    }
    @Override public VersionObservation createVersion(InstanceSource source, RemoteProjectRef project, CreateVersionCommand command,
                                                     Access access, TicketRequestGate gate) {
        return translate(() -> {
            requireProjectInstance(source, project);
            if (command.released() || command.archived()) throw unsupported("GitLab milestones have native active/closed states; Jira release/archive commands are unsupported.");
            if (command.name() == null || command.name().isBlank()) throw invalid();
            var payload = new LinkedHashMap<String,Object>(); payload.put("title", command.name());
            if (command.releaseDate() != null) payload.put("due_date", command.releaseDate().toString());
            Session session = session(source, access, gate);
            ProjectObservation bound = session.project(project.externalProjectId(), project.projectKey());
            return session.version(session.post(GitLabUrls.project(bound.externalProjectId()) + "/milestones", payload), bound.externalProjectId());
        });
    }
    @Override public IssueObservation createIssue(InstanceSource source, RemoteProjectRef project, CreateIssueCommand command,
                                                 Access access, TicketRequestGate gate) {
        return translate(() -> {
            requireProjectInstance(source, project);
            if (command.summary() == null || command.summary().isBlank() || command.fixVersions().size() > 1) throw invalid();
            if (command.workflowState() != null && command.workflowState() != WorkflowState.OPEN
                    || command.providerStatus() != null && !command.providerStatus().isBlank())
                throw unsupported("GitLab issue creation supports the native opened state.");
            if (command.providerPriority() != null && !command.providerPriority().isBlank())
                throw unsupported("GitLab issues have no native priority field; use the portable priority label.");
            String nativeType = command.providerType() == null || command.providerType().isBlank() ? "issue" : command.providerType();
            if (!Set.of("issue", "incident", "test_case", "task").contains(nativeType)) throw unsupported("Unsupported GitLab issue type.");
            Session session = session(source, access, gate);
            ProjectObservation bound = session.project(project.externalProjectId(), project.projectKey());
            var payload = new LinkedHashMap<String,Object>();
            payload.put("title", command.summary()); payload.put("issue_type", nativeType);
            if (command.description() != null) payload.put("description", command.description());
            var labels = new ArrayList<String>();
            if (command.type() != null) labels.add("type::" + command.type().name().toLowerCase(java.util.Locale.ROOT));
            if (command.priority() != null) labels.add("priority::" + command.priority().name().toLowerCase(java.util.Locale.ROOT));
            if (!labels.isEmpty()) payload.put("labels", String.join(",", labels));
            if (!command.fixVersions().isEmpty()) {
                String id = numeric(command.fixVersions().getFirst());
                if (session.versions(bound.externalProjectId()).stream().noneMatch(v -> id.equals(v.externalVersionId()))) throw invalid();
                payload.put("milestone_id", Long.parseLong(id));
            }
            String assignee = command.assigneeExternalId();
            if (command.assignToSelf()) assignee = id(session.get("/user"), "id");
            if (assignee != null && !assignee.isBlank()) payload.put("assignee_ids", List.of(Long.parseLong(numeric(assignee))));
            return session.issue(session.post(GitLabUrls.project(bound.externalProjectId()) + "/issues", payload), bound);
        });
    }
    @Override public CapabilityInspection inspect(InstanceSource source, String projectId, String capability, Access access, TicketRequestGate gate) {
        Session session = session(source, access, gate);
        String product = null;
        try { product = GitLabVersions.read(transport, session.urls, session.captured.token(), session.gate, reader).version(); }
        catch (ForgeException failure) { /* Version metadata is optional; resource evidence remains scoped. */ }
        CapabilityInspection.State state;
        String reason;
        if (!definition.capabilities().contains(capability)) {
            state = CapabilityInspection.State.UNSUPPORTED; reason = "operation_not_implemented";
        } else try {
            if (projectId == null || projectId.isBlank()) { state = CapabilityInspection.State.UNKNOWN; reason = "project_not_selected"; }
            else {
                JsonNode project = session.projectResource(GitLabUrls.project(projectId));
                if (!projectId.equals(id(project, "id")) && !projectId.equals(text(project, "path_with_namespace", true)))
                    throw GitLabErrors.invalidResponse();
                if (project.path("issues_enabled").isBoolean() && !project.path("issues_enabled").booleanValue()
                        || "disabled".equals(project.path("issues_access_level").asText())) {
                    state = CapabilityInspection.State.DISABLED; reason = "project_issues_disabled";
                } else if (capability.endsWith("-create")) {
                    state = CapabilityInspection.State.UNKNOWN; reason = "write_permission_requires_explicit_command";
                } else {
                    session.collection(GitLabUrls.project(projectId) + (capability.endsWith("version-membership") ? "/milestones" : "/issues"),
                            Map.of(), 1, 1, true, true);
                    state = CapabilityInspection.State.AVAILABLE; reason = "project_endpoint_readable";
                }
            }
        } catch (ForgeException failure) {
            state = switch (failure.getCode()) {
                case "gitlab_forbidden", "gitlab_unauthorized" -> CapabilityInspection.State.FORBIDDEN;
                case "gitlab_timeout", "gitlab_transport_failed", "gitlab_rate_limited", "gitlab_unavailable" -> CapabilityInspection.State.TEMPORARILY_UNAVAILABLE;
                default -> CapabilityInspection.State.UNKNOWN;
            };
            reason = failure.getCode();
        }
        return new CapabilityInspection("GITLAB", capability, state, reason, product, "gitlab-rest-v4", access.context(), clock.instant());
    }
    private Session session(InstanceSource source, Access access, TicketRequestGate gate) {
        return new Session(credentials.require(source, access, false), gate);
    }
    private final class Session {
        private final GitLabCredentials.Captured captured;
        private final GitLabUrls urls;
        private final ForgeRequestGate gate;
        Session(GitLabCredentials.Captured captured, TicketRequestGate host) {
            this.captured = captured; this.urls = new GitLabUrls(captured.source().baseUrl());
            this.gate = new ForgeRequestGate() {
                @Override public Duration beforeRequest() { credentials.require(captured.source(), captured, false); return host.beforeRequest(); }
                @Override public void receivedBytes(long count) { host.receivedBytes(count); }
                @Override public void acceptedObjects(int count) { host.acceptedObjects(count); }
                @Override public void check() { credentials.require(captured.source(), captured, false); host.check(); }
            };
        }
        JsonNode get(String path) {
            JsonNode result = parse(transport.get(urls.detail(path), captured.token(), gate));
            gate.acceptedObjects(1);
            return result;
        }
        JsonNode projectResource(String path) {
            JsonNode result = parse(projectResponse(urls.detail(path)));
            gate.acceptedObjects(1);
            return result;
        }
        GitLabTransport.Response projectResponse(URI uri) {
            var response = transport.getForCheck(uri, captured.token(), gate);
            // GitLab hides inaccessible projects and their collection endpoints behind 404.
            // Treat this scoped denial conservatively so the host invalidates private observations.
            if (response.status() == 404) throw GitLabErrors.remoteStatus(403);
            if (response.status() != 200) throw GitLabErrors.remoteStatus(response.status());
            return response;
        }
        JsonNode post(String path, Object payload) {
            byte[] body = mapper.writeValueAsBytes(payload);
            if (body.length > 65_536) throw limit();
            var response = transport.post(urls.detail(path), captured.token(), body, gate, 201);
            if (response.status() != 201) throw GitLabErrors.remoteStatus(response.status());
            JsonNode result = parse(response);
            gate.acceptedObjects(1);
            return result;
        }
        JsonNode parse(GitLabTransport.Response response) {
            try {
                JsonNode node = reader.readTree(response.body());
                if (node == null || node.isMissingNode()) throw GitLabErrors.invalidResponse();
                return node;
            } catch (JacksonException failure) { throw GitLabErrors.invalidResponse(); }
        }
        ProjectObservation project(String externalId, String key) {
            definition.validateProject(externalId, key);
            return project(projectResource(GitLabUrls.project(externalId)), externalId, key);
        }
        ProjectObservation project(JsonNode row, String externalId, String key) {
            String receivedId = id(row, "id"); String path = text(row, "path_with_namespace", true);
            definition.validateProject(receivedId, path);
            if (externalId != null && !externalId.equals(receivedId) && !externalId.equals(path)
                    || key != null && !key.equals(path)) throw GitLabErrors.invalidResponse();
            return new ProjectObservation(receivedId, path, text(row, "name", true), safeUrl(row, "web_url", "/" + path), clock.instant());
        }
        List<JsonNode> collection(String path, Map<String,String> filters, int pageSize, int maximum, boolean probe, boolean projectAccessRequired) {
            var result = new ArrayList<JsonNode>();
            for (int page = 1; page <= limits.maxPages(); page++) {
                URI uri = urls.uri(path, filters, pageSize, page);
                var response = projectAccessRequired ? projectResponse(uri) : transport.get(uri, captured.token(), gate);
                JsonNode rows = parse(response);
                if (!rows.isArray() || rows.size() > pageSize) throw GitLabErrors.invalidResponse();
                gate.acceptedObjects(rows.size());
                rows.forEach(result::add);
                if (result.size() > maximum) throw limit();
                if (probe || !GitLabPages.hasNext(urls, uri, response.headers(), filters, page, pageSize, rows.size())) return result;
            }
            throw limit();
        }
        List<VersionObservation> versions(String project) {
            var result = new ArrayList<VersionObservation>(); var ids = new LinkedHashSet<String>();
            for (JsonNode row : collection(GitLabUrls.project(project) + "/milestones", Map.of("include_parent_milestones", "false"), PAGE_SIZE, limits.maxObjects(), false, true)) {
                VersionObservation version = version(row, project);
                if (!ids.add(version.externalVersionId())) throw GitLabErrors.invalidPagination();
                result.add(version);
            }
            return List.copyOf(result);
        }
        VersionObservation version(JsonNode row, String project) {
            if (!project.equals(id(row, "project_id"))) throw GitLabErrors.invalidResponse();
            String state = text(row, "state", true);
            if (!state.equals("active") && !state.equals("closed")) throw GitLabErrors.invalidResponse();
            return new VersionObservation(id(row, "id"), text(row, "title", true), false, false,
                    date(row, "due_date"), clock.instant(), state, date(row, "start_date"), null);
        }
        IssueObservation issue(JsonNode row, ProjectObservation project) {
            if (!project.externalProjectId().equals(id(row, "project_id"))) throw GitLabErrors.invalidResponse();
            String iid = id(row, "iid"); String state = text(row, "state", true);
            if (!state.equals("opened") && !state.equals("closed")) throw GitLabErrors.invalidResponse();
            List<String> milestones = row.path("milestone").isObject() ? List.of(id(row.path("milestone"), "id")) : List.of();
            var labels = new ArrayList<String>();
            JsonNode labelNodes = row.get("labels");
            if (labelNodes != null && !labelNodes.isNull()) {
                if (!labelNodes.isArray()) throw GitLabErrors.invalidResponse();
                for (JsonNode label : labelNodes) {
                    if (!label.isTextual()) throw GitLabErrors.invalidResponse();
                    labels.add(label.stringValue());
                }
            }
            String assignee = row.path("assignee").isObject() ? id(row.path("assignee"), "id") : null;
            if (assignee == null && row.path("assignees").isArray() && !row.path("assignees").isEmpty()) assignee = id(row.path("assignees").get(0), "id");
            return new IssueObservation(new IssueIdentity(project.externalProjectId(), id(row, "id")),
                    project.projectKey() + "#" + iid, text(row, "title", true), text(row, "issue_type", false), state,
                    state.equals("closed") ? "DONE" : "TODO", null, null, instant(row, "created_at"), instant(row, "updated_at"),
                    instant(row, "closed_at"), milestones, safeUrl(row, "web_url", "/" + project.projectKey() + "/-/issues/" + iid),
                    clock.instant(), assignee, text(row, "description", false), labels);
        }
        List<IssueObservation> members(ProjectObservation project, String milestone) {
            var issues = new ArrayList<IssueObservation>(); var ids = new LinkedHashSet<IssueIdentity>();
            for (JsonNode row : collection(GitLabUrls.project(project.externalProjectId()) + "/milestones/" + numeric(milestone) + "/issues",
                    Map.of(), PAGE_SIZE, limits.maxReleaseIssues(), false, false)) {
                IssueObservation issue = issue(row, project);
                if (!issue.releaseVersionIds().contains(milestone) || !ids.add(issue.identity())) throw GitLabErrors.invalidResponse();
                issues.add(issue);
            }
            return List.copyOf(issues);
        }
        ProjectResult read(ProjectQuery query) {
            if (query.issueKeys().size() > limits.maxIssueKeys()) throw limit();
            ProjectObservation project = project(query.externalProjectId(), query.projectKey());
            List<VersionObservation> versions = versions(project.externalProjectId());
            var issues = new LinkedHashMap<IssueIdentity,IssueObservation>(); var unresolved = new LinkedHashSet<String>();
            for (String key : query.issueKeys()) {
                var normalized = definition.normalizeIssueKey(key, project.projectKey());
                if (normalized.isEmpty()) { unresolved.add(key); continue; }
                String iid = normalized.get().substring(project.projectKey().length() + 1);
                try {
                    IssueObservation issue = issue(get(GitLabUrls.project(project.externalProjectId()) + "/issues/" + iid), project);
                    if (!normalized.get().equals(issue.issueKey())) throw GitLabErrors.invalidResponse();
                    issues.put(issue.identity(), issue);
                } catch (ForgeException failure) {
                    if (failure.getCode().equals("gitlab_not_found")) unresolved.add(key); else throw failure;
                }
            }
            ReleaseMembership membership = new ReleaseMembership(ReleaseMappingState.NOT_REQUESTED, null, List.of(), clock.instant());
            if (query.releaseVersionName() != null && !query.releaseVersionName().isBlank()) {
                List<VersionObservation> matches = versions.stream().filter(v -> query.releaseVersionName().equals(v.name())).toList();
                if (matches.size() != 1) membership = new ReleaseMembership(matches.isEmpty() ? ReleaseMappingState.NOT_FOUND : ReleaseMappingState.AMBIGUOUS, null, List.of(), clock.instant());
                else {
                    String milestone = matches.getFirst().externalVersionId();
                    List<IssueObservation> members = members(project, milestone);
                    members.forEach(issue -> issues.put(issue.identity(), issue));
                    membership = new ReleaseMembership(ReleaseMappingState.MATCHED, milestone, members.stream().map(IssueObservation::identity).toList(), clock.instant());
                }
            }
            return new ProjectResult(query.ticketProjectId(), project, versions, List.copyOf(issues.values()), unresolved, membership);
        }
        URI safeUrl(JsonNode row, String field, String path) {
            String value = text(row, field, true);
            try {
                URI uri = URI.create(value); URI base = captured.source().baseUrl();
                String expected = base.getPath().replaceAll("/+$", "") + path;
                if (!"https".equalsIgnoreCase(uri.getScheme()) || !base.getHost().equalsIgnoreCase(uri.getHost())
                        || port(base) != port(uri) || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                        || uri.getRawFragment() != null || !expected.equals(uri.getRawPath())) throw GitLabErrors.invalidResponse();
                return uri;
            } catch (IllegalArgumentException failure) { throw GitLabErrors.invalidResponse(); }
        }
    }
    private static void requireProjectInstance(InstanceSource source, RemoteProjectRef project) {
        if (project == null || !source.instanceId().equals(project.instanceId()))
            throw GitLabCredentials.error(Code.STALE_CONTEXT, "GitLab project belongs to another connection.");
    }
    private static int port(URI uri) { return uri.getPort() == -1 ? 443 : uri.getPort(); }
    private static String text(JsonNode row, String field, boolean required) { return GitLabPayload.text(row, field, required); }
    private static String id(JsonNode row, String field) {
        JsonNode value = row.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 1) throw GitLabErrors.invalidResponse();
        return value.asText();
    }
    private static String numeric(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw invalid();
        try { Long.parseLong(value); } catch (NumberFormatException failure) { throw invalid(); }
        return value;
    }
    private static Instant instant(JsonNode row, String field) {
        String value = text(row, field, false);
        try { return value == null ? null : Instant.parse(value); }
        catch (DateTimeParseException failure) { throw GitLabErrors.invalidResponse(); }
    }
    private static LocalDate date(JsonNode row, String field) {
        String value = text(row, field, false);
        try { return value == null ? null : LocalDate.parse(value); }
        catch (DateTimeParseException failure) { throw GitLabErrors.invalidResponse(); }
    }
    private static TicketException invalid() { return GitLabCredentials.error(Code.INVALID_CONFIGURATION, "Invalid GitLab ticketing command or binding."); }
    private static TicketException limit() { return GitLabCredentials.error(Code.LIMIT_EXCEEDED, "GitLab ticketing exceeded its bounded resource budget."); }
    private static TicketException unsupported(String message) { return GitLabCredentials.error(Code.UNSUPPORTED_PROVIDER, message); }
    private static <T> T translate(Supplier<T> operation) {
        try { return operation.get(); }
        catch (ForgeException failure) {
            Code code = switch (failure.getCode()) {
                case "gitlab_unauthorized" -> Code.AUTHENTICATION_FAILED;
                case "gitlab_forbidden" -> Code.AUTHORIZATION_FAILED;
                case "gitlab_not_found" -> Code.NOT_FOUND;
                case "gitlab_timeout" -> Code.TIMEOUT;
                case "gitlab_rate_limited" -> Code.RATE_LIMITED;
                case "forge_budget_exhausted" -> Code.LIMIT_EXCEEDED;
                case "gitlab_invalid_response", "gitlab_invalid_pagination" -> Code.INVALID_RESPONSE;
                case "gitlab_configuration_invalid" -> Code.INVALID_CONFIGURATION;
                default -> Code.PROVIDER_ERROR;
            };
            throw new TicketException(code, failure.getMessage());
        }
    }
}
