package io.knowledgebrain.content;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class WikiLinksTest {
    @Test void parsesTitleAliasFragmentExplicitIdAndUtf16Offsets() {
        String text="😀 [[공부#절|표시]] [[note:0199b30d-0000-7000-8000-000000000001|ID]]";
        var links=WikiLinks.parse(text);assertThat(links).hasSize(2);assertThat(links.getFirst().target()).isEqualTo("공부");assertThat(links.getFirst().label()).isEqualTo("표시");assertThat(links.getFirst().fragment()).isEqualTo("절");assertThat(links.getFirst().start()).isEqualTo(3);assertThat(text.substring(links.getFirst().start(),links.getFirst().end())).isEqualTo("[[공부#절|표시]]");assertThat(links.getLast().requestedId().version()).isEqualTo(7);
    }
    @Test void ignoresEscapesCodeFencesInlineCodeIndentAndComments() {
        String text="\\[[escaped]] `[[inline]]` ``[[also]]`` [[real]]\n```java\n[[fenced]]\n```\n~~~\n[[tilde]]\n~~~\n    [[indent]]\n<!-- [[comment]]\n[[comment2]] --> [[last]]";
        assertThat(WikiLinks.parse(text)).extracting(WikiLinks.Token::target).containsExactly("real","last");
        assertThat(WikiLinks.parse("```\n[[unclosed fence]]")).isEmpty();assertThat(WikiLinks.parse("<!-- [[unclosed comment]]")).isEmpty();
    }
    @Test void malformedOrUnsupportedLinksRemainLiteralAndRepeatedLinksStayDistinct() {
        assertThat(WikiLinks.parse("[[]] [[x|a|b]] [[note:bad]] [[a\nb]] [[unclosed")).isEmpty();
        assertThat(WikiLinks.parse("[[x]] [[x|alias]]")).hasSize(2);assertThat(WikiLinks.parse("`unmatched [[x]]")).hasSize(1);
    }
    @Test void titleNormalizationIsNfcCaseSensitiveAndLinksAreBounded() {
        assertThat(WikiLinks.key(" e\u0301 ")).isEqualTo("é");assertThat(WikiLinks.key("A")).isNotEqualTo(WikiLinks.key("a"));
        assertThat(WikiLinks.parse("[[x]]".repeat(256))).hasSize(256);assertThatThrownBy(()->WikiLinks.parse("[[x]]".repeat(257))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
}
