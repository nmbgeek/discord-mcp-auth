package dev.saseq.security.oauth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@RestController
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "mcp.oauth.enabled", havingValue = "true")
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(type = org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
public final class OAuthEndpoints {
    private final OAuthSettings settings;
    private final OAuthStore store;
    private final PasswordEncoder encoder;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final SecureRandom random = new SecureRandom();

    public OAuthEndpoints(OAuthSettings settings, OAuthStore store, PasswordEncoder encoder) {
        this.settings = settings;
        this.store = store;
        this.encoder = encoder;
    }

    @GetMapping({"/.well-known/oauth-protected-resource", "/.well-known/oauth-protected-resource/**"})
    public ResponseEntity<?> resource(HttpServletRequest request) {
        if (!request.getRequestURI().equals("/.well-known/oauth-protected-resource")
                && !request.getRequestURI().equals("/.well-known/oauth-protected-resource" + settings.endpoint)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("resource", settings.resource, "authorization_servers", List.of(settings.issuer),
                "scopes_supported", List.of("mcp", "offline_access"), "bearer_methods_supported", List.of("header")));
    }

    @PostMapping(value = "/oauth2/register", consumes = "application/json")
    public ResponseEntity<?> register(HttpServletRequest request) throws java.io.IOException {
        // Bound bytes before JSON decoding, including requests with chunked transfer encoding.
        byte[] body = request.getInputStream().readNBytes(8193);
        if (body.length > 8192) return error(413, "invalid_client_metadata");
        try {
            JsonNode input = mapper.readTree(body);
            if (input == null || !input.isObject()) return error(400, "invalid_client_metadata");
            JsonNode redirects = input.get("redirect_uris");
            if (redirects == null || !redirects.isArray() || redirects.isEmpty() || redirects.size() > 5) {
                return error(400, "invalid_redirect_uri");
            }
            Set<String> uris = new LinkedHashSet<>();
            for (JsonNode redirect : redirects) {
                if (!redirect.isString() || !validRedirect(redirect.asString())) return error(400, "invalid_redirect_uri");
                uris.add(redirect.asString());
            }
            if (String.join(",", uris).length() > 900) return error(400, "invalid_redirect_uri");
            String method = input.path("token_endpoint_auth_method").asString("client_secret_basic");
            if (!Set.of("none", "client_secret_basic").contains(method)) return error(400, "invalid_client_metadata");
            if (input.has("response_types") && (!input.get("response_types").isArray()
                    || input.get("response_types").size() != 1 || !input.get("response_types").get(0).asString("").equals("code"))) {
                return error(400, "invalid_client_metadata");
            }
            if (input.has("grant_types")) {
                JsonNode grants = input.get("grant_types");
                if (!grants.isArray() || grants.isEmpty()) return error(400, "invalid_client_metadata");
                for (JsonNode grant : grants) {
                    if (!Set.of("authorization_code", "refresh_token").contains(grant.asString(""))) return error(400, "invalid_client_metadata");
                }
            }
            String name = input.path("client_name").asString("MCP client");
            // Avoid presenting arbitrary markup, control characters or unbounded names on consent pages.
            if (name.isBlank() || name.length() > 100 || name.chars().anyMatch(c -> c < 32 || c == '<' || c == '>')) {
                return error(400, "invalid_client_metadata");
            }
            var client = RegisteredClient.withId(UUID.randomUUID().toString()).clientId(UUID.randomUUID().toString())
                    .clientName(name).clientIdIssuedAt(Instant.now()).clientAuthenticationMethod(new ClientAuthenticationMethod(method))
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                    .redirectUris(values -> values.addAll(uris)).scope("mcp").scope("offline_access")
                    .clientSettings(ClientSettings.builder().requireProofKey(true).requireAuthorizationConsent(true).build())
                    .tokenSettings(TokenSettings.builder().accessTokenFormat(OAuth2TokenFormat.REFERENCE)
                            .authorizationCodeTimeToLive(Duration.ofMinutes(2)).accessTokenTimeToLive(settings.accessTtl)
                            .refreshTokenTimeToLive(settings.refreshTtl).reuseRefreshTokens(false).build());
            String secret = null;
            if (method.equals("client_secret_basic")) {
                byte[] bytes = new byte[32]; random.nextBytes(bytes);
                secret = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
                client.clientSecret(encoder.encode(secret));
            }
            var registered = client.build();
            synchronized (store) {
                store.cleanup();
                if (store.jdbc.queryForObject("SELECT COUNT(*) FROM oauth2_registered_client", Integer.class) >= 1000) {
                    return error(503, "temporarily_unavailable");
                }
                store.clients.save(registered);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("client_id", registered.getClientId());
            result.put("client_id_issued_at", registered.getClientIdIssuedAt().getEpochSecond());
            result.put("client_name", name);
            result.put("redirect_uris", uris);
            result.put("token_endpoint_auth_method", method);
            result.put("grant_types", List.of("authorization_code", "refresh_token"));
            result.put("response_types", List.of("code"));
            result.put("scope", "mcp offline_access");
            if (secret != null) { result.put("client_secret", secret); result.put("client_secret_expires_at", 0); }
            return ResponseEntity.status(201).header("Cache-Control", "no-store").body(result);
        } catch (tools.jackson.core.JacksonException | IllegalArgumentException ex) {
            return error(400, "invalid_client_metadata");
        }
    }

    static boolean validRedirect(String value) {
        try {
            if (value.length() > 900 || value.contains(",")) return false;
            URI uri = URI.create(value);
            if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawFragment() != null) return false;
            if ("https".equals(uri.getScheme())) return true;
            return "http".equals(uri.getScheme()) && Set.of("127.0.0.1", "localhost", "[::1]").contains(uri.getHost());
        } catch (IllegalArgumentException ex) { return false; }
    }
    private static ResponseEntity<?> error(int status, String error) {
        return ResponseEntity.status(status).header("Cache-Control", "no-store").body(Map.of("error", error));
    }
}
