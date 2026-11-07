package io.knowledgebrain.identity;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.*;

final class TokenCipher {
    private final SecretKeySpec key;private final SecureRandom random=new SecureRandom();
    TokenCipher(String encoded) {byte[] bytes=Base64.getUrlDecoder().decode(encoded);if(bytes.length!=32)throw new IllegalArgumentException("256-bit session encryption key required");key=new SecretKeySpec(bytes,"AES");}
    byte[] encrypt(String value,String context) {
        try{byte[] nonce=new byte[12];random.nextBytes(nonce);var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key,new GCMParameterSpec(128,nonce));cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));byte[] encrypted=cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));return ByteBuffer.allocate(12+encrypted.length).put(nonce).put(encrypted).array();}
        catch(java.security.GeneralSecurityException e){throw new IllegalStateException("Session encryption failed");}
    }
    String decrypt(byte[] value,String context) {
        try{if(value.length<28)throw new IllegalArgumentException("Invalid ciphertext");var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,java.util.Arrays.copyOf(value,12)));cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));return new String(cipher.doFinal(java.util.Arrays.copyOfRange(value,12,value.length)),StandardCharsets.UTF_8);}
        catch(java.security.GeneralSecurityException e){throw new IllegalStateException("Session decryption failed");}
    }
}
