package io.givops.forge.gitlab;

import io.givops.adapters.ContextSigner;
import io.givops.adapters.CredentialAccessException;
import io.givops.adapters.CredentialHost;
import io.givops.adapters.CredentialLease;
import io.givops.adapters.CredentialRequest;
import io.givops.ticketing.model.TicketException;
import io.givops.ticketing.model.TicketException.Code;
import io.givops.ticketing.model.TicketModels.InstanceSource;
import io.givops.ticketing.port.TicketProviderAdapter;
import java.util.List;
import java.util.Objects;

/** Host leases bind each request and result publication to one destination and revision. */
final class GitLabCredentials {
    private final CredentialHost host;
    private final ContextSigner signer;
    GitLabCredentials(CredentialHost host, ContextSigner signer) { this.host = host; this.signer = signer; }
    record Captured(InstanceSource source, CredentialLease lease, String token, String context)
            implements TicketProviderAdapter.Access {
        @Override public String toString() { return "Captured[redacted]"; }
    }
    Captured capture(InstanceSource source) {
        if (!"GITLAB".equals(source.provider().name())) throw error(Code.INVALID_CONFIGURATION, "Expected a GitLab connection.");
        new GitLabUrls(source.baseUrl());
        CredentialLease lease;
        try { lease = host.capture(new CredentialRequest(source.instanceId(), source.instanceKey(),
                source.provider().name(), source.baseUrl())); }
        catch (CredentialAccessException failure) { throw error(Code.MISSING_CREDENTIALS, "GitLab credentials are unavailable."); }
        String base = source.baseUrl().toString().replaceAll("/+$", "");
        if (!Objects.equals(source.instanceKey(), lease.destinations().get("instanceKey"))
                || !base.equals(lease.destinations().get("baseUrl"))) {
            throw error(Code.INVALID_CONFIGURATION, "GitLab credentials do not match the destination.");
        }
        String token = lease.fields().get("apiToken");
        if (!validToken(token)) throw error(Code.MISSING_CREDENTIALS, "GitLab requires a valid API token.");
        String context = signer.sign("GITLAB", "ticketing", List.of(source.instanceId().toString(),
                source.instanceKey(), base, Long.toString(source.revision()),
                Objects.toString(lease.accessRevision(), ""), token));
        return new Captured(source, lease, token, context);
    }
    Captured require(InstanceSource source, TicketProviderAdapter.Access access, boolean locked) {
        if (!(access instanceof Captured captured) || !captured.source().equals(source))
            throw error(Code.STALE_CONTEXT, "GitLab access belongs to another connection.");
        try { captured.lease().requireCurrent(locked); }
        catch (CredentialAccessException failure) { throw error(Code.SUPERSEDED, "GitLab credential access changed."); }
        return captured;
    }
    static boolean validToken(String token) {
        return token != null && !token.isBlank() && token.length() <= 4096
                && token.chars().noneMatch(c -> c < 33 || c > 126);
    }
    static TicketException error(Code code, String message) { return new TicketException(code, message); }
}
