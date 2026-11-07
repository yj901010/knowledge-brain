package io.knowledgebrain.content;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
class PropertyValueFilterTest {
    @Test void stringsAndListElementsKeepWhitespaceCaseAndLiteralCharacters(){
        assertThat(PropertyValueFilter.parse("STRING"," %_ '한글+&\n ").json()).isEqualTo("\" %_ '한글+&\\n \"");
        assertThat(PropertyValueFilter.parse("STRING","").json()).isEqualTo("\"\"");assertThat(PropertyValueFilter.parse("STRING_LIST","").json()).isEqualTo("[\"\"]");assertThat(PropertyValueFilter.parse("STRING_LIST","whole item").contains()).isTrue();
        PropertyValueFilter.input("🧠".repeat(200));assertThatThrownBy(()->PropertyValueFilter.input("🧠".repeat(201))).isInstanceOf(ResponseStatusException.class);
    }
    @Test void decimalComparisonNormalizesEquivalentTokensWithoutFloatingPoint(){
        for(String input:new String[]{"80","80.0","8e1","800e-1"})assertThat(PropertyValueFilter.parse("NUMBER",input).json()).isEqualTo("80");
        String precise="12345678901234567890.123456789012345678";assertThat(PropertyValueFilter.parse("NUMBER",precise).json()).isEqualTo(precise);
        String expanded="9".repeat(38)+"0".repeat(18);assertThat(PropertyValueFilter.parse("NUMBER",expanded).json()).isEqualTo(expanded);
        assertThat(PropertyValueFilter.parse("NUMBER","-0.000").json()).isEqualTo("0");
    }
    @Test void booleansAndCalendarDatesAreStrict(){
        assertThat(PropertyValueFilter.parse("BOOLEAN","false").json()).isEqualTo("false");assertThat(PropertyValueFilter.parse("DATE","2024-02-29").json()).isEqualTo("\"2024-02-29\"");
        for(String input:new String[]{"", "False","1"," true"})assertThatThrownBy(()->PropertyValueFilter.parse("BOOLEAN",input)).isInstanceOf(ResponseStatusException.class);
        for(String input:new String[]{"2025-02-29","2026-13-01","2026-1-1","2026-10-22T00:00:00Z"})assertThatThrownBy(()->PropertyValueFilter.parse("DATE",input)).isInstanceOf(ResponseStatusException.class);
    }
    @Test void malformedOrExcessiveConditionsFailBeforeSql(){
        for(String input:new String[]{""," 1","+1","01","NaN","Infinity","1e100","1e-19","1e9999999","9".repeat(39)})assertThatThrownBy(()->PropertyValueFilter.parse("NUMBER",input)).isInstanceOf(ResponseStatusException.class);
        for(String input:new String[]{"\u0000","\ud800","x".repeat(201)})assertThatThrownBy(()->PropertyValueFilter.parse("STRING",input)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->PropertyValueFilter.parse("UNKNOWN","x")).isInstanceOf(ResponseStatusException.class);
    }

    @Test void rangesKeepInclusiveDecimalAndCalendarBoundariesWithoutRounding(){
        var equal=PropertyValueFilter.bounds("NUMBER","8e1","80.00");assertThat(equal.minJson()).isEqualTo("80");assertThat(equal.maxJson()).isEqualTo("80");
        String low="12345678901234567890.123456789012345678",high="12345678901234567890.123456789012345679";
        assertThat(PropertyValueFilter.bounds("NUMBER",low,high).minJson()).isEqualTo(low);
        assertThat(PropertyValueFilter.bounds("NUMBER",null,"-0.0").maxJson()).isEqualTo("0");
        assertThat(PropertyValueFilter.bounds("DATE","2024-02-29",null).minJson()).isEqualTo("\"2024-02-29\"");
        assertThat(PropertyValueFilter.bounds("DATE",null,"2024-03-01").maxJson()).isEqualTo("\"2024-03-01\"");
    }
    @Test void invertedMixedEmptyAndUnsupportedRangesAreRejected(){
        for(String type:new String[]{"STRING","BOOLEAN","STRING_LIST"})assertThatThrownBy(()->PropertyValueFilter.bounds(type,"1",null)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->PropertyValueFilter.bounds("NUMBER","0.000000000000000002","0.000000000000000001")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->PropertyValueFilter.bounds("NUMBER","-2","-10")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->PropertyValueFilter.bounds("DATE","2024-03-01","2024-02-29")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->PropertyValueFilter.bounds("DATE",null,"2025-02-29")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->PropertyValueFilter.selection("80","80",null)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->PropertyValueFilter.selection(null,"",null)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->PropertyValueFilter.selection(null,null,"\u0000")).isInstanceOf(ResponseStatusException.class);
    }
}
