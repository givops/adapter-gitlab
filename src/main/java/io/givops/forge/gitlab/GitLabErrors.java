package io.givops.forge.gitlab;

import io.givops.adapters.FailureCategory;
import io.givops.forge.model.ForgeException;
import org.springframework.http.HttpStatus;

final class GitLabErrors {
    private GitLabErrors() {}

    static ForgeException invalidResponse() {
        return new ForgeException("gitlab_invalid_response", HttpStatus.BAD_GATEWAY,
                GitLabMessages.format("forge.gitLabErrors.gitlab.returned.an.invalid.response"), FailureCategory.INVALID_RESPONSE);
    }

    static ForgeException invalidPagination() {
        return new ForgeException("gitlab_invalid_pagination", HttpStatus.BAD_GATEWAY,
                GitLabMessages.format("forge.gitLabErrors.gitlab.returned.invalid.pagination.information"), FailureCategory.INVALID_RESPONSE);
    }

    static ForgeException budgetExceeded() {
        return new ForgeException("forge_budget_exhausted", HttpStatus.UNPROCESSABLE_CONTENT,
                GitLabMessages.format("forge.gitLabErrors.the.forge.refresh.exceeded.its.resource.budget"), FailureCategory.INVALID_REQUEST);
    }

    static ForgeException invalidConfiguration() {
        return new ForgeException("gitlab_configuration_invalid", HttpStatus.UNPROCESSABLE_CONTENT,
                GitLabMessages.format("forge.gitLabErrors.the.gitlab.api.configuration.is.invalid"), FailureCategory.INVALID_REQUEST);
    }

    static ForgeException remoteStatus(int status) {
        return switch (status) {
            case 401 -> remote("gitlab_unauthorized", GitLabMessages.format("forge.gitLabErrors.gitlab.requires.valid.credentials.for.the.requested.data"), FailureCategory.ACCESS_DENIED);
            case 403 -> remote("gitlab_forbidden", GitLabMessages.format("forge.gitLabErrors.gitlab.denied.access.to.the.requested.data"), FailureCategory.ACCESS_DENIED);
            case 404 -> remote("gitlab_not_found", GitLabMessages.format("forge.gitLabErrors.gitlab.did.not.expose.the.requested.resource"), FailureCategory.NOT_FOUND);
            case 408 -> timeout();
            case 429 -> new ForgeException("gitlab_rate_limited", HttpStatus.SERVICE_UNAVAILABLE,
                    GitLabMessages.format("forge.gitLabErrors.gitlab.rate.limited.the.request"), FailureCategory.TRANSIENT);
            default -> {
                if (status >= 300 && status < 400) {
                    yield remote("gitlab_redirect", GitLabMessages.format("forge.gitLabErrors.gitlab.returned.a.redirect"), FailureCategory.INVALID_REQUEST);
                }
                if (status >= 500) {
                    yield new ForgeException("gitlab_unavailable", HttpStatus.SERVICE_UNAVAILABLE,
                            GitLabMessages.format("forge.gitLabErrors.gitlab.is.temporarily.unavailable"), FailureCategory.TRANSIENT);
                }
                yield remote("gitlab_request_rejected", GitLabMessages.format("forge.gitLabErrors.gitlab.rejected.the.api.request"), FailureCategory.INVALID_REQUEST);
            }
        };
    }

    static ForgeException timeout() {
        return new ForgeException("gitlab_timeout", HttpStatus.GATEWAY_TIMEOUT,
                GitLabMessages.format("forge.gitLabErrors.the.gitlab.request.timed.out"), FailureCategory.TRANSIENT);
    }

    static ForgeException connectionFailed() {
        return remote("gitlab_transport_failed", GitLabMessages.format("forge.gitLabErrors.the.gitlab.request.could.not.be.completed"), FailureCategory.TRANSIENT);
    }

    private static ForgeException remote(String code, String message, FailureCategory category) {
        return new ForgeException(code, HttpStatus.BAD_GATEWAY, message, category);
    }
}
