package dev.saseq.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class BearerTokenFilterTest {
    private static final String SECRET = "test-secret-0123456789-abcdefghijklmnopqrstuvwxyz";
    private final BearerTokenFilter filter = new BearerTokenFilter(SECRET);

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"short", "a secret with spaces that is long enough", "invalid!secret-01234567890123456789"})
    void rejectsInvalidConfigurationWithoutDisclosingTheSecret(String secret) {
        var error = assertThatIllegalArgumentException().isThrownBy(() -> new BearerTokenFilter(secret));
        error.withMessageContaining("MCP_AUTH_TOKEN");
        if (secret != null && !secret.isEmpty()) {
            error.withMessageNotContaining(secret);
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"Basic abc", "Bearer", "Bearer wrong", "Bearer test-secret-0123456789-abcdefghijklmnopqrstuvwxyz ",
            "Bearer test-secret-0123456789-abcdefghijklmnopqrstuvwxyz, Bearer other"})
    void rejectsMissingOrInvalidAuthorization(String header) throws Exception {
        var request = new MockHttpServletRequest("POST", "/mcp");
        if (header != null) request.addHeader("Authorization", header);
        assertRejected(request);
    }

    @Test
    void rejectsDuplicateAuthorizationHeaders() throws Exception {
        var request = new MockHttpServletRequest("POST", "/mcp");
        request.addHeader("Authorization", "Bearer " + SECRET);
        request.addHeader("Authorization", "Bearer " + SECRET);
        assertRejected(request);
    }

    @Test
    void ignoresQueryTokensAndSessionIds() throws Exception {
        var request = new MockHttpServletRequest("GET", "/mcp");
        request.addParameter("access_token", SECRET);
        request.addHeader("Mcp-Session-Id", "existing-session");
        assertRejected(request);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Bearer ", "bearer ", "BEARER   "})
    void acceptsBearerSchemeCaseAndSpaces(String prefix) throws Exception {
        var request = new MockHttpServletRequest("POST", "/custom/mcp");
        request.addHeader("Authorization", prefix + SECRET);
        var called = new AtomicBoolean();
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> called.set(true));
        assertThat(called).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "HEAD"})
    void allowsOnlyBasicReadOnlyHealthProbe(String method) throws Exception {
        var request = new MockHttpServletRequest(method, "/discord/actuator/health");
        request.setContextPath("/discord");
        var called = new AtomicBoolean();
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> called.set(true));
        assertThat(called).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health/components", "/actuator/env", "/mcp", "/error", "/actuator/health/../mcp"})
    void protectsAllOtherPaths(String path) throws Exception {
        assertRejected(new MockHttpServletRequest("GET", path));
    }

    @Test
    void doesNotExemptHealthWrites() throws Exception {
        assertRejected(new MockHttpServletRequest("POST", "/actuator/health"));
    }

    private void assertRejected(MockHttpServletRequest request) throws Exception {
        var response = new MockHttpServletResponse();
        var called = new AtomicBoolean();
        filter.doFilter(request, response, (req, res) -> called.set(true));
        assertThat(called).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bearer realm=\"discord-mcp\"");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getContentAsString()).isEqualTo("{\"error\":\"unauthorized\"}");
    }
}
