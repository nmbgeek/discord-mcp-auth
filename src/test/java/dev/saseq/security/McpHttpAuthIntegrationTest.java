package dev.saseq.security;

import dev.saseq.configs.McpHttpAuthConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercises the real HTTP transport without connecting a Discord bot or sending messages. */
class McpHttpAuthIntegrationTest {
    private static final String SECRET = "integration-secret-0123456789-abcdefghijklmnopqrstuvwxyz";
    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static final AtomicInteger CALLS = new AtomicInteger();
    private static ConfigurableApplicationContext context;
    private static String baseUrl;

    @BeforeAll
    static void startServer() {
        context = new SpringApplicationBuilder(TestServer.class).profiles("http").run(
                "--server.port=0", "--server.address=127.0.0.1", "--server.servlet.context-path=/discord",
                "--spring.ai.mcp.server.streamable-http.mcp-endpoint=/agent/mcp",
                "--MCP_AUTH_ENABLED=true", "--MCP_AUTH_TOKEN=" + SECRET);
        int port = ((WebServerApplicationContext) context).getWebServer().getPort();
        baseUrl = "http://127.0.0.1:" + port + "/discord";
    }

    @AfterAll
    static void stopServer() {
        if (context != null) context.close();
    }

    @Test
    void optOutStartsHttpServerWithoutTokenAndAcceptsUnauthenticatedInitialization() throws Exception {
        try (var localContext = new SpringApplicationBuilder(TestServer.class).profiles("http").run(
                "--server.port=0", "--server.address=127.0.0.1",
                "--MCP_AUTH_ENABLED=false", "--MCP_AUTH_TOKEN=")) {
            int port = ((WebServerApplicationContext) localContext).getWebServer().getPort();
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/json, text/event-stream")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""
                            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                              "protocolVersion":"2025-03-26","capabilities":{},
                              "clientInfo":{"name":"auth-opt-out-test","version":"1.0"}}}
                            """))
                    .build();

            var response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("serverInfo");
            assertThat(response.headers().firstValue("Mcp-Session-Id")).isNotEmpty();
            assertThat(response.headers().firstValue("WWW-Authenticate")).isEmpty();
        }
    }

    @Test
    void healthIsPublicAndContainsNoComponents() throws Exception {
        var response = send("GET", "/actuator/health", null, null, null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"").doesNotContain("components", "details");
    }

    @Test
    void rejectsEveryTransportMethodAndUnknownPaths() throws Exception {
        for (String method : new String[]{"GET", "POST", "DELETE", "OPTIONS"}) {
            var response = send(method, "/agent/mcp", "{}", null, null);
            assertThat(response.statusCode()).as(method).isEqualTo(401);
            assertThat(response.headers().firstValue("WWW-Authenticate")).contains("Bearer realm=\"discord-mcp\"");
        }
        assertThat(send("GET", "/unknown", null, null, null).statusCode()).isEqualTo(401);
        assertThat(send("POST", "/agent/mcp", "{}", "wrong", null).statusCode()).isEqualTo(401);
    }

    @Test
    void authenticatedMcpSessionRequiresTokenOnSubsequentRequests() throws Exception {
        String initialize = """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                  "protocolVersion":"2025-03-26","capabilities":{},
                  "clientInfo":{"name":"auth-test","version":"1.0"}}}
                """;
        var response = send("POST", "/agent/mcp", initialize, SECRET, null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("serverInfo");
        String session = response.headers().firstValue("Mcp-Session-Id").orElseThrow();

        var initialized = send("POST", "/agent/mcp",
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", SECRET, session);
        assertThat(initialized.statusCode()).isEqualTo(202);

        String toolCall = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"auth_probe\",\"arguments\":{}}}";
        assertThat(send("POST", "/agent/mcp", toolCall, null, session).statusCode()).isEqualTo(401);
        assertThat(send("GET", "/agent/mcp", null, null, session).statusCode()).isEqualTo(401);
        assertThat(send("DELETE", "/agent/mcp", null, null, session).statusCode()).isEqualTo(401);
        assertThat(CALLS.get()).isZero();

        var listed = send("POST", "/agent/mcp", "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\"}", SECRET, session);
        assertThat(listed.statusCode()).isEqualTo(200);
        assertThat(listed.body()).contains("auth_probe");
        var called = send("POST", "/agent/mcp", toolCall, SECRET, session);
        assertThat(called.statusCode()).isEqualTo(200);
        assertThat(called.body()).contains("authenticated");
        assertThat(CALLS.get()).isEqualTo(1);
        assertThat(send("DELETE", "/agent/mcp", null, SECRET, session).statusCode()).isEqualTo(200);
    }

    private static HttpResponse<String> send(String method, String path, String body, String token, String session)
            throws Exception {
        var request = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json, text/event-stream")
                .header("Content-Type", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (session != null) request.header("Mcp-Session-Id", session);
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import(McpHttpAuthConfig.class)
    static class TestServer {
        @Bean
        ToolCallbackProvider probeTools() {
            return MethodToolCallbackProvider.builder().toolObjects(new ProbeTool()).build();
        }
    }

    static class ProbeTool {
        @Tool(name = "auth_probe", description = "Checks authenticated tool dispatch without Discord")
        public String probe() {
            CALLS.incrementAndGet();
            return "authenticated";
        }
    }
}
