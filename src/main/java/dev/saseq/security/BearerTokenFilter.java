package dev.saseq.security;

import dev.saseq.security.oauth.OAuthAccess;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Pattern;

/** Requires a shared secret on every HTTP request except the basic health probe. */
public final class BearerTokenFilter extends OncePerRequestFilter {

    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9._~+/-]+=*");
    private final byte[] expectedDigest;
    private final OAuthAccess oauth;

    public BearerTokenFilter(String token) {
        this(token, null);
    }

    public BearerTokenFilter(String token, OAuthAccess oauth) {
        this.oauth = oauth;
        if (oauth != null && (token == null || token.isEmpty())) {
            expectedDigest = null;
            return;
        }
        if (token == null || token.length() < 32 || !TOKEN.matcher(token).matches()) {
            throw new IllegalArgumentException(
                    "MCP_AUTH_TOKEN must contain at least 32 bearer-token characters. "
                    + "Generate a secret with openssl rand -hex 32, or explicitly set "
                    + "MCP_AUTH_ENABLED=false for trusted local use only.");
        }
        expectedDigest = digest(token);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Keep Docker probes working without exposing health components or other routes.
        return (oauth != null && oauth.isPublic(request)) || ("GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod()))
                && (request.getContextPath() + "/actuator/health").equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                  FilterChain filterChain) throws ServletException, IOException {
        var headers = request.getHeaders("Authorization");
        String authorization = headers.hasMoreElements() ? headers.nextElement() : null;
        if (!headers.hasMoreElements() && isAuthorized(authorization, request)) {
            filterChain.doFilter(request, response);
            return;
        }

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader("WWW-Authenticate", oauth == null ? "Bearer realm=\"discord-mcp\"" : oauth.challenge());
        response.setHeader("Cache-Control", "no-store");
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"unauthorized\"}");
    }

    private boolean isAuthorized(String authorization, HttpServletRequest request) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return false;
        }
        // RFC 6750 permits one or more spaces between the scheme and token.
        int start = 7;
        while (start < authorization.length() && authorization.charAt(start) == ' ') start++;
        String token = authorization.substring(start);
        return TOKEN.matcher(token).matches()
                && ((expectedDigest != null && MessageDigest.isEqual(expectedDigest, digest(token)))
                    || (oauth != null && oauth.accepts(token, request)));
    }

    private static byte[] digest(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
