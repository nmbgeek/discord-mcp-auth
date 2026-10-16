package dev.saseq.security.oauth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.web.authentication.AuthenticationConverter;

import java.util.Collections;

/** Public refresh/revocation requests prove possession of a token, not a client secret. */
public final class PublicClientRefresh implements AuthenticationConverter, AuthenticationProvider {
    private final RegisteredClientRepository clients;
    public PublicClientRefresh(RegisteredClientRepository clients) { this.clients = clients; }

    @Override public Authentication convert(HttpServletRequest request) {
        if (!"POST".equals(request.getMethod()) || request.getHeader("Authorization") != null
                || request.getParameter("client_secret") != null) return null;
        boolean refresh = request.getRequestURI().equals("/oauth2/token")
                && "refresh_token".equals(request.getParameter("grant_type"));
        boolean revoke = request.getRequestURI().equals("/oauth2/revoke");
        if (!refresh && !revoke) return null;
        String[] ids = request.getParameterValues("client_id");
        if (ids == null || ids.length != 1 || ids[0].isBlank()) throw new OAuth2AuthenticationException("invalid_client");
        return new OAuth2ClientAuthenticationToken(ids[0], ClientAuthenticationMethod.NONE, null,
                Collections.singletonMap("public_token_operation", Boolean.TRUE));
    }

    @Override public Authentication authenticate(Authentication authentication) {
        var token = (OAuth2ClientAuthenticationToken) authentication;
        if (!ClientAuthenticationMethod.NONE.equals(token.getClientAuthenticationMethod())
                || !Boolean.TRUE.equals(token.getAdditionalParameters().get("public_token_operation"))) return null;
        var client = clients.findByClientId(token.getPrincipal().toString());
        if (client == null || !client.getClientAuthenticationMethods().contains(ClientAuthenticationMethod.NONE)) {
            throw new OAuth2AuthenticationException("invalid_client");
        }
        return new OAuth2ClientAuthenticationToken(client, ClientAuthenticationMethod.NONE, null);
    }

    @Override public boolean supports(Class<?> type) { return OAuth2ClientAuthenticationToken.class.isAssignableFrom(type); }
}
