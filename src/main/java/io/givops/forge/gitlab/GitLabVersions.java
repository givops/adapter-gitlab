package io.givops.forge.gitlab;

import io.givops.forge.port.ForgeRequestGate;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.JsonNode;
import tools.jackson.core.JacksonException;

/** Product/API evidence is per request and is never confused with SDK or adapter artifact versions. */
final class GitLabVersions {
    record Profile(String version, Boolean enterprise, String plan, String endpoint) {
        boolean beforePipelineInputs() {
            if (version == null) return false;
            var match = java.util.regex.Pattern.compile("^(\\d+)\\.(\\d+)(?:\\..*)?$").matcher(version);
            if (!match.matches()) return false;
            try {
                int major = Integer.parseInt(match.group(1)), minor = Integer.parseInt(match.group(2));
                return major < 17 || major == 17 && minor < 10;
            } catch (NumberFormatException failure) { return false; }
        }
    }
    static Profile read(GitLabTransport transport, GitLabUrls urls, String token, ForgeRequestGate gate, ObjectReader reader) {
        var response = transport.getForCheck(urls.metadata(), token, gate);
        String endpoint = "metadata";
        if (response.status() == 404) {
            // Documented compatible /version endpoint; only absence permits fallback, never auth or rate failures.
            response = transport.getForCheck(urls.detail("/version"), token, gate);
            endpoint = "version";
        }
        if (response.status() != 200) throw GitLabErrors.remoteStatus(response.status());
        try {
            JsonNode row = reader.readTree(response.body());
            GitLabPayload.requireObject(row); gate.acceptedObjects(1);
            return new Profile(GitLabPayload.text(row, "version", true), GitLabPayload.flag(row, "enterprise"),
                    GitLabPayload.text(row, "plan", false), endpoint);
        } catch (JacksonException failure) { throw GitLabErrors.invalidResponse(); }
    }
}
