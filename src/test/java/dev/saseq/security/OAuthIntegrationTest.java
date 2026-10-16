package dev.saseq.security;

import dev.saseq.configs.McpHttpAuthConfig;
import dev.saseq.security.oauth.OAuthStore;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Real sockets, cookies, login, consent, PKCE, refresh, MCP and on-disk restart persistence. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OAuthIntegrationTest {
    static final String ISSUER = "https://discord-mcp.example.test";
    static final String RESOURCE = ISSUER + "/mcp";
    static final String PASSWORD = "integration-only-plaintext-password";
    static final String STATIC_TOKEN = "integration-static-bearer-01234567890123456789";
    static final String REDIRECT = "http://127.0.0.1:19191/callback";
    static final String VERIFIER = "integration-test-pkce-verifier-abcdefghijklmnopqrstuvwxyz0123456789";
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpClient API = HttpClient.newHttpClient();
    static HttpClient browser;
    static ConfigurableApplicationContext context;
    static String base;
    @TempDir static Path directory;

    @BeforeAll static void setup() throws Exception {
        start(PASSWORD);
        login(PASSWORD);
    }

    static void login(String password) throws Exception {
        browser = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        var login = send(browser, "GET", "/login", null, null);
        assertThat(login.statusCode()).isEqualTo(200);
        String csrf = field(login.body(), "_csrf");
        assertThat(send(browser, "POST", "/login", form("username", "owner", "password", password), null).statusCode()).isEqualTo(403);
        var bad = send(browser, "POST", "/login", form("username", "owner", "password", "incorrect", "_csrf", csrf), null);
        assertThat(bad.headers().firstValue("Location").orElse("")).contains("error");
        csrf = field(send(browser, "GET", "/login", null, null).body(), "_csrf");
        var good = send(browser, "POST", "/login", form("username", "owner", "password", password, "_csrf", csrf), null);
        assertThat(good.statusCode()).isEqualTo(302);
        assertThat(good.headers().firstValue("Location").orElse("")).doesNotContain("error");
    }

    static void start(String password) { start(password, STATIC_TOKEN); }
    static void start(String password, String staticToken) {
        context = new SpringApplicationBuilder(TestServer.class).profiles("http").run(
                "--server.port=0", "--server.address=127.0.0.1", "--server.servlet.session.cookie.secure=false",
                "--mcp.auth.enabled=true", "--mcp.auth.token=" + staticToken,
                "--mcp.oauth.enabled=true", "--mcp.oauth.issuer=" + ISSUER,
                "--mcp.oauth.username=owner", "--mcp.oauth.password=" + password,
                "--mcp.oauth.data-dir=" + directory);
        base = "http://127.0.0.1:" + ((WebServerApplicationContext) context).getWebServer().getPort();
    }
    @AfterAll static void stop() { if (context != null) context.close(); }

    @Test @Order(1) void discoveryAndStaticBearerStillWorkWithoutCookieAuthentication() throws Exception {
        var unauthorized = send(browser, "POST", "/mcp", "{}", null);
        assertThat(unauthorized.statusCode()).isEqualTo(401);
        assertThat(unauthorized.headers().firstValue("WWW-Authenticate").orElseThrow()).contains("resource_metadata", "offline_access");
        var resource = send(API, "GET", "/.well-known/oauth-protected-resource/mcp", null, null);
        assertThat(resource.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(resource.body()).path("resource").asString()).isEqualTo(RESOURCE);
        var metadata = JSON.readTree(send(API, "GET", "/.well-known/oauth-authorization-server", null, null).body());
        assertThat(metadata.path("issuer").asString()).isEqualTo(ISSUER);
        assertThat(metadata.path("registration_endpoint").asString()).isEqualTo(ISSUER + "/oauth2/register");
        assertThat(metadata.path("grant_types_supported").toString()).contains("refresh_token");
        assertThat(metadata.path("token_endpoint_auth_methods_supported").toString()).contains("none");
        assertMcp(STATIC_TOKEN);
        assertThat(send(API, "GET", "/actuator/health", null, null).statusCode()).isEqualTo(200);
        assertThat(send(API, "GET", "/actuator/env", null, null).statusCode()).isEqualTo(401);
    }

    @Test @Order(2) void publicPkceClientCanRefreshAndReplayRevokesTheGrant() throws Exception {
        JsonNode client = register("none");
        JsonNode tokens = exchange(client, authorize(client, VERIFIER), VERIFIER);
        assertThat(tokens.path("expires_in").asLong()).isBetween(870L, 900L);
        assertThat(tokens.path("refresh_token").asString()).isNotBlank();
        String access = tokens.path("access_token").asString();
        assertMcp(access);
        // A session alone, refresh token or token for another route does not grant MCP access.
        assertThat(send(API, "POST", "/mcp", "{}", tokens.path("refresh_token").asString()).statusCode()).isEqualTo(401);
        assertThat(send(API, "GET", "/actuator/env", null, access).statusCode()).isEqualTo(401);
        var refreshed = refresh(client, tokens.path("refresh_token").asString(), null);
        assertThat(refreshed.statusCode()).isEqualTo(200);
        JsonNode next = JSON.readTree(refreshed.body());
        assertThat(next.path("refresh_token").asString()).isNotEqualTo(tokens.path("refresh_token").asString());
        assertMcp(next.path("access_token").asString());
        var persisted = context.getBean(OAuthStore.class).findByToken(next.path("refresh_token").asString(),
                org.springframework.security.oauth2.server.authorization.OAuth2TokenType.REFRESH_TOKEN);
        assertThat(persisted.getRefreshToken().getToken().getExpiresAt()).isAfter(java.time.Instant.now().plus(Duration.ofDays(89)));
        assertThat(refresh(client, tokens.path("refresh_token").asString(), null).statusCode()).isEqualTo(400);
        assertThat(refresh(client, next.path("refresh_token").asString(), null).statusCode()).isEqualTo(400);
        assertThat(send(API, "POST", "/mcp", "{}", next.path("access_token").asString()).statusCode()).isEqualTo(401);
    }

    @Test @Order(3) void confidentialClientsUseBasicAuthenticationAndCanRefresh() throws Exception {
        var client = register("client_secret_basic");
        assertThat(client.path("client_secret").asString()).isNotBlank();
        var token = exchange(client, authorize(client, VERIFIER), VERIFIER);
        var refreshed = refresh(client, token.path("refresh_token").asString(), null);
        assertThat(refreshed.statusCode()).isEqualTo(200);
        assertMcp(JSON.readTree(refreshed.body()).path("access_token").asString());
    }

    @Test @Order(4) void rejectsRedirectPkceAndResourceAttacks() throws Exception {
        var client = register("none");
        String url = authorizationPath(client, VERIFIER);
        assertThat(send(browser, "GET", url.replace(enc(REDIRECT), enc("https://attacker.example/callback")), null, null).statusCode()).isEqualTo(400);
        assertThat(send(browser, "GET", url.replace("code_challenge_method=S256", "code_challenge_method=plain"), null, null).statusCode()).isEqualTo(400);
        assertThat(send(browser, "GET", url.replace(enc(RESOURCE), enc("https://other.example/mcp")), null, null).statusCode()).isEqualTo(400);
        assertThat(send(browser, "GET", url + "&resource=" + enc(RESOURCE), null, null).statusCode()).isEqualTo(400);
        String code = authorize(client, VERIFIER);
        assertThat(tokenRequest(client, code, "wrong-pkce-verifier-abcdefghijklmnopqrstuvwxyz0123456789").statusCode()).isEqualTo(400);
        // A fresh grant proves the successful code is single use.
        code = authorize(client, VERIFIER);
        assertThat(tokenRequest(client, code, VERIFIER).statusCode()).isEqualTo(200);
        assertThat(tokenRequest(client, code, VERIFIER).statusCode()).isEqualTo(400);
    }

    @Test @Order(5) void refreshIsBoundToClientResourceAndScopesAndSupportsRevocation() throws Exception {
        var client = register("none");
        var other = register("none");
        var tokens = exchange(client, authorize(client, VERIFIER), VERIFIER);
        String refresh = tokens.path("refresh_token").asString();
        assertThat(refresh(other, refresh, null).statusCode()).isEqualTo(400);
        assertThat(refresh(client, refresh, "resource=" + enc("https://other.example/mcp")).statusCode()).isEqualTo(400);
        assertThat(refresh(client, refresh, "scope=administrator").statusCode()).isEqualTo(400);
        var nextResponse = refresh(client, refresh, "scope=mcp");
        assertThat(nextResponse.statusCode()).isEqualTo(200);
        var next = JSON.readTree(nextResponse.body());
        var revoked = send(API, "POST", "/oauth2/revoke", form("client_id", client.path("client_id").asString(),
                "token", next.path("refresh_token").asString(), "token_type_hint", "refresh_token"), null);
        assertThat(revoked.statusCode()).isEqualTo(200);
        assertThat(refresh(client, next.path("refresh_token").asString(), null).statusCode()).isEqualTo(400);
        assertThat(send(API, "POST", "/mcp", "{}", next.path("access_token").asString()).statusCode()).isEqualTo(401);
    }

    @Test @Order(6) void expiredAccessCanRefreshButExpiredRefreshCannot() throws Exception {
        var client = register("none");
        var tokens = exchange(client, authorize(client, VERIFIER), VERIFIER);
        var store = context.getBean(OAuthStore.class);
        store.jdbc.update("UPDATE oauth2_authorization SET access_token_issued_at=DATEADD('SECOND', -120, CURRENT_TIMESTAMP), access_token_expires_at=DATEADD('SECOND', -60, CURRENT_TIMESTAMP) WHERE registered_client_id=?",
                store.clients.findByClientId(client.path("client_id").asString()).getId());
        assertThat(send(API, "POST", "/mcp", "{}", tokens.path("access_token").asString()).statusCode()).isEqualTo(401);
        var response = refresh(client, tokens.path("refresh_token").asString(), null);
        assertThat(response.statusCode()).isEqualTo(200);
        var next = JSON.readTree(response.body());
        assertMcp(next.path("access_token").asString());
        store.jdbc.update("UPDATE oauth2_authorization SET refresh_token_issued_at=DATEADD('SECOND', -120, CURRENT_TIMESTAMP), refresh_token_expires_at=DATEADD('SECOND', -60, CURRENT_TIMESTAMP) WHERE registered_client_id=?",
                store.clients.findByClientId(client.path("client_id").asString()).getId());
        assertThat(refresh(client, next.path("refresh_token").asString(), null).statusCode()).isEqualTo(400);
    }

    @Test @Order(7) void registrationRejectsUnsafeRedirectsAndOversizedRequests() throws Exception {
        for (String redirect : List.of("http://attacker.example/callback", "file:///etc/passwd", "https://user:pass@example.com", "https://example.com/#fragment")) {
            var response = jsonPost("/oauth2/register", JSON.writeValueAsString(Map.of("redirect_uris", List.of(redirect))));
            assertThat(response.statusCode()).isEqualTo(400);
        }
        assertThat(jsonPost("/oauth2/register", " ".repeat(8193)).statusCode()).isEqualTo(413);
    }

    @Test @Order(8) void concurrentRefreshHasOnlyOneWinnerAndDetectsReuse() throws Exception {
        var client = register("none");
        var tokens = exchange(client, authorize(client, VERIFIER), VERIFIER);
        String body = form("grant_type", "refresh_token", "client_id", client.path("client_id").asString(),
                "refresh_token", tokens.path("refresh_token").asString());
        var request = HttpRequest.newBuilder(URI.create(base + "/oauth2/token"))
                .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var first = API.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        var second = API.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        assertThat(List.of(first.get().statusCode(), second.get().statusCode())).containsExactlyInAnyOrder(200, 400);
    }

    @Test @Order(9) void refreshAndReplayHistorySurviveRestartAndCredentialChangeRevokesTokens() throws Exception {
        var client = register("none");
        var initial = exchange(client, authorize(client, VERIFIER), VERIFIER);
        var next = JSON.readTree(refresh(client, initial.path("refresh_token").asString(), null).body());
        context.close();
        start(PASSWORD);
        assertMcp(next.path("access_token").asString());
        var afterRestart = refresh(client, next.path("refresh_token").asString(), null);
        assertThat(afterRestart.statusCode()).isEqualTo(200);
        var finalTokens = JSON.readTree(afterRestart.body());
        assertMcp(finalTokens.path("access_token").asString());
        assertThat(refresh(client, initial.path("refresh_token").asString(), null).statusCode()).isEqualTo(400);
        assertThat(refresh(client, finalTokens.path("refresh_token").asString(), null).statusCode()).isEqualTo(400);
        // Re-starting with different owner credentials removes every stored grant.
        context.close();
        start(PASSWORD + "-changed", "");
        assertThat(context.getBean(OAuthStore.class).jdbc.queryForObject("SELECT COUNT(*) FROM oauth2_authorization", Integer.class)).isZero();
        assertThat(send(API, "POST", "/mcp", "{}", STATIC_TOKEN).statusCode()).isEqualTo(401);
    }

    @Test @Order(10) void oauthOnlyModeWorksWithoutAStaticBearerSecret() throws Exception {
        login(PASSWORD + "-changed");
        var client = register("none");
        var tokens = exchange(client, authorize(client, VERIFIER), VERIFIER);
        assertMcp(tokens.path("access_token").asString());
        assertThat(refresh(client, tokens.path("refresh_token").asString(), null).statusCode()).isEqualTo(200);
    }

    @Test @Order(11) void abandonedRegistrationsAreCleanedUpButConsentedClientsRemain() throws Exception {
        var abandoned = register("none");
        var consented = register("none");
        var tokens = exchange(consented, authorize(consented, VERIFIER), VERIFIER);
        var store = context.getBean(OAuthStore.class);
        store.jdbc.update("UPDATE oauth2_registered_client SET client_id_issued_at=DATEADD('DAY', -2, CURRENT_TIMESTAMP)");
        store.cleanup();
        assertThat(store.clients.findByClientId(abandoned.path("client_id").asString())).isNull();
        assertThat(store.clients.findByClientId(consented.path("client_id").asString())).isNotNull();
        assertMcp(tokens.path("access_token").asString());
    }

    @Test @Order(12) void boundsLoginAttemptsAndRejectsQueryStringTokenExchange() throws Exception {
        assertThat(send(API, "POST", "/oauth2/token?grant_type=refresh_token", "client_id=unknown", null).statusCode()).isEqualTo(400);
        HttpResponse<String> response = null;
        for (int i = 0; i < 21; i++) {
            response = send(API, "POST", "/login", "username=owner&password=invalid", null);
            if (response.statusCode() == 429) break;
        }
        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(response.headers().firstValue("Retry-After")).contains("60");
    }

    static JsonNode register(String method) throws Exception {
        var result = jsonPost("/oauth2/register", JSON.writeValueAsString(Map.of("client_name", "Integration test",
                "redirect_uris", List.of(REDIRECT), "token_endpoint_auth_method", method)));
        assertThat(result.statusCode()).as(result.body()).isEqualTo(201);
        return JSON.readTree(result.body());
    }
    static String authorizationPath(JsonNode client, String verifier) throws Exception {
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256")
                .digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        return "/oauth2/authorize?" + form("response_type", "code", "client_id", client.path("client_id").asString(),
                "redirect_uri", REDIRECT, "scope", "mcp offline_access", "state", "integration-state", "resource", RESOURCE,
                "code_challenge", challenge, "code_challenge_method", "S256");
    }
    static String authorize(JsonNode client, String verifier) throws Exception {
        var response = send(browser, "GET", authorizationPath(client, verifier), null, null);
        if (response.statusCode() == 200) {
            String state = field(response.body(), "state");
            response = send(browser, "POST", "/oauth2/authorize", form("client_id", client.path("client_id").asString(),
                    "state", state, "scope", "mcp") + "&scope=offline_access", null);
        }
        assertThat(response.statusCode()).as(response.body()).isEqualTo(302);
        String location = response.headers().firstValue("Location").orElseThrow();
        assertThat(location).contains("state=integration-state").doesNotContain("error=");
        return Arrays.stream(URI.create(location).getRawQuery().split("&")).filter(pair -> pair.startsWith("code="))
                .map(pair -> URLDecoder.decode(pair.substring(5), StandardCharsets.UTF_8)).findFirst().orElseThrow();
    }
    static JsonNode exchange(JsonNode client, String code, String verifier) throws Exception {
        var result = tokenRequest(client, code, verifier);
        assertThat(result.statusCode()).as(result.body()).isEqualTo(200);
        return JSON.readTree(result.body());
    }
    static HttpResponse<String> tokenRequest(JsonNode client, String code, String verifier) throws Exception {
        return clientPost(client, "/oauth2/token", form("grant_type", "authorization_code", "client_id", client.path("client_id").asString(),
                "code", code, "redirect_uri", REDIRECT, "code_verifier", verifier, "resource", RESOURCE));
    }
    static HttpResponse<String> refresh(JsonNode client, String token, String extra) throws Exception {
        return clientPost(client, "/oauth2/token", form("grant_type", "refresh_token", "client_id", client.path("client_id").asString(),
                "refresh_token", token) + (extra == null ? "" : "&" + extra));
    }
    static HttpResponse<String> clientPost(JsonNode client, String path, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body));
        if (client.has("client_secret")) request.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                (client.path("client_id").asString() + ":" + client.path("client_secret").asString()).getBytes(StandardCharsets.UTF_8)));
        return API.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    static void assertMcp(String token) throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{},\"clientInfo\":{\"name\":\"oauth-test\",\"version\":\"1\"}}}";
        var response = send(API, "POST", "/mcp", body, token);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.body()).contains("serverInfo");
        String session = response.headers().firstValue("Mcp-Session-Id").orElseThrow();
        for (String rpc : List.of("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"oauth_probe\",\"arguments\":{}}}")) {
            var toolRequest = HttpRequest.newBuilder(URI.create(base + "/mcp"))
                    .header("Authorization", "Bearer " + token).header("Mcp-Session-Id", session)
                    .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(rpc)).build();
            var toolResponse = API.send(toolRequest, HttpResponse.BodyHandlers.ofString());
            assertThat(toolResponse.statusCode()).isIn(200, 202);
            if (rpc.contains("tools/call")) assertThat(toolResponse.body()).contains("oauth-authenticated");
        }
        var request = HttpRequest.newBuilder(URI.create(base + "/mcp")).header("Authorization", "Bearer " + token)
                .header("Mcp-Session-Id", response.headers().firstValue("Mcp-Session-Id").orElseThrow()).DELETE().build();
        assertThat(API.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(200);
    }
    static HttpResponse<String> jsonPost(String path, String body) throws Exception {
        return API.send(HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    static HttpResponse<String> send(HttpClient client, String method, String path, String body, String bearer) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json, text/html, text/event-stream")
                .header("Content-Type", path.equals("/mcp") ? "application/json" : "application/x-www-form-urlencoded");
        if (bearer != null) request.header("Authorization", "Bearer " + bearer);
        return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    static String enc(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    static String form(String... values) {
        var pairs = new ArrayList<String>();
        for (int i = 0; i < values.length; i += 2) pairs.add(enc(values[i]) + "=" + enc(values[i + 1]));
        return String.join("&", pairs);
    }
    static String field(String html, String name) {
        var match = Pattern.compile("name=\"" + name + "\"[^>]*value=\"([^\"]+)\"").matcher(html);
        assertThat(match.find()).as("Hidden field " + name + " in " + html).isTrue();
        return match.group(1);
    }
    static class OAuthProbe {
        @org.springframework.ai.tool.annotation.Tool(name = "oauth_probe", description = "OAuth integration probe without Discord")
        public String probe() { return "oauth-authenticated"; }
    }
    @Configuration(proxyBeanMethods = false) @EnableAutoConfiguration
    @Import(McpHttpAuthConfig.class)
    @ComponentScan("dev.saseq.security.oauth")
    static class TestServer {
        @Bean ToolCallbackProvider probeTools() {
            return MethodToolCallbackProvider.builder().toolObjects(new OAuthProbe()).build();
        }
    }
}
