package dev.saseq.security.oauth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/** Bounds public entry points and validates the single MCP resource before OAuth processing. */
final class OAuthRequestGuard extends OncePerRequestFilter {
    private final OAuthSettings settings;
    private final Map<String, Window> windows = new HashMap<>();
    OAuthRequestGuard(OAuthSettings settings) { this.settings = settings; }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (path.startsWith("/oauth2/") || path.equals("/login")) {
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("Referrer-Policy", "no-referrer");
        }
        if (request.getMethod().equals("POST") && (path.equals("/login") || path.equals("/oauth2/register"))) {
            // Use the direct peer address. Never trust an arbitrary X-Forwarded-For header.
            if (!allow(path, "global", 100) || !allow(path, request.getRemoteAddr(), path.equals("/login") ? 10 : 20)) {
                response.setHeader("Retry-After", "60");
                error(response, 429, "temporarily_unavailable"); return;
            }
        }
        if (request.getMethod().equals("POST") && (path.equals("/oauth2/token") || path.equals("/oauth2/revoke"))) {
            var authorizationHeaders = request.getHeaders("Authorization");
            if (authorizationHeaders.hasMoreElements()) authorizationHeaders.nextElement();
            if (authorizationHeaders.hasMoreElements() || request.getQueryString() != null) {
                error(response, 400, "invalid_request"); return;
            }
            if (!allow(path, "global", 600) || !allow(path, request.getRemoteAddr(), 120)) {
                response.setHeader("Retry-After", "60");
                error(response, 429, "temporarily_unavailable"); return;
            }
        }
        boolean authorize = path.equals("/oauth2/authorize") && request.getParameter("response_type") != null;
        boolean token = path.equals("/oauth2/token");
        if (authorize || token) {
            for (String name : new String[]{"resource", "client_id", "grant_type", "redirect_uri", "code", "code_verifier",
                    "code_challenge", "code_challenge_method", "scope", "response_type", "state"}) {
                String[] values = request.getParameterValues(name);
                if (values != null && values.length != 1) { error(response, 400, "invalid_request"); return; }
            }
            String resource = request.getParameter("resource");
            if ((resource != null && !settings.resource.equals(resource)) || (authorize && resource == null)) {
                error(response, 400, "invalid_target"); return;
            }
            if (authorize && (!"S256".equals(request.getParameter("code_challenge_method"))
                    || request.getParameter("code_challenge") == null
                    || !request.getParameter("code_challenge").matches("[A-Za-z0-9_-]{43}"))) {
                error(response, 400, "invalid_request"); return;
            }
        }
        chain.doFilter(request, response);
    }

    private synchronized boolean allow(String path, String address, int limit) {
        long minute = System.currentTimeMillis() / 60_000;
        windows.entrySet().removeIf(entry -> entry.getValue().minute != minute);
        String key = path + ":" + address;
        Window window = windows.computeIfAbsent(key, ignored -> new Window(minute));
        return ++window.count <= limit;
    }
    private static class Window {
        final long minute;
        int count;
        Window(long minute) { this.minute = minute; }
    }
    static void error(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setHeader("Cache-Control", "no-store");
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + code + "\"}");
    }
}
