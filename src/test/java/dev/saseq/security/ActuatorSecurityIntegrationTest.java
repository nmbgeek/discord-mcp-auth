package dev.saseq.security;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ActuatorSecurityIntegrationTest {
    private static final String SECRET = "actuator-fixture-0123456789-abcdefghijklmnopqrstuvwxyz";

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void wildcardExposureCannotEnableSensitiveEndpoints(boolean auth) throws Exception {
        try (var context = new SpringApplicationBuilder(McpHttpAuthIntegrationTest.TestServer.class)
                .profiles("http").run("--server.port=0", "--server.address=127.0.0.1",
                        "--MCP_AUTH_ENABLED=" + auth, "--MCP_AUTH_TOKEN=" + SECRET,
                        "--DISCORD_TOKEN=synthetic-discord-secret",
                        "--management.endpoints.web.exposure.include=*")) {
            int port = ((WebServerApplicationContext) context).getWebServer().getPort();
            var client = HttpClient.newHttpClient();
            for (String endpoint : new String[]{"health", "env", "configprops", "heapdump", "logfile", "loggers", "mappings"}) {
                for (boolean authenticated : new boolean[]{false, true}) {
                    var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/" + endpoint))
                            .timeout(Duration.ofSeconds(10));
                    if (authenticated) builder.header("Authorization", "Bearer " + SECRET);
                    var response = client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
                    int expected = endpoint.equals("health") ? 200 : auth && !authenticated ? 401 : 404;
                    assertThat(response.statusCode()).as("%s auth=%s header=%s", endpoint, auth, authenticated).isEqualTo(expected);
                    assertThat(response.body()).doesNotContain(SECRET, "synthetic-discord-secret");
                    if (endpoint.equals("health")) {
                        assertThat(response.body()).contains("\"status\":\"UP\"").doesNotContain("components", "details");
                    }
                }
            }
            assertThat(context.getEnvironment().getProperty("management.endpoint.env.show-values")).isEqualTo("never");
            assertThat(context.getEnvironment().getProperty("management.endpoint.configprops.show-values")).isEqualTo("never");
        }
    }
}
