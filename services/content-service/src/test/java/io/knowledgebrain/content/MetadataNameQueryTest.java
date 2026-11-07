package io.knowledgebrain.content;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
class MetadataNameQueryTest {
 @Test void normalizesUnicodeWithoutTrimmingCaseFoldingOrWildcardRules(){assertThat(MetadataNameQuery.parse("cafe\u0301")).isEqualTo("café");assertThat(MetadataNameQuery.parse(" %_ '한글+& ")).isEqualTo(" %_ '한글+& ");assertThat(MetadataNameQuery.parse(" ")).isEqualTo(" ");assertThat(MetadataNameQuery.parse("e\u0301".repeat(64))).isEqualTo("é".repeat(64));assertThat(MetadataNameQuery.parse("🧠".repeat(64))).hasSize(128);}
 @Test void emptyControlsLoneSurrogatesAndExcessiveNormalizedInputFail(){for(String q:new String[]{"","\u0000","a\nb","\u007f","\u0085","\ud800","x".repeat(65),"e\u0301".repeat(65)})assertThatThrownBy(()->MetadataNameQuery.parse(q)).isInstanceOf(ResponseStatusException.class);}
}
