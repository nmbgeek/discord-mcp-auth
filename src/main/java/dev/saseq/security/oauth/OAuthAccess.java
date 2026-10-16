package dev.saseq.security.oauth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;

import java.util.Set;

/** Bridges OAuth opaque access tokens into the existing per-request MCP bearer check. */
public final class OAuthAccess {
    private static final Set<String> BROWSER = Set.of("/login", "/logout", "/oauth2/authorize");
    private static final Set<String> PROTOCOL_POST = Set.of("/oauth2/token", "/oauth2/revoke", "/oauth2/register");
    private final OAuthSettings settings;
    private final OAuthStore store;

    public OAuthAccess(OAuthSettings settings, OAuthStore store) {
        this.settings = settings;
        this.store = store;
    }

    public boolean isPublic(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();
        if ((method.equals("GET") || method.equals("HEAD")) && (path.equals("/.well-known/oauth-authorization-server")
                || path.equals("/.well-known/oauth-protected-resource")
                || path.equals("/.well-known/oauth-protected-resource" + settings.endpoint))) return true;
        return ((method.equals("GET") || method.equals("POST")) && BROWSER.contains(path))
                || (method.equals("POST") && PROTOCOL_POST.contains(path));
    }

    public boolean accepts(String token, HttpServletRequest request) {
        if (!settings.endpoint.equals(request.getRequestURI()) || token.length() > 512) return false;
        var authorization = store.findByToken(token, OAuth2TokenType.ACCESS_TOKEN);
        return authorization != null && authorization.getAccessToken() != null
                && authorization.getAccessToken().isActive()
                && settings.username.equals(authorization.getPrincipalName())
                && authorization.getAccessToken().getToken().getScopes().contains("mcp")
                && authorization.getAccessToken().getClaims() != null
                && settings.resource.equals(authorization.getAccessToken().getClaims().get("resource"));
    }

    public String challenge() {
        return "Bearer realm=\"discord-mcp\", resource_metadata=\"" + settings.issuer
                + "/.well-known/oauth-protected-resource" + settings.endpoint + "\", scope=\"mcp offline_access\"";
    }
}
