package io.givops.forge.gitlab;
import io.givops.adapters.i18n.MessageCatalog;
final class GitLabMessages {
    private static final MessageCatalog CATALOG = MessageCatalog.load(GitLabMessages.class, "/io/givops/adapters/gitlab/i18n/en.yml");
    private GitLabMessages() { }
    static String format(String key) { return format(key, java.util.Map.of()); }
    static String format(String key, java.util.Map<String, ?> arguments) { return CATALOG.format(key, arguments); }
}
