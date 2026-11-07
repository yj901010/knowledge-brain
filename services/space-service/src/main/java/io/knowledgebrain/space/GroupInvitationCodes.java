package io.knowledgebrain.space;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
final class GroupInvitationCodes {
 private final byte[] key;
 GroupInvitationCodes(String key){if(!valid(key))throw new IllegalArgumentException("32-byte Group invitation key required");this.key=Base64.getUrlDecoder().decode(key);}
 static boolean valid(String code){if(code==null||!code.matches("[A-Za-z0-9_-]{43}"))return false;try{byte[] bytes=Base64.getUrlDecoder().decode(code);return bytes.length==32&&Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(code);}catch(IllegalArgumentException e){return false;}}
 String generate(UUID id){try{var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(("brain-group-invitation-v1:"+id).getBytes(StandardCharsets.US_ASCII)));}catch(GeneralSecurityException e){throw new IllegalStateException("Invitation code unavailable");}}
 static byte[] hash(String code){try{return MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.US_ASCII));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
}
