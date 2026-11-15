package io.givops.forge.gitlab;

import static org.assertj.core.api.Assertions.assertThat;

import io.givops.adapters.FailureCategory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;

class GitLabErrorsTest {
    @ParameterizedTest
    @CsvSource({
        "401,ACCESS_DENIED,gitlab_unauthorized", "403,ACCESS_DENIED,gitlab_forbidden",
        "404,NOT_FOUND,gitlab_not_found", "408,TRANSIENT,gitlab_timeout",
        "429,TRANSIENT,gitlab_rate_limited", "500,TRANSIENT,gitlab_unavailable",
        "503,TRANSIENT,gitlab_unavailable", "301,INVALID_REQUEST,gitlab_redirect",
        "302,INVALID_REQUEST,gitlab_redirect", "307,INVALID_REQUEST,gitlab_redirect",
        "400,INVALID_REQUEST,gitlab_request_rejected", "422,INVALID_REQUEST,gitlab_request_rejected"
    })
    void categorizesProviderStatusWithoutRequiringCoreToParseVendorCodes(int status, FailureCategory category, String code) {
        var failure = GitLabErrors.remoteStatus(status);
        assertThat(failure.category()).isEqualTo(category);
        assertThat(failure.getCode()).isEqualTo(code);
        assertThat(failure.getCause()).isNull();
    }

    @Test void badGatewayPresentationDoesNotMakeMalformedPayloadsOrPaginationRetryable() {
        for (var failure : java.util.List.of(GitLabErrors.invalidResponse(), GitLabErrors.invalidPagination())) {
            assertThat(failure.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
            assertThat(failure.category()).isEqualTo(FailureCategory.INVALID_RESPONSE);
        }
        assertThat(GitLabErrors.invalidConfiguration().category()).isEqualTo(FailureCategory.INVALID_REQUEST);
        assertThat(GitLabErrors.budgetExceeded().category()).isEqualTo(FailureCategory.INVALID_REQUEST);
    }

    @Test void transportFailureAndTimeoutAreExplicitlyTransient() {
        assertThat(GitLabErrors.connectionFailed().category()).isEqualTo(FailureCategory.TRANSIENT);
        assertThat(GitLabErrors.timeout().category()).isEqualTo(FailureCategory.TRANSIENT);
    }
}
