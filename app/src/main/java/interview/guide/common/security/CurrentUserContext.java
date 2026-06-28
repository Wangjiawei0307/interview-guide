package interview.guide.common.security;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public record CurrentUserContext(String userId, Set<String> roles, boolean anonymous) {

    public static final String ANONYMOUS_USER_ID = "anonymous";

    public CurrentUserContext {
        userId = normalizeUserId(userId);
        roles = normalizeRoles(roles);
        anonymous = anonymous || ANONYMOUS_USER_ID.equals(userId);
    }

    public boolean isAdmin() {
        return hasRole("ADMIN") || hasRole("admin");
    }

    public boolean hasRole(String role) {
        if (role == null || role.isBlank()) {
            return false;
        }
        String normalized = normalizeRole(role);
        return roles.contains(normalized);
    }

    public static String normalizeUserId(String value) {
        if (value == null || value.isBlank()) {
            return ANONYMOUS_USER_ID;
        }
        return value.trim();
    }

    public static String normalizeRole(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private static Set<String> normalizeRoles(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            String role = normalizeRole(value);
            if (!role.isBlank()) {
                normalized.add(role);
            }
        }
        return Collections.unmodifiableSet(normalized);
    }
}