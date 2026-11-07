package io.knowledgebrain.identity;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.sql.Timestamp;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class GuestSessionService {
    static final String COOKIE="__Host-brain_guest";
    private final GuestRegistrationStore registrations;
    private OidcLoginService registered;
    void registered(OidcLoginService value){registered=value;}
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final SecureRandom random=new SecureRandom();
    record Principal(UUID principalId,String kind,Instant expiresAt) {}
    record Issued(String token,Principal principal) {
        @Override public String toString() { return "Issued[credential=REDACTED]"; }
    }
    GuestSessionService(GuestRegistrationStore registrations,DataSource source,Clock clock) {
        this.registrations=registrations;this.jdbc=new JdbcTemplate(source);this.clock=clock;
    }
    Issued create(String trace) {
        byte[] secret=new byte[32];random.nextBytes(secret);
        String token=Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        var created=registrations.register(hash(token),trace);
        return new Issued(token,resolve(token));
    }
    Principal resolve(String token) {
        byte[] hash=hash(token);
        var rows=jdbc.query("SELECT principal_id,credential_expires_at FROM guest_identity WHERE credential_hash=? AND state='ACTIVE' AND credential_revoked_at IS NULL AND credential_expires_at>?",
            (rs,n)->new Principal(rs.getObject(1,UUID.class),"GUEST",rs.getTimestamp(2).toInstant()),hash,Timestamp.from(clock.instant()));
        if(rows.isEmpty()){if(registered==null)throw unauthorized();var account=registered.current(token);return new Principal(account.principalId(),account.kind(),account.expiresAt());}return rows.getFirst();
    }
    void revoke(String token) {
        int updated=jdbc.update("UPDATE guest_identity SET credential_revoked_at=? WHERE credential_hash=? AND state='ACTIVE' AND credential_revoked_at IS NULL AND credential_expires_at>?",
            Timestamp.from(clock.instant()),hash(token),Timestamp.from(clock.instant()));
        if(updated!=1){if(registered==null)throw unauthorized();registered.revoke(token);}
    }
    static byte[] hash(String token) {
        if(token==null || !token.matches("[A-Za-z0-9_-]{43}"))throw unauthorized();
        try {
            byte[] decoded=Base64.getUrlDecoder().decode(token);
            if(decoded.length!=32 || !Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(token))throw unauthorized();
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
        } catch(NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    static ResponseStatusException unauthorized() { return new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid guest credential"); }
}
