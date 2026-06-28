package interview.guide.common.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

@Component
public class CurrentUserProvider {

    public static final String USER_ID_HEADER = "X-User-Id";
    public static final String USER_ROLES_HEADER = "X-User-Roles";
    public static final String USER_ADMIN_HEADER = "X-User-Admin";

    public CurrentUserContext currentUser() {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return new CurrentUserContext(CurrentUserContext.ANONYMOUS_USER_ID, Set.of(), true);
        }

        String userId = firstNonBlank(
            attribute(request, "userId"),
            request.getHeader(USER_ID_HEADER)
        );
        Set<String> roles = parseRoles(request.getHeader(USER_ROLES_HEADER));
        if (isTruthy(request.getHeader(USER_ADMIN_HEADER))) {
            roles.add("ADMIN");
        }
        String normalizedUserId = CurrentUserContext.normalizeUserId(userId);
        return new CurrentUserContext(
            normalizedUserId,
            roles,
            CurrentUserContext.ANONYMOUS_USER_ID.equals(normalizedUserId)
        );
    }

    private HttpServletRequest currentRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            return servletAttributes.getRequest();
        }
        return null;
    }

    private String attribute(HttpServletRequest request, String name) {
        Object value = request.getAttribute(name);
        return value == null ? null : value.toString();
    }

    private Set<String> parseRoles(String header) {
        Set<String> roles = new LinkedHashSet<>();
        if (header == null || header.isBlank()) {
            return roles;
        }
        Arrays.stream(header.split(","))
            .map(CurrentUserContext::normalizeRole)
            .filter(role -> !role.isBlank())
            .forEach(roles::add);
        return roles;
    }

    private boolean isTruthy(String value) {
        return value != null && ("true".equalsIgnoreCase(value)
            || "1".equals(value)
            || "yes".equalsIgnoreCase(value));
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}