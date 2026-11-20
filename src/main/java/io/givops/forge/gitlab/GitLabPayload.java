package io.givops.forge.gitlab;

import io.givops.forge.model.ChangeRequestLifecycle;
import io.givops.forge.model.ForgeModels.CiSummary;
import io.givops.forge.model.ForgeModels.CiState;
import io.givops.forge.model.ForgeModels.ConflictState;
import io.givops.forge.model.ForgeModels.ReviewerCoverage;
import io.givops.forge.model.ForgeModels.PipelineCoverage;
import io.givops.forge.model.ForgeModels.PipelineCoverageState;
import io.givops.forge.model.ForgeModels.ChangeRequest;
import io.givops.forge.model.ForgeModels.ChangeRequestKey;
import io.givops.forge.model.ForgeModels.Person;
import io.givops.forge.model.ForgeModels.Pipeline;
import io.givops.forge.model.ForgeModels.PipelineKey;
import io.givops.forge.model.ForgeModels.TaskSignals;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;

final class GitLabPayload {
    private GitLabPayload() {}

    static ChangeRequest changeRequest(JsonNode node, Instant observedAt) {
        return changeRequest(node, observedAt, true);
    }

    static ChangeRequest changeRequest(JsonNode node, Instant observedAt, boolean observePipeline) {
        requireObject(node);
        Boolean draft = flag(node, "draft");
        if (draft == null) {
            draft = flag(node, "work_in_progress");
        }
        JsonNode head = observePipeline ? node.get("head_pipeline") : null;
        String state = text(node, "state", true);
        String mergeStatus = text(node, "detailed_merge_status", false);
        if (mergeStatus == null) mergeStatus = text(node, "merge_status", false);
        return new ChangeRequest(new ChangeRequestKey(id(node, "project_id", true), id(node, "iid", true)),
                id(node, "id", true), text(node, "web_url", false), text(node, "title", true),
                text(node, "description", false), state, Boolean.TRUE.equals(draft),
                id(node, "source_project_id", false), id(node, "target_project_id", false),
                text(node, "source_branch", false), text(node, "target_branch", false),
                text(node, "sha", false), text(node, "merge_commit_sha", false),
                text(node, "squash_commit_sha", false), person(node.get("author")),
                assignees(node), people(node.get("reviewers")), labels(node.get("labels")),
                instant(node, "created_at"), instant(node, "updated_at"), instant(node, "merged_at"),
                instant(node, "closed_at"), missing(head) ? null : pipeline(head, observedAt), observedAt,
                "opened".equals(state) ? signals(null, Set.of(), false, mergeStatus,
                        missing(head) ? null : pipeline(head, observedAt), observePipeline) : null,
                lifecycle(state), List.of(), List.of(), observePipeline
                        ? new PipelineCoverage(PipelineCoverageState.OBSERVED, "gitlab_head_pipeline")
                        : new PipelineCoverage(PipelineCoverageState.UNKNOWN, "not_observed"));
    }

    static ChangeRequestLifecycle lifecycle(String state) {
        return switch (state) {
            case "opened", "locked" -> ChangeRequestLifecycle.OPEN;
            case "merged" -> ChangeRequestLifecycle.MERGED;
            case "closed" -> ChangeRequestLifecycle.CLOSED;
            default -> ChangeRequestLifecycle.UNKNOWN;
        };
    }

    static TaskSignals signals(Integer unresolved, Set<String> approved, boolean approvalsKnown, String mergeStatus, Pipeline pipeline) {
        return signals(unresolved, approved, approvalsKnown, mergeStatus, pipeline, true);
    }

    private static TaskSignals signals(Integer unresolved, Set<String> approved, boolean approvalsKnown,
                                       String mergeStatus, Pipeline pipeline, boolean observePipeline) {
        ConflictState conflict = mergeStatus == null ? ConflictState.UNKNOWN : switch (mergeStatus) {
            case "cannot_be_merged", "conflict" -> ConflictState.CONFLICT;
            case "can_be_merged", "mergeable" -> ConflictState.CLEAR;
            default -> ConflictState.UNKNOWN;
        };
        CiState ci = pipeline == null ? CiState.UNKNOWN : switch (pipeline.status()) {
            case "success" -> CiState.SUCCESS;
            case "failed", "canceled" -> CiState.FAILED;
            case "created", "waiting_for_resource", "preparing", "pending", "running", "scheduled" -> CiState.PENDING;
            default -> CiState.UNKNOWN;
        };
        return new TaskSignals(unresolved, approved, approvalsKnown, mergeStatus,
                new CiSummary(ci, observePipeline ? PipelineCoverageState.OBSERVED : PipelineCoverageState.UNKNOWN,
                        observePipeline ? "gitlab_head_pipeline" : "not_observed"), conflict, ReviewerCoverage.COMPLETE);
    }

