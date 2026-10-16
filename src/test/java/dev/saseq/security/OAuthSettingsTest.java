package dev.saseq.security;

import dev.saseq.security.oauth.OAuthSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.*;

class OAuthSettingsTest {
    private MockEnvironment valid() {
        return new MockEnvironment().withProperty("mcp.oauth.issuer", "https://mcp.example.com")
                .withProperty("mcp.oauth.username", "owner").withProperty("mcp.oauth.password", "plaintext-password-from-env");
    }
    @Test void acceptsPlaintextPasswordAndLongTermDefaults() {
        var settings = new OAuthSettings(valid());
        assertThat(settings.password).isEqualTo("plaintext-password-from-env");
        assertThat(settings.accessTtl.toMinutes()).isEqualTo(15);
        assertThat(settings.refreshTtl.toDays()).isEqualTo(90);
    }
    @ParameterizedTest @ValueSource(strings = {"", "http://mcp.example.com", "https://mcp.example.com/", "https://mcp.example.com/path",
            "https://user:password@mcp.example.com", "https://mcp.example.com?query=1", "https://mcp.example.com#fragment"})
    void rejectsInvalidIssuer(String issuer) {
        assertThatIllegalArgumentException().isThrownBy(() -> new OAuthSettings(valid().withProperty("mcp.oauth.issuer", issuer)));
    }
    @Test void rejectsAuthOptOutAndInvalidCredentials() {
        assertThatIllegalArgumentException().isThrownBy(() -> new OAuthSettings(valid().withProperty("mcp.auth.enabled", "false")));
        assertThatIllegalArgumentException().isThrownBy(() -> new OAuthSettings(valid().withProperty("mcp.oauth.username", "")));
        assertThatIllegalArgumentException().isThrownBy(() -> new OAuthSettings(valid().withProperty("mcp.oauth.password", "too-short")));
        assertThatIllegalArgumentException().isThrownBy(() -> new OAuthSettings(valid().withProperty("server.servlet.context-path", "/discord")));
    }
    @ParameterizedTest @ValueSource(strings = {"/login", "/logout", "/oauth2/token", "/oauth2/authorize", "/actuator/health", "/error"})
    void rejectsEndpointCollisionsWithPublicAuthRoutes(String endpoint) {
        assertThatIllegalArgumentException().isThrownBy(() -> new OAuthSettings(valid()
                .withProperty("spring.ai.mcp.server.streamable-http.mcp-endpoint", endpoint)));
    }
    @Test void rejectsUnboundedTokenLifetimes() {
        assertThatIllegalArgumentException().isThrownBy(() -> new OAuthSettings(valid().withProperty("mcp.oauth.refresh-token-days", "0")));
        assertThatIllegalArgumentException().isThrownBy(() -> new OAuthSettings(valid().withProperty("mcp.oauth.refresh-token-days", "366")));
        assertThatIllegalArgumentException().isThrownBy(() -> new OAuthSettings(valid().withProperty("mcp.oauth.access-token-seconds", "7200")));
    }
}
