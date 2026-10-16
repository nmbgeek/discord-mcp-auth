package dev.saseq.configs;

import dev.saseq.security.BearerTokenFilter;
import dev.saseq.security.oauth.OAuthAccess;
import dev.saseq.security.oauth.OAuthConfig;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Import;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration(proxyBeanMethods = false)
@Import(OAuthConfig.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class McpHttpAuthConfig {

    private static final Logger log = LoggerFactory.getLogger(McpHttpAuthConfig.class);

    @Bean
    public FilterRegistrationBean<Filter> mcpBearerTokenFilter(
            @Value("${mcp.auth.enabled:true}") boolean enabled,
            @Value("${mcp.auth.token:}") String token, ObjectProvider<OAuthAccess> oauth) {
        var registration = new FilterRegistrationBean<Filter>();
        registration.setEnabled(enabled);
        if (!enabled) {
            // Spring inspects the filter even when its registration is disabled.
            registration.setFilter((request, response, chain) -> chain.doFilter(request, response));
            log.warn("HTTP authentication is disabled. Use this only on a trusted local network.");
            return registration;
        }

        // Constructing the filter validates the secret before the HTTP server starts.
        registration.setFilter(new BearerTokenFilter(token, oauth.getIfAvailable()));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/*");
        registration.setAsyncSupported(true);
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC);
        log.info("HTTP bearer token authentication is enabled.");
        return registration;
    }
}
