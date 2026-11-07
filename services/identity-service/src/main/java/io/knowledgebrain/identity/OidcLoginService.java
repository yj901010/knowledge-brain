package io.knowledgebrain.identity;

import com.google.protobuf.ByteString;
import io.knowledgebrain.contracts.identity.account.v1.*;
import java.security.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

final class OidcLoginService {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(OidcLoginService.class);
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final Clock clock;
    private final TokenCipher cipher;private final OidcProvider provider;private final UuidV7 ids;private final SecureRandom random=new SecureRandom();
    record Started(String authorizationUrl,String binding) {@Override public String toString(){return "Started[REDACTED]";}}
    record Current(UUID principalId,String kind,Instant expiresAt,UUID claimGuestId) {}
    OidcLoginService(DataSource source,Clock clock,TokenCipher cipher,OidcProvider provider){jdbc=new JdbcTemplate(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));tx.setTimeout(20);this.clock=clock;this.cipher=cipher;this.provider=provider;ids=new UuidV7(clock);}
    Started start(String guest,String trace){
        var rows=jdbc.queryForList("SELECT principal_id FROM guest_identity WHERE credential_hash=? AND state='ACTIVE' AND credential_revoked_at IS NULL AND credential_expires_at>?",GuestSessionService.hash(guest),Timestamp.from(clock.instant()));if(rows.isEmpty())throw GuestSessionService.unauthorized();
        String state=random(),binding=random(),nonce=random(),verifier=random();
        jdbc.update("DELETE FROM oidc_login_flow WHERE expires_at<?",Timestamp.from(clock.instant()));
        jdbc.update("INSERT INTO oidc_login_flow(state_hash,binding_hash,guest_hash,nonce,verifier,trace_id,expires_at) VALUES(?,?,?,?,?,?,?)",GuestSessionService.hash(state),GuestSessionService.hash(binding),GuestSessionService.hash(guest),nonce,cipher.encrypt(verifier,"flow:"+state),trace,Timestamp.from(clock.instant().plusSeconds(300)));
        try{return new Started(provider.authorization(state,nonce,Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(java.nio.charset.StandardCharsets.US_ASCII)))),binding);}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    GuestSessionService.Issued callback(String state,String binding,String guest,String code){
        if(code==null||code.isBlank()||code.length()>2048)throw GuestSessionService.unauthorized();
        byte[] stateHash=GuestSessionService.hash(state),bindingHash=GuestSessionService.hash(binding),guestHash=GuestSessionService.hash(guest);
        // Delete-and-return is committed before contacting Keycloak: one browser-bound callback attempt.
        var flows=jdbc.queryForList("DELETE FROM oidc_login_flow WHERE state_hash=? AND binding_hash=? AND guest_hash=? AND expires_at>? RETURNING *",stateHash,bindingHash,guestHash,Timestamp.from(clock.instant()));
        if(flows.isEmpty()){log.warn("oidc_callback rejected stage=browser_binding");throw GuestSessionService.unauthorized();}var flow=flows.getFirst();log.info("oidc_callback stage=flow_consumed trace_id={}",flow.get("trace_id"));
        var tokens=provider.exchange(code,cipher.decrypt((byte[])flow.get("verifier"),"flow:"+state),(String)flow.get("nonce"));String credential=random();log.info("oidc_callback stage=tokens_verified trace_id={}",flow.get("trace_id"));
        return tx.execute(status->{
            var guests=jdbc.queryForList("SELECT principal_id FROM guest_identity WHERE credential_hash=? AND state='ACTIVE' AND credential_revoked_at IS NULL AND credential_expires_at>? FOR UPDATE",guestHash,Timestamp.from(clock.instant()));if(guests.isEmpty())throw GuestSessionService.unauthorized();UUID guestId=(UUID)guests.getFirst().get("principal_id");
            UUID candidate=ids.next();jdbc.update("INSERT INTO registered_account(principal_id,issuer,subject,created_at) VALUES(?,?,?,?) ON CONFLICT(issuer,subject) DO NOTHING",candidate,provider.issuer,tokens.subject(),Timestamp.from(clock.instant()));
            UUID account=jdbc.queryForObject("SELECT principal_id FROM registered_account WHERE issuer=? AND subject=?",UUID.class,provider.issuer,tokens.subject());
            jdbc.update("UPDATE guest_identity SET state='CLAIMED',claimed_by=?,credential_revoked_at=? WHERE principal_id=?",account,Timestamp.from(clock.instant()),guestId);
            String context="session:"+Base64.getUrlEncoder().withoutPadding().encodeToString(GuestSessionService.hash(credential));
            jdbc.update("INSERT INTO registered_session(credential_hash,principal_id,access_token,refresh_token,access_expires_at,expires_at,claim_guest_id) VALUES(?,?,?,?,?,?,?)",GuestSessionService.hash(credential),account,cipher.encrypt(tokens.access(),context+":access"),cipher.encrypt(tokens.refresh(),context+":refresh"),Timestamp.from(tokens.accessExpires()),Timestamp.from(tokens.sessionExpires()),guestId);
            UUID eventId=ids.next();var now=clock.instant();String trace=(String)flow.get("trace_id");var payload=GuestSpaceClaimRequestedPayloadV1.newBuilder().setGuestPrincipalId(guestId.toString()).setRegisteredPrincipalId(account.toString()).build();
            var event=EventEnvelopeV1.newBuilder().setEventId(eventId.toString()).setEventType("GuestSpaceClaimRequested").setEventVersion(1).setOccurredAtEpochMillis(now.toEpochMilli()).setProducer("identity-service").setAggregateType("RegisteredAccount").setAggregateId(account.toString()).setActorId(account.toString()).setTraceId(trace).setPayload(ByteString.copyFrom(payload.toByteArray())).build();
            jdbc.update("INSERT INTO outbox_event(id,aggregatetype,aggregateid,type,occurred_at,trace_id,schema_group,schema_artifact,schema_version,payload) VALUES(?,'account',?,'GuestSpaceClaimRequested',?,?,'identity','account-events','1',?)",eventId,account,Timestamp.from(now),trace,event.toByteArray());
            return new GuestSessionService.Issued(credential,new GuestSessionService.Principal(account,"REGISTERED",tokens.sessionExpires()));
        });
    }
    Current current(String credential){return tx.execute(status->{
        byte[] hash=GuestSessionService.hash(credential);var rows=jdbc.queryForList("SELECT s.*,a.subject FROM registered_session s JOIN registered_account a ON a.principal_id=s.principal_id WHERE s.credential_hash=? AND s.revoked_at IS NULL AND s.expires_at>? FOR UPDATE OF s",hash,Timestamp.from(clock.instant()));if(rows.isEmpty())throw GuestSessionService.unauthorized();var row=rows.getFirst();String context="session:"+Base64.getUrlEncoder().withoutPadding().encodeToString(hash),subject=(String)row.get("subject");
        String access=cipher.decrypt((byte[])row.get("access_token"),context+":access");Instant expires=((Timestamp)row.get("expires_at")).toInstant();
        if(!((Timestamp)row.get("access_expires_at")).toInstant().isAfter(clock.instant().plusSeconds(20))){var refreshed=provider.refresh(cipher.decrypt((byte[])row.get("refresh_token"),context+":refresh"),subject);access=refreshed.access();expires=expires.isBefore(refreshed.sessionExpires())?expires:refreshed.sessionExpires();jdbc.update("UPDATE registered_session SET access_token=?,refresh_token=?,access_expires_at=?,expires_at=? WHERE credential_hash=?",cipher.encrypt(access,context+":access"),cipher.encrypt(refreshed.refresh(),context+":refresh"),Timestamp.from(refreshed.accessExpires()),Timestamp.from(expires),hash);}
        provider.active(access,subject);return new Current((UUID)row.get("principal_id"),"REGISTERED",expires,(UUID)row.get("claim_guest_id"));
    });}
    void revoke(String credential){
        byte[] hash=GuestSessionService.hash(credential);var rows=jdbc.queryForList("UPDATE registered_session SET revoked_at=? WHERE credential_hash=? AND revoked_at IS NULL RETURNING refresh_token",Timestamp.from(clock.instant()),hash);if(rows.isEmpty())throw GuestSessionService.unauthorized();
        // Local revocation already persists even when Keycloak is unavailable.
        try{provider.revoke(cipher.decrypt((byte[])rows.getFirst().get("refresh_token"),"session:"+Base64.getUrlEncoder().withoutPadding().encodeToString(hash)+":refresh"));}catch(org.springframework.web.server.ResponseStatusException ignored){}
    }
    private String random(){byte[] bytes=new byte[32];random.nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
}