    static boolean hasUnresolvedNote(JsonNode notes) {
        boolean unresolved = false;
        for (JsonNode note : notes) {
            requireObject(note);
            Boolean system = flag(note, "system");
            if (Boolean.TRUE.equals(system)) continue;
            Boolean resolvable = flag(note, "resolvable");
            if (resolvable == null) throw GitLabErrors.invalidResponse();
            if (!resolvable) continue;
            Boolean resolved = flag(note, "resolved");
            if (system == null || resolved == null) throw GitLabErrors.invalidResponse();
            unresolved |= !resolved;
        }
        return unresolved;
    }

    static Set<String> approvedProviderIds(JsonNode approvals) {
        var ids = new LinkedHashSet<String>();
        for (JsonNode approval : approvals) {
            requireObject(approval);
            JsonNode user = approval.get("user");
            requireObject(user);
            if (!ids.add(id(user, "id", true))) throw GitLabErrors.invalidResponse();
        }
        return Set.copyOf(ids);
    }

    static Pipeline pipeline(JsonNode node, Instant observedAt) {
        requireObject(node);
        return new Pipeline(new PipelineKey(id(node, "project_id", true), id(node, "id", true)),
                text(node, "status", true), text(node, "ref", true), text(node, "sha", true),
                text(node, "source", false), text(node, "web_url", false),
                instant(node, "created_at"), instant(node, "updated_at"), observedAt);
    }

    static void requireObject(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw GitLabErrors.invalidResponse();
        }
    }

    static String text(JsonNode node, String name, boolean required) {
        JsonNode field = node.get(name);
        if (!required && missing(field)) {
            return null;
        }
        if (missing(field) || !field.isTextual() || required && field.stringValue().isBlank()) {
            throw GitLabErrors.invalidResponse();
        }
        return field.stringValue();
    }

    static Boolean flag(JsonNode node, String name) {
        JsonNode field = node.get(name);
        if (missing(field)) {
            return null;
        }
        if (!field.isBoolean()) {
            throw GitLabErrors.invalidResponse();
        }
        return field.booleanValue();
    }

    private static String id(JsonNode node, String name, boolean required) {
        JsonNode field = node.get(name);
        if (!required && missing(field)) {
            return null;
        }
        if (!missing(field) && field.isIntegralNumber()) {
            long value = field.longValueOpt().orElse(0);
            if (value > 0) {
                return Long.toString(value);
            }
        }
        throw GitLabErrors.invalidResponse();
    }

    private static Instant instant(JsonNode node, String name) {
        String value = text(node, name, false);
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw GitLabErrors.invalidResponse();
        }
    }

    private static List<String> labels(JsonNode node) {
        if (missing(node)) {
            return List.of();
        }
        if (!node.isArray()) {
            throw GitLabErrors.invalidResponse();
        }
        var labels = new ArrayList<String>();
        for (JsonNode label : node) {
            if (!label.isTextual()) {
                throw GitLabErrors.invalidResponse();
            }
            labels.add(label.stringValue());
        }
        return labels;
    }

    private static List<Person> assignees(JsonNode node) {
        JsonNode assignees = node.get("assignees");
        if (!missing(assignees)) {
            return people(assignees);
        }
        Person assignee = person(node.get("assignee"));
        return assignee == null ? List.of() : List.of(assignee);
    }

    private static List<Person> people(JsonNode node) {
        if (missing(node)) {
            return List.of();
        }
        if (!node.isArray()) {
            throw GitLabErrors.invalidResponse();
        }
        var people = new ArrayList<Person>();
        for (JsonNode item : node) {
            requireObject(item);
            people.add(person(item));
        }
        return people;
    }

    private static Person person(JsonNode node) {
        if (missing(node)) {
            return null;
        }
        requireObject(node);
        return new Person(id(node, "id", true), text(node, "username", false), text(node, "name", false));
    }

    private static boolean missing(JsonNode node) {
        return node == null || node.isNull();
    }
}
