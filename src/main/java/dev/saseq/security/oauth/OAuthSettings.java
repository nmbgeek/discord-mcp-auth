package dev.saseq.security.oauth;

import org.springframework.core.env.Environment;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

/** Validated deployment settings. Deliberately has no toString containing credentials. */
public final class OAuthSettings {
    public final String issuer;
    public final String resource;
    public final String endpoint;
    public final String username;
    public final String password;
    public final Path directory;
    public final Duration accessTtl;
    public final Duration refreshTtl;

    public OAuthSettings(Environment env) {
        if (!env.getProperty("mcp.auth.enabled", Boolean.class, true)) {
            throw new IllegalArgumentException("OAuth requires MCP_AUTH_ENABLED=true");
        }
        issuer = env.getRequiredProperty("mcp.oauth.issuer");
        URI uri;
        try { uri = URI.create(issuer); }
        catch (IllegalArgumentException ex) { throw new IllegalArgumentException("MCP_OAUTH_ISSUER must be an HTTPS origin"); }
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null || !uri.getRawPath().isEmpty()) {
            throw new IllegalArgumentException("MCP_OAUTH_ISSUER must be an HTTPS origin without a trailing slash");
        }
        if (!env.getProperty("server.servlet.context-path", "").isEmpty()) {
            throw new IllegalArgumentException("OAuth requires the application at the origin root, without a servlet context path");
        }
        endpoint = env.getProperty("spring.ai.mcp.server.streamable-http.mcp-endpoint", "/mcp");
        if (!endpoint.matches("/(?:[A-Za-z0-9_-]+/)*[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("OAuth requires a simple absolute MCP endpoint path");
        }
        if (endpoint.equals("/login") || endpoint.equals("/logout") || endpoint.equals("/error")
                || endpoint.startsWith("/oauth2/") || endpoint.equals("/oauth2")
                || endpoint.startsWith("/actuator/") || endpoint.equals("/actuator")) {
            throw new IllegalArgumentException("The MCP endpoint must not overlap OAuth, login or Actuator routes");
        }
        resource = issuer + endpoint;
        username = env.getRequiredProperty("mcp.oauth.username");
        password = env.getRequiredProperty("mcp.oauth.password");
        if (username.isBlank() || username.length() > 64 || password.length() < 16 || password.length() > 256) {
            throw new IllegalArgumentException("Set MCP_OAUTH_USERNAME and a MCP_OAUTH_PASSWORD of 16 to 256 characters");
        }
        directory = Path.of(env.getProperty("mcp.oauth.data-dir", "./data/oauth")).toAbsolutePath().normalize();
        if (directory.toString().contains(";")) throw new IllegalArgumentException("Invalid OAuth data directory");
        accessTtl = Duration.ofSeconds(env.getProperty("mcp.oauth.access-token-seconds", Long.class, 900L));
        refreshTtl = Duration.ofDays(env.getProperty("mcp.oauth.refresh-token-days", Long.class, 90L));
        if (accessTtl.getSeconds() < 30 || accessTtl.getSeconds() > 3600
                || refreshTtl.toDays() < 1 || refreshTtl.toDays() > 365) {
            throw new IllegalArgumentException("OAuth access lifetime must be 30-3600 seconds and refresh lifetime 1-365 days");
        }
    }
}
