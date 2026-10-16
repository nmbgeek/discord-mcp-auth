package dev.saseq.configs;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

import static org.assertj.core.api.Assertions.assertThat;

class McpHttpAuthConfigTest {
    private final WebApplicationContextRunner web = new WebApplicationContextRunner()
            .withUserConfiguration(McpHttpAuthConfig.class);

    @Test
    void httpFailsClosedWithoutTokenEvenWithoutHttpProfile() {
        web.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class)
                    .hasStackTraceContaining("MCP_AUTH_TOKEN");
        });
    }

    @Test
    void httpAllowsExplicitLocalOptOut() {
        web.withPropertyValues("mcp.auth.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(FilterRegistrationBean.class).isEnabled()).isFalse();
        });
    }

    @Test
    void stdioDoesNotRequireToken() {
        new ApplicationContextRunner().withUserConfiguration(McpHttpAuthConfig.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(FilterRegistrationBean.class);
        });
    }
}
