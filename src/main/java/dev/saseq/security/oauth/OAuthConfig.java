package dev.saseq.security.oauth;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.password.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.server.authorization.*;
import org.springframework.security.oauth2.server.authorization.authentication.*;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.*;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.context.SecurityContextHolderFilter;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "mcp.oauth.enabled", havingValue = "true")
@EnableWebSecurity
@ComponentScan(basePackageClasses = OAuthEndpoints.class)
public class OAuthConfig {
    @Bean OAuthSettings oauthSettings(Environment env) { return new OAuthSettings(env); }
    @Bean PasswordEncoder oauthPasswordEncoder() {
        return new DelegatingPasswordEncoder("pbkdf2", Map.of("pbkdf2", Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8()));
    }
    @Bean OAuthStore oauthStore(OAuthSettings settings, PasswordEncoder encoder) throws Exception {
        return new OAuthStore(settings, encoder);
    }
    @Bean RegisteredClientRepository registeredClients(OAuthStore store) { return store.clients; }
    @Bean OAuth2AuthorizationConsentService authorizationConsents(OAuthStore store) { return store.consents; }
    @Bean OAuthAccess oauthAccess(OAuthSettings settings, OAuthStore store) { return new OAuthAccess(settings, store); }
    @Bean UserDetailsService oauthUser(OAuthSettings settings, PasswordEncoder encoder) {
        return new InMemoryUserDetailsManager(User.withUsername(settings.username)
                .password(encoder.encode(settings.password)).roles("OWNER").build());
    }
    @Bean AuthorizationServerSettings authorizationServerSettings(OAuthSettings settings) {
        return AuthorizationServerSettings.builder().issuer(settings.issuer).build();
    }
    @Bean OAuth2TokenGenerator<?> oauthTokenGenerator(OAuthSettings settings) {
        var access = new OAuth2AccessTokenGenerator();
        access.setAccessTokenCustomizer(context -> context.getClaims()
                .audience(new java.util.ArrayList<>(List.of(settings.resource))).claim("resource", settings.resource));
        // Spring's default generator intentionally omits public-client refresh tokens.
        // Our clients use mandatory PKCE, single-use rotation and durable replay detection.
        SecureRandom random = new SecureRandom();
        OAuth2TokenGenerator<OAuth2RefreshToken> refresh = context -> {
            if (!OAuth2TokenType.REFRESH_TOKEN.equals(context.getTokenType())) return null;
            var consentedScopes = context.getAuthorization() == null ? context.getAuthorizedScopes()
                    : context.getAuthorization().getAuthorizedScopes();
            if (!consentedScopes.contains("offline_access")) return null;
            byte[] bytes = new byte[48];
            random.nextBytes(bytes);
            Instant now = Instant.now();
            return new OAuth2RefreshToken(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes), now,
                    now.plus(settings.refreshTtl));
        };
        return new DelegatingOAuth2TokenGenerator(access, refresh);
    }

    @Bean @Order(1)
    SecurityFilterChain authorizationChain(HttpSecurity http, OAuthSettings settings, OAuthStore store,
            OAuth2TokenGenerator<?> generator, PasswordEncoder encoder) throws Exception {
        var server = new OAuth2AuthorizationServerConfigurer();
        var publicClients = new PublicClientRefresh(store.clients);
        http.securityMatcher(server.getEndpointsMatcher())
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .exceptionHandling(errors -> errors.authenticationEntryPoint(new LoginUrlAuthenticationEntryPoint("/login")))
                .addFilterBefore(new OAuthRequestGuard(settings), SecurityContextHolderFilter.class)
                .with(server, oauth -> oauth
                        .tokenGenerator(generator)
                        .clientAuthentication(clients -> clients.authenticationConverter(publicClients)
                                .authenticationProvider(publicClients)
                                .authenticationProviders(providers -> providers.forEach(provider -> {
                                    if (provider instanceof ClientSecretAuthenticationProvider secret) secret.setPasswordEncoder(encoder);
                                })))
                        .tokenEndpoint(endpoint -> endpoint.authenticationProviders(providers ->
                                providers.replaceAll(provider -> serialized(provider, store))))
                        .tokenRevocationEndpoint(endpoint -> endpoint.authenticationProviders(providers ->
                                providers.replaceAll(provider -> serialized(provider, store))))
                        .authorizationServerMetadataEndpoint(endpoint -> endpoint.authorizationServerMetadataCustomizer(metadata -> metadata
                                .claim("registration_endpoint", settings.issuer + "/oauth2/register")
                                .scopes(scopes -> { scopes.clear(); scopes.addAll(List.of("mcp", "offline_access")); })
                                .tokenEndpointAuthenticationMethods(methods -> { methods.clear(); methods.addAll(List.of("none", "client_secret_basic")); })
                                .grantTypes(types -> { types.clear(); types.addAll(List.of("authorization_code", "refresh_token")); })
                                .claims(claims -> {
                                    claims.remove("introspection_endpoint");
                                    claims.remove("introspection_endpoint_auth_methods_supported");
                                    claims.remove("device_authorization_endpoint");
                                    claims.remove("pushed_authorization_request_endpoint");
                                    claims.put("revocation_endpoint_auth_methods_supported", List.of("none", "client_secret_basic"));
                                }))));
        return http.build();
    }

    @Bean @Order(2)
    SecurityFilterChain browserChain(HttpSecurity http, OAuthSettings settings) throws Exception {
        // MCP requests are authenticated by BearerTokenFilter on every request. A browser
        // session is never sufficient to call a tool. CSRF remains enabled for login/logout.
        http.authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .csrf(csrf -> csrf.ignoringRequestMatchers(settings.endpoint, "/oauth2/register"))
                .formLogin(Customizer.withDefaults())
                .logout(logout -> logout.logoutSuccessUrl("/login?logout"))
                .addFilterBefore(new OAuthRequestGuard(settings), SecurityContextHolderFilter.class)
                .headers(headers -> headers.frameOptions(frame -> frame.deny())
                        .referrerPolicy(policy -> policy.policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)));
        return http.build();
    }

    private static AuthenticationProvider serialized(AuthenticationProvider delegate, OAuthStore store) {
        return new AuthenticationProvider() {
            @Override public Authentication authenticate(Authentication authentication) {
                // H2 holds an exclusive process lock on the database; this monitor also
                // serializes token exchanges inside that single process.
                synchronized (store) {
                    if (authentication instanceof OAuth2RefreshTokenAuthenticationToken refresh) {
                        var client = (OAuth2ClientAuthenticationToken) refresh.getPrincipal();
                        if (client.isAuthenticated() && client.getRegisteredClient() != null) {
                            store.rejectReplay(refresh.getRefreshToken(), client.getRegisteredClient().getId());
                        }
                    }
                    return delegate.authenticate(authentication);
                }
            }
            @Override public boolean supports(Class<?> type) { return delegate.supports(type); }
        };
    }
}
