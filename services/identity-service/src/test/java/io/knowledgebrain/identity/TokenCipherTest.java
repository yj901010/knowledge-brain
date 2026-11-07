package io.knowledgebrain.identity;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class TokenCipherTest {
 @Test void encryptedValuesAreRandomizedAndBoundToSessionAndPurpose(){var cipher=new TokenCipher("A".repeat(43));byte[] a=cipher.encrypt("private-token","session-a:access"),b=cipher.encrypt("private-token","session-a:access");assertThat(a).isNotEqualTo(b);assertThat(cipher.decrypt(a,"session-a:access")).isEqualTo("private-token");assertThatThrownBy(()->cipher.decrypt(a,"session-b:access")).isInstanceOf(RuntimeException.class);assertThatThrownBy(()->cipher.decrypt(a,"session-a:refresh")).isInstanceOf(RuntimeException.class);a[a.length-1]^=1;assertThatThrownBy(()->cipher.decrypt(a,"session-a:access")).isInstanceOf(RuntimeException.class);}
}
