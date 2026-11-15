package io.givops.forge.gitlab;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

final class GitLabUrls {
    private static final Pattern PREFIX = Pattern.compile("(?:/[A-Za-z0-9._~-]+)*/?");
    private static final HexFormat HEX = HexFormat.of().withUpperCase();
    private final URI base;
    private final String api;

    GitLabUrls(URI base) {
        if (!safeAuthority(base) || base.getRawQuery() != null || base.getRawFragment() != null
                || !PREFIX.matcher(base.getRawPath()).matches() || dotSegment(base.getRawPath())) {
            throw GitLabErrors.invalidConfiguration();
        }
        this.base = base;
        String path = base.getRawPath();
        this.api = origin(base) + (path.endsWith("/") ? path.substring(0, path.length() - 1) : path) + "/api/v4";
    }

    URI uri(String path, Map<String, String> filters, int perPage, int page) {
        var query = new StringBuilder();
        filters.forEach((key, value) -> query.append(encode(key)).append('=').append(encode(value)).append('&'));
        return URI.create(api + path + "?" + query + "per_page=" + perPage + "&page=" + page);
    }

    URI metadata() {
        return URI.create(api + "/metadata");
    }

    URI detail(String path) {
        return URI.create(api + path);
    }

    int nextPage(URI current, String href, Map<String, String> filters, int perPage) {
        try {
            URI link = URI.create(href);
            String path = link.getRawPath();
            if (link.getRawFragment() != null || path == null || dotSegment(path)) {
                throw GitLabErrors.invalidPagination();
            }
            URI resolved;
            if (link.isAbsolute()) {
                resolved = link;
            } else if (link.getRawAuthority() == null && (path.isEmpty() || path.startsWith("/"))) {
                resolved = URI.create(origin(current) + (path.isEmpty() ? current.getRawPath() : path)
                        + (link.getRawQuery() == null ? "" : "?" + link.getRawQuery()));
            } else {
                throw GitLabErrors.invalidPagination();
            }
            if (!safeAuthority(resolved) || !base.getHost().equalsIgnoreCase(resolved.getHost())
                    || effectivePort(base) != effectivePort(resolved)
                    || !sameRawPath(current.getRawPath(), resolved.getRawPath())) {
                throw GitLabErrors.invalidPagination();
            }
            var query = query(resolved.getRawQuery());
            String next = query.remove("page");
            String size = query.remove("per_page");
            // Never forward remote query values. Reject changed filters, and regenerate the exact
            // original query even if the link omitted a filter or contains an unrelated parameter.
            if ((size != null && !size.equals(Integer.toString(perPage)))
                    || filters.entrySet().stream().anyMatch(entry -> query.containsKey(entry.getKey())
                            && !entry.getValue().equals(query.get(entry.getKey())))) {
                throw GitLabErrors.invalidPagination();
            }
            return positivePage(next);
        } catch (IllegalArgumentException exception) {
            throw GitLabErrors.invalidPagination();
        }
    }

    static int positivePage(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,8}")) {
            throw GitLabErrors.invalidPagination();
        }
        return Integer.parseInt(value);
    }

    static String project(String id) {
        if (id == null || id.isBlank() || dotSegment(id) || id.startsWith("/") || id.endsWith("/")
                || id.contains("//") || id.contains("\\") || id.contains("%")
                || id.codePoints().anyMatch(Character::isISOControl)) {
            throw GitLabErrors.invalidConfiguration();
        }
        return "/projects/" + encode(id);
    }

    static String encode(String value) {
        var result = new StringBuilder(value.length());
        for (byte octet : value.getBytes(StandardCharsets.UTF_8)) {
            int c = Byte.toUnsignedInt(octet);
            if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9'
                    || c == '-' || c == '.' || c == '_' || c == '~') {
                result.append((char) c);
            } else {
                result.append('%');
                HEX.toHexDigits(result, octet);
            }
        }
        return result.toString();
    }

    private static Map<String, String> query(String raw) {
        var result = new LinkedHashMap<String, String>();
        if (raw != null) {
            for (String pair : raw.split("&", -1)) {
                int delimiter = pair.indexOf('=');
                if (delimiter <= 0 || result.putIfAbsent(
                        URLDecoder.decode(pair.substring(0, delimiter), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(delimiter + 1), StandardCharsets.UTF_8)) != null) {
                    throw GitLabErrors.invalidPagination();
                }
            }
        }
        return result;
    }

    private static boolean safeAuthority(URI uri) {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getRawUserInfo() != null || uri.getPort() == 0 || uri.getPort() > 65535) {
            return false;
        }
        String authority = uri.getHost() + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
        return authority.equalsIgnoreCase(uri.getRawAuthority());
    }

    private static boolean dotSegment(String path) {
        for (String segment : path.split("/")) {
            if (segment.equals(".") || segment.equals("..")) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameRawPath(String expected, String actual) {
        if (expected.length() != actual.length()) {
            return false;
        }
        for (int index = 0; index < expected.length(); index++) {
            if (expected.charAt(index) == '%' && actual.charAt(index) == '%') {
                if (!expected.regionMatches(true, index + 1, actual, index + 1, 2)) {
                    return false;
                }
                index += 2;
            } else if (expected.charAt(index) != actual.charAt(index)) {
                return false;
            }
        }
        return true;
    }

    private static int effectivePort(URI uri) {
        return uri.getPort() == -1 ? 443 : uri.getPort();
    }

    private static String origin(URI uri) {
        return "https://" + uri.getRawAuthority();
    }
}
