package dev.saseq.security.oauth;

import org.h2.jdbcx.JdbcConnectionPool;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.server.authorization.*;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;

/** One embedded database per server instance. The directory must be on a persistent volume. */
public final class OAuthStore implements OAuth2AuthorizationService, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(OAuthStore.class);
    private static final long REFRESH_RETRY_SECONDS = 60;
    private final JdbcConnectionPool pool;
    public final JdbcTemplate jdbc;
    public final JdbcRegisteredClientRepository clients;
    public final JdbcOAuth2AuthorizationConsentService consents;
    private final JdbcOAuth2AuthorizationService authorizations;
    private final TransactionTemplate transactions;
    private Instant nextCleanup = Instant.EPOCH;

    public OAuthStore(OAuthSettings settings, PasswordEncoder encoder) throws Exception {
        Files.createDirectories(settings.directory);
        Files.setPosixFilePermissions(settings.directory, PosixFilePermissions.fromString("rwx------"));
        pool = JdbcConnectionPool.create("jdbc:h2:file:" + settings.directory.resolve("authorizations")
                + ";DB_CLOSE_ON_EXIT=FALSE", "sa", "");
        pool.setMaxConnections(8);
        jdbc = new JdbcTemplate(pool);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(pool));
        String prefix = "org/springframework/security/oauth2/server/authorization/";
        for (String schema : new String[]{"client/oauth2-registered-client-schema.sql",
                "oauth2-authorization-schema.sql", "oauth2-authorization-consent-schema.sql"}) {
            String sql = new ClassPathResource(prefix + schema).getContentAsString(StandardCharsets.UTF_8)
                    .replace("CREATE TABLE ", "CREATE TABLE IF NOT EXISTS ");
            new ResourceDatabasePopulator(new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8))).execute(pool);
        }
        jdbc.execute("CREATE TABLE IF NOT EXISTS oauth_refresh_history (digest VARCHAR(64) PRIMARY KEY, "
                + "authorization_id VARCHAR(100) NOT NULL, client_id VARCHAR(100) NOT NULL, expires_at BIGINT NOT NULL)");
        // Legacy history has no retry allowance. Migration preserves all current grants.
        jdbc.execute("ALTER TABLE oauth_refresh_history ADD COLUMN IF NOT EXISTS used_at BIGINT DEFAULT 0 NOT NULL");
        jdbc.execute("ALTER TABLE oauth_refresh_history ADD COLUMN IF NOT EXISTS replacement_digest VARCHAR(64)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS oauth_identity (id INT PRIMARY KEY, verifier VARCHAR(512) NOT NULL)");
        clients = new JdbcRegisteredClientRepository(jdbc);
        consents = new JdbcOAuth2AuthorizationConsentService(jdbc, clients);
        authorizations = new JdbcOAuth2AuthorizationService(jdbc, clients);
        // A password, username, issuer or resource change revokes existing grants on restart.
        String identity = digest(settings.username + "\0" + settings.password + "\0" + settings.resource);
        var existing = jdbc.queryForList("SELECT verifier FROM oauth_identity WHERE id=1", String.class);
        if (existing.isEmpty() || !encoder.matches(identity, existing.get(0))) {
            jdbc.update("DELETE FROM oauth2_authorization");
            jdbc.update("DELETE FROM oauth2_authorization_consent");
            jdbc.update("DELETE FROM oauth_refresh_history");
            jdbc.update("MERGE INTO oauth_identity KEY(id) VALUES (1, ?)", encoder.encode(identity));
        }
        cleanup();
        Files.setPosixFilePermissions(settings.directory.resolve("authorizations.mv.db"),
                PosixFilePermissions.fromString("rw-------"));
    }

    @Override
    public synchronized void save(OAuth2Authorization authorization) {
        if (Instant.now().isAfter(nextCleanup)) cleanup();
        transactions.executeWithoutResult(status -> {
            OAuth2Authorization previous = authorizations.findById(authorization.getId());
            if (previous != null && previous.getRefreshToken() != null && authorization.getRefreshToken() != null) {
                var old = previous.getRefreshToken().getToken();
                if (!old.getTokenValue().equals(authorization.getRefreshToken().getToken().getTokenValue())) {
                    jdbc.update("MERGE INTO oauth_refresh_history "
                                    + "(digest, authorization_id, client_id, expires_at, used_at, replacement_digest) "
                                    + "KEY(digest) VALUES (?, ?, ?, ?, ?, ?)",
                            digest(old.getTokenValue()), previous.getId(), previous.getRegisteredClientId(),
                            old.getExpiresAt().getEpochSecond(), Instant.now().getEpochSecond(),
                            digest(authorization.getRefreshToken().getToken().getTokenValue()));
                }
            }
            authorizations.save(authorization);
        });
    }

    /** Return the already-issued pair for a brief retry; never rotate again or extend its expiry. */
    public synchronized OAuth2Authorization retryRefresh(String token, String registeredClientId, Set<String> requestedScopes) {
        var history = jdbc.query("SELECT authorization_id, expires_at, used_at, replacement_digest "
                        + "FROM oauth_refresh_history WHERE digest=? AND client_id=?",
                (rs, row) -> new UsedRefresh(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getString(4)),
                digest(token), registeredClientId);
        if (history.isEmpty()) return null;
        var used = history.get(0);
        long now = Instant.now().getEpochSecond();
        if (now >= used.usedAt() + REFRESH_RETRY_SECONDS) {
            jdbc.update("DELETE FROM oauth2_authorization WHERE id=?", used.authorizationId());
            log.warn("OAuth refresh token reused outside the retry window; authorization revoked");
            throw new OAuth2AuthenticationException("invalid_grant");
        }
        var authorization = authorizations.findById(used.authorizationId());
        // A revoked, expired, or superseded result cannot be recovered. During the retry
        // window reject it without deleting a newer grant another request has just renewed.
        if (used.expiresAt() <= now || authorization == null
                || authorization.getRefreshToken() == null || !authorization.getRefreshToken().isActive()
                || authorization.getAccessToken() == null || !authorization.getAccessToken().isActive()
                || !digest(authorization.getRefreshToken().getToken().getTokenValue()).equals(used.replacementDigest())) {
            throw new OAuth2AuthenticationException("invalid_grant");
        }
        var access = authorization.getAccessToken().getToken();
        // Proof-bound tokens must go through Spring's proof validation, never this bearer retry path.
        if (!OAuth2AccessToken.TokenType.BEARER.equals(access.getTokenType())) {
            throw new OAuth2AuthenticationException("invalid_grant");
        }
        var scopes = requestedScopes.isEmpty() ? authorization.getAuthorizedScopes() : requestedScopes;
        if (!scopes.equals(access.getScopes())) throw new OAuth2AuthenticationException("invalid_scope");
        log.info("Recovered OAuth refresh response within the retry window");
        return authorization;
    }

    private record UsedRefresh(String authorizationId, long expiresAt, long usedAt, String replacementDigest) {}

    public synchronized void cleanup() {
        long now = Instant.now().getEpochSecond();
        nextCleanup = Instant.now().plusSeconds(3600);
        jdbc.update("DELETE FROM oauth_refresh_history WHERE expires_at < ?", now);
        jdbc.update("DELETE FROM oauth2_authorization WHERE "
                + "COALESCE(refresh_token_expires_at, access_token_expires_at, authorization_code_expires_at, "
                + "CURRENT_TIMESTAMP) < CURRENT_TIMESTAMP");
        // Abandoned public registrations should not exhaust the client cap forever.
        // Keep clients with consent so a temporarily disconnected agent can sign in again.
        jdbc.update("DELETE FROM oauth2_registered_client c WHERE client_id_issued_at < DATEADD('DAY', -1, CURRENT_TIMESTAMP) "
                + "AND NOT EXISTS (SELECT 1 FROM oauth2_authorization a WHERE a.registered_client_id=c.id) "
                + "AND NOT EXISTS (SELECT 1 FROM oauth2_authorization_consent s WHERE s.registered_client_id=c.id)");
    }

    @Override public synchronized void remove(OAuth2Authorization authorization) { authorizations.remove(authorization); }
    @Override public synchronized OAuth2Authorization findById(String id) { return authorizations.findById(id); }
    @Override public synchronized OAuth2Authorization findByToken(String token, OAuth2TokenType type) {
        return authorizations.findByToken(token, type);
    }
    @Override public void close() { pool.dispose(); }

    static String digest(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception ex) { throw new IllegalStateException("SHA-256 unavailable", ex); }
    }
}
