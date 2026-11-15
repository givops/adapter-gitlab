package io.givops.forge.gitlab;

import java.net.URI;
import java.net.http.HttpHeaders;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

final class GitLabPages {
    private static final Pattern LINK = Pattern.compile(
            "\\s*<([^<>]+)>\\s*((?:;\\s*[\\w-]+\\s*=\\s*(?:\"[^\"\\r\\n]*\"|[^\\s,;]+)\\s*)*)(?:,|$)");
    private static final Pattern RELATION = Pattern.compile(
            "(?:^|;)\\s*rel\\s*=\\s*(?:\"([^\"]*)\"|([^\\s;]+))", Pattern.CASE_INSENSITIVE);

    private GitLabPages() {}

    static boolean hasNext(GitLabUrls urls, URI request, HttpHeaders headers,
                           Map<String, String> filters, int page, int pageSize, int count) {
        String reportedPage = singleHeader(headers, "X-Page");
        if (reportedPage != null && GitLabUrls.positivePage(reportedPage) != page) {
            throw GitLabErrors.invalidPagination();
        }
        String reportedNext = singleHeader(headers, "X-Next-Page");
        Integer headerNext = reportedNext == null || reportedNext.isEmpty()
                ? null : GitLabUrls.positivePage(reportedNext);
        String nextLink = nextLink(headers.allValues("Link"));
        Integer linkedNext = nextLink == null ? null : urls.nextPage(request, nextLink, filters, pageSize);
        if (linkedNext != null && reportedNext != null && !linkedNext.equals(headerNext)) {
            throw GitLabErrors.invalidPagination();
        }
        Integer next = linkedNext == null ? headerNext : linkedNext;
        if (next != null && (next != page + 1 || count == 0)) {
            throw GitLabErrors.invalidPagination();
        }
        String totalHeader = singleHeader(headers, "X-Total-Pages");
        if (totalHeader != null) {
            int total = totalHeader.equals("0") ? 0 : GitLabUrls.positivePage(totalHeader);
            if ((total < page && !(total == 0 && page == 1 && count == 0))
                    || (next != null && total < next)
                    || (total > page && (count == 0 || reportedNext != null && next == null))) {
                throw GitLabErrors.invalidPagination();
            }
            return total > page;
        }
        if (next != null) {
            return true;
        }
        // If an intermediary omits all continuation headers for a full page, check the following
        // page rather than treating the collection as complete on ambiguous evidence.
        return reportedNext == null && count == pageSize;
    }

    private static String singleHeader(HttpHeaders headers, String name) {
        List<String> values = headers.allValues(name);
        if (values.size() > 1) {
            throw GitLabErrors.invalidPagination();
        }
        return values.isEmpty() ? null : values.getFirst().strip();
    }

    private static String nextLink(List<String> values) {
        String result = null;
        for (String value : values) {
            var link = LINK.matcher(value);
            for (int start = 0; start < value.length(); start = link.end()) {
                link.region(start, value.length());
                if (!link.lookingAt()) {
                    throw GitLabErrors.invalidPagination();
                }
                var relation = RELATION.matcher(link.group(2));
                if (relation.find()) {
                    String names = relation.group(1) == null ? relation.group(2) : relation.group(1);
                    for (String name : names.split("\\s+")) {
                        if (name.equalsIgnoreCase("next")) {
                            if (result != null) {
                                throw GitLabErrors.invalidPagination();
                            }
                            result = link.group(1);
                        }
                    }
                }
            }
        }
        return result;
    }
}
