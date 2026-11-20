package io.givops.forge.gitlab;


import io.givops.catalog.ForgeProvider;
import io.givops.forge.config.ForgeProperties;
import io.givops.forge.model.ForgeException;
import io.givops.forge.model.ForgeModels.Branch;
import io.givops.forge.model.ForgeModels.BranchPipeline;
import io.givops.forge.model.ForgeModels.CachedAssociations;
import io.givops.forge.model.ForgeModels.Capabilities;
import io.givops.forge.model.ForgeModels.Capability;
import io.givops.forge.model.ForgeModels.CapabilityState;
import io.givops.forge.model.ForgeModels.CommitAssociations;
import io.givops.forge.model.ForgeModels.ChangeRequest;
import io.givops.forge.model.ForgeModels.ChangeRequestKey;
import io.givops.forge.model.ForgeModels.Pipeline;
import io.givops.forge.model.ForgeModels.PipelineCoverage;
import io.givops.forge.model.ForgeModels.PipelineCoverageState;
import io.givops.forge.model.ForgeModels.RefreshInput;
import io.givops.forge.model.ForgeModels.Selection;
import io.givops.forge.model.ForgeModels.Settings;
import io.givops.forge.model.ForgeModels.Snapshot;
import io.givops.forge.model.ForgeModels.Source;
import io.givops.forge.model.ForgeModels.TaskSignals;
import io.givops.forge.port.ForgeAdapter;
import io.givops.forge.port.ForgeRequestGate;
import io.givops.forge.port.OpenChangeRequestReader;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

public final class GitLabAdapter implements ForgeAdapter, OpenChangeRequestReader {
    private static final Pattern FULL_SHA = Pattern.compile("(?:[0-9a-fA-F]{40}|[0-9a-fA-F]{64})");
    private static final String HEADS = "refs/heads/";
    private static final String UNKNOWN = "UNKNOWN";
    private static final int PAGE_SIZE = 100;
    private static final int PIPELINE_PAGE_SIZE = 20;
    private final ForgeProperties limits;
    private final GitLabTransport transport;
    private final ObjectReader json;
    private final Clock clock;

    GitLabAdapter(ForgeProperties limits, GitLabTransport transport, JsonMapper mapper, Clock clock) {
        this.limits = limits;
        this.transport = transport;
        this.clock = clock;
        json = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION);
    }

    @Override
    public ForgeProvider provider() {
        return ForgeProvider.GITLAB;
    }

    @Override
    public boolean supports(URI baseUrl) {
        try {
            new GitLabUrls(baseUrl);
            return true;
        } catch (ForgeException exception) {
            return false;
        }
    }

    @Override
    public Snapshot read(RefreshInput input, ForgeRequestGate gate, Access access) {
        if (!input.settings().enabled()) {
            throw new ForgeException("forge_disabled", HttpStatus.CONFLICT, GitLabMessages.format("forge.gitLabAdapter.forge.access.is.disabled"));
        }
        gate.check();
        if (input.selection().branches().size() > limits.maxBranches()) {
            throw GitLabErrors.budgetExceeded();
        }
        return new Read(input.source(), input.cachedAssociations(), gate, access).snapshot(input.selection());
    }

    @Override
    public BranchPipeline readBranchPipeline(Source source, Settings settings, Branch branch,
                                             ForgeRequestGate gate, Access access) {
        if (!settings.enabled()) {
            throw new ForgeException("forge_disabled", HttpStatus.CONFLICT, GitLabMessages.format("forge.gitLabAdapter.forge.access.is.disabled"));
        }
        gate.check();
        String numericProject = source.projectId().matches("[1-9][0-9]*") ? source.projectId() : null;
        var observed = new Read(source, Map.of(), gate, access).branchPipeline(branch, numericProject);
        return new BranchPipeline(observed.ref(), observed.sha(), observed.pipeline(), List.of(), List.of(),
                new PipelineCoverage(PipelineCoverageState.OBSERVED, "exact_ref_sha_pipelines_endpoint"));
    }

    @Override
    public List<ChangeRequest> readOpenChangeRequests(Source source, ForgeRequestGate gate, Access access) {
        gate.check();
        return new Read(source, Map.of(), gate, access).openChangeRequests();
    }

    private final class Read {
        private final Map<String, CachedAssociations> cachedAssociations;
        private final ForgeRequestGate gate;
        private final Access access;
        private final GitLabUrls urls;
        private final String projectPath;
        private final Map<String, Capability> operations = new LinkedHashMap<>();
        private final Map<ChangeRequestKey, SeenMergeRequest> changeRequests = new LinkedHashMap<>();
        private int edges;

        private Read(Source source, Map<String, CachedAssociations> cachedAssociations, ForgeRequestGate gate,
                     Access access) {
            this.cachedAssociations = cachedAssociations;
            this.gate = gate;
            this.access = access;
            urls = new GitLabUrls(source.baseUrl());
            projectPath = GitLabUrls.project(source.projectId());
        }

        private List<ChangeRequest> openChangeRequests() {
            // wip=no also supports GitLab versions predating the draft filter introduced in 19.0.
            var filters = new LinkedHashMap<String, String>();
            filters.put("state", "opened");
            filters.put("scope", "all");
            filters.put("wip", "no");
            readCollection(projectPath + "/merge_requests", filters, (item, observedAt) -> {
                GitLabPayload.requireObject(item);
                if (!"opened".equals(GitLabPayload.text(item, "state", true))) return;
                Boolean draft = GitLabPayload.flag(item, "draft");
                Boolean legacyDraft = GitLabPayload.flag(item, "work_in_progress");
                if (Boolean.TRUE.equals(draft) || Boolean.TRUE.equals(legacyDraft)) return;
                if (draft == null && legacyDraft == null) throw GitLabErrors.invalidResponse();
                merge(new SeenMergeRequest(GitLabPayload.changeRequest(item, observedAt, false),
                        Observation.OPEN, false));
            });
            gate.check();
            return changeRequests.values().stream().map(SeenMergeRequest::value).toList();
        }

        private Snapshot snapshot(Selection selection) {
            Metadata metadata = metadata();
            openMergeRequests();
            var associations = new ArrayList<CommitAssociations>();
            for (String sha : selection.commitShas()) {
                associations.add(associations(sha));
            }
            for (var entry : changeRequests.entrySet()) {
                SeenMergeRequest observed = entry.getValue();
                if (!observed.hasHeadPipelineField()
                        || observed.source() == Observation.CACHE && "opened".equals(observed.value().state())) {
                    entry.setValue(readMrDetail(observed));
                }
            }
            var branches = new ArrayList<BranchPipeline>();
            for (Branch branch : selection.branches()) {
                branches.add(branchPipeline(branch, null));
            }
            var observedChanges = changeRequests.values().stream()
                    .map(SeenMergeRequest::value).map(this::withTaskSignals).toList();
            unknownIfUnobserved("commitAssociations");
            unknownIfUnobserved("mergeRequestPipelines");
            unknownIfUnobserved("branchPipelines");
            gate.check();
            var capabilities = new Capabilities("GitLab", metadata.version(), metadata.edition(),
                    access.hosting().name(), metadata.tier(), operations, clock.instant());
            return new Snapshot(capabilities, observedChanges, associations, branches);
        }

        private ChangeRequest withTaskSignals(ChangeRequest change) {
            if (!"opened".equals(change.state())) return change;
            String path = GitLabUrls.project(change.key().providerRepositoryId()) + "/merge_requests/" + change.key().localId();
            Integer unresolved = unresolvedDiscussions(path + "/discussions");
            Set<String> approved = approvals(path + "/approvals");
            String status = change.taskSignals() == null ? null : change.taskSignals().mergeStatus();
            return change.withTaskSignals(GitLabPayload.signals(unresolved, approved, approved != null, status, change.headPipeline()));
        }

        private Integer unresolvedDiscussions(String path) {
            var threads = new LinkedHashSet<String>();
            var unresolved = new LinkedHashSet<String>();
            try {
                readCollection(path, Map.of(), (row, observedAt) -> {
                    GitLabPayload.requireObject(row);
                    String id = GitLabPayload.text(row, "id", true);
                    Boolean individual = GitLabPayload.flag(row, "individual_note");
                    JsonNode notes = row.get("notes");
                    if (!threads.add(id) || individual == null || notes == null || !notes.isArray() || notes.isEmpty()) {
                        throw GitLabErrors.invalidResponse();
                    }
                    gate.acceptedObjects(notes.size());
                    if (!individual && GitLabPayload.hasUnresolvedNote(notes)) unresolved.add(id);
                });
                return unresolved.size();
            } catch (ForgeException exception) {
                if (optionalAccessUnavailable(exception)) return null;
                throw exception;
            }
        }

        private Set<String> approvals(String path) {
            try {
                JsonNode result = body(transport.get(urls.detail(path), access.token(), gate));
                GitLabPayload.requireObject(result);
                JsonNode rows = result.get("approved_by");
                if (rows == null || !rows.isArray()) throw GitLabErrors.invalidResponse();
                gate.acceptedObjects(rows.size() + 1);
                return GitLabPayload.approvedProviderIds(rows);
            } catch (ForgeException exception) {
                if (optionalAccessUnavailable(exception)) return null;
                throw exception;
            }
        }

        private Metadata metadata() {
            try {
                var profile = GitLabVersions.read(transport, urls, access.token(), gate, json);
                String version = profile.version();
                Boolean enterprise = profile.enterprise();
                String plan = profile.plan();
                boolean hasPlan = plan != null && !plan.isBlank();
                record("metadata", CapabilityState.AVAILABLE, "metadata_endpoint");
                record("instanceLicensePlan", hasPlan ? CapabilityState.AVAILABLE : CapabilityState.UNKNOWN,
                        hasPlan ? "metadata_instance_plan" : "metadata_plan_not_reported");
                return new Metadata(version, enterprise == null ? UNKNOWN : enterprise ? "EE" : "CE",
                        hasPlan ? plan : UNKNOWN);
            } catch (ForgeException exception) {
                CapabilityState state = switch (exception.getCode()) {
                    case "gitlab_unauthorized", "gitlab_forbidden" -> CapabilityState.FORBIDDEN;
                    case "gitlab_timeout", "gitlab_rate_limited", "gitlab_unavailable", "gitlab_transport_failed" ->
                            CapabilityState.TEMPORARILY_UNAVAILABLE;
                    case "gitlab_not_found", "gitlab_invalid_response", "gitlab_request_rejected" ->
                            CapabilityState.UNKNOWN;
                    default -> throw exception;
                };
                record("metadata", state, exception.getCode());
                record("instanceLicensePlan", state, exception.getCode());
                return new Metadata(null, UNKNOWN, UNKNOWN);
            }
        }

        private void openMergeRequests() {
            readCollection(projectPath + "/merge_requests", Map.of("state", "opened"), (item, observedAt) -> {
                SeenMergeRequest mr = parseMr(item, observedAt, Observation.OPEN);
                if (!"opened".equals(mr.value().state())) {
                    throw GitLabErrors.invalidResponse();
                }
                merge(mr);
            });
            record("openMergeRequests", CapabilityState.AVAILABLE, "open_merge_requests_endpoint");
        }

        private CommitAssociations associations(String sha) {
            requireSha(sha);
            var keys = new LinkedHashSet<ChangeRequestKey>();
            var cached = cachedAssociations.get(sha);
            if (cached != null) {
                gate.acceptedObjects(cached.changeRequests().size());
                for (ChangeRequest mr : cached.changeRequests()) {
                    addAssociation(keys, new SeenMergeRequest(mr, Observation.CACHE, true));
                }
                return new CommitAssociations(sha, List.copyOf(keys), cached.observedAt());
            }
            readCollection(projectPath + "/repository/commits/" + sha + "/merge_requests", Map.of(),
                    (item, observedAt) -> addAssociation(keys, parseMr(item, observedAt, Observation.ASSOCIATION)));
            record("commitAssociations", CapabilityState.AVAILABLE, "commit_merge_requests_endpoint");
            return new CommitAssociations(sha, List.copyOf(keys), clock.instant());
        }

        private SeenMergeRequest parseMr(JsonNode node, Instant observedAt, Observation source) {
            ChangeRequest mr = GitLabPayload.changeRequest(node, observedAt);
            if (mr.headPipeline() != null) {
                gate.acceptedObjects(1);
            }
            boolean hasHeadPipelineField = node.has("head_pipeline");
            if (hasHeadPipelineField) {
                operations.putIfAbsent("mergeRequestPipelines", new Capability(CapabilityState.AVAILABLE,
                        "embedded_head_pipeline", observedAt));
            }
            return new SeenMergeRequest(mr, source, hasHeadPipelineField);
        }

        private void addAssociation(LinkedHashSet<ChangeRequestKey> keys, SeenMergeRequest mr) {
            if (keys.add(mr.value().key()) && ++edges > limits.maxAssociations()) {
                throw GitLabErrors.budgetExceeded();
            }
            merge(mr);
        }

        private void merge(SeenMergeRequest observation) {
            ChangeRequest mr = observation.value();
            Observation source = observation.source();
            SeenMergeRequest previous = changeRequests.get(mr.key());
            if (previous == null) {
                if (changeRequests.size() >= limits.maxMergeRequests()) {
                    throw GitLabErrors.budgetExceeded();
                }
                changeRequests.put(mr.key(), observation);
            } else if (source.ordinal() > previous.source().ordinal()
                    || source == previous.source() && mr.observedAt().isAfter(previous.value().observedAt())) {
                changeRequests.put(mr.key(), observation);
            }
        }

        private SeenMergeRequest readMrDetail(SeenMergeRequest observed) {
            ChangeRequestKey key = observed.value().key();
            // Only this field identifies GitLab's current MR head pipeline. The newest item in
            // pipeline history may be stale, and a merged-results head may use a synthetic SHA.
            var uri = urls.detail(GitLabUrls.project(key.providerRepositoryId()) + "/merge_requests/" + key.localId());
            JsonNode detail = body(transport.get(uri, access.token(), gate));
            gate.acceptedObjects(1);
            SeenMergeRequest result = parseMr(detail, clock.instant(), observed.source());
            if (!result.value().key().equals(key) || !result.hasHeadPipelineField()) {
                throw GitLabErrors.invalidResponse();
            }
            record("mergeRequestPipelines", CapabilityState.AVAILABLE, "merge_request_detail_head_pipeline");
            return result;
        }

        private BranchPipeline branchPipeline(Branch branch, String expectedProjectId) {
            requireSha(branch.sha());
            if (branch.ref() == null || !branch.ref().startsWith(HEADS) || branch.ref().length() <= HEADS.length()) {
                throw GitLabErrors.invalidConfiguration();
            }
            String shortRef = branch.ref().substring(HEADS.length());
            Pipeline pipeline = findBranchPipeline(shortRef, branch.sha(), expectedProjectId);
            record("branchPipelines", CapabilityState.AVAILABLE, "exact_ref_sha_pipelines_endpoint");
            return new BranchPipeline(branch.ref(), branch.sha(), pipeline);
        }

        private Pipeline findBranchPipeline(String ref, String sha, String expectedProjectId) {
            var filters = Map.of("ref", ref, "sha", sha, "order_by", "id", "sort", "desc");
            long previousId = 0;
            for (int page = 1; page <= limits.maxPages(); page++) {
                var request = urls.uri(projectPath + "/pipelines", filters, PIPELINE_PAGE_SIZE, page);
                var response = transport.get(request, access.token(), gate);
                JsonNode rows = array(response, PIPELINE_PAGE_SIZE);
                Instant observedAt = clock.instant();
                for (JsonNode row : rows) {
                    Pipeline candidate = GitLabPayload.pipeline(row, observedAt);
                    long id = Long.parseLong(candidate.key().id());
                    if (!matchesHead(candidate, ref, sha, expectedProjectId) || previousId != 0 && id >= previousId) {
                        throw GitLabErrors.invalidResponse();
                    }
                    previousId = id;
                    // The list filters use a short ref, which may name both a branch and a tag.
                    // The project-scoped detail exposes the tag flag on both GitLab 17 and newer.
                    var uri = urls.detail(projectPath + "/pipelines/" + candidate.key().id());
                    JsonNode detail = body(transport.get(uri, access.token(), gate));
                    gate.acceptedObjects(1);
                    Pipeline pipeline = GitLabPayload.pipeline(detail, clock.instant());
                    Boolean tag = GitLabPayload.flag(detail, "tag");
                    if (tag == null || !pipeline.key().equals(candidate.key())
                            || !matchesHead(pipeline, ref, sha, expectedProjectId)) {
                        throw GitLabErrors.invalidResponse();
                    }
                    if (!tag) {
                        return pipeline;
                    }
                }
                if (!GitLabPages.hasNext(urls, request, response.headers(), filters, page,
                        PIPELINE_PAGE_SIZE, rows.size())) {
                    return null;
                }
            }
            throw GitLabErrors.budgetExceeded();
        }

        private boolean matchesHead(Pipeline pipeline, String ref, String sha, String expectedProjectId) {
            return ref.equals(pipeline.ref()) && sha.equalsIgnoreCase(pipeline.sha())
                    && (expectedProjectId == null || expectedProjectId.equals(pipeline.key().projectId()));
        }

        private void readCollection(String path, Map<String, String> filters, BiConsumer<JsonNode, Instant> consume) {
            for (int page = 1; page <= limits.maxPages(); page++) {
                var request = urls.uri(path, filters, PAGE_SIZE, page);
                var response = transport.get(request, access.token(), gate);
                JsonNode rows = array(response, PAGE_SIZE);
                Instant observedAt = clock.instant();
                for (JsonNode row : rows) {
                    consume.accept(row, observedAt);
                }
                if (!GitLabPages.hasNext(urls, request, response.headers(), filters, page, PAGE_SIZE, rows.size())) {
                    return;
                }
            }
            throw GitLabErrors.budgetExceeded();
        }

        private JsonNode array(GitLabTransport.Response response, int pageSize) {
            JsonNode rows = body(response);
            if (rows == null || !rows.isArray() || rows.size() > pageSize) {
                throw GitLabErrors.invalidResponse();
            }
            gate.acceptedObjects(rows.size());
            return rows;
        }

        private void record(String operation, CapabilityState state, String evidence) {
            operations.put(operation, new Capability(state, evidence, clock.instant()));
        }

        private void unknownIfUnobserved(String operation) {
            operations.putIfAbsent(operation, new Capability(CapabilityState.UNKNOWN, "not_observed", clock.instant()));
        }
    }

    private JsonNode body(GitLabTransport.Response response) {
        try {
            return json.readTree(response.body());
        } catch (JacksonException exception) {
            throw GitLabErrors.invalidResponse();
        }
    }

    private static void requireSha(String sha) {
        if (sha == null || !FULL_SHA.matcher(sha).matches()) {
            throw GitLabErrors.invalidConfiguration();
        }
    }

    private static boolean optionalAccessUnavailable(ForgeException exception) {
        return switch (exception.getCode()) {
            case "gitlab_unauthorized", "gitlab_forbidden", "gitlab_not_found" -> true;
            default -> false;
        };
    }

    private enum Observation { CACHE, ASSOCIATION, OPEN }
    private record SeenMergeRequest(ChangeRequest value, Observation source, boolean hasHeadPipelineField) {}
    private record Metadata(String version, String edition, String tier) {}
}
