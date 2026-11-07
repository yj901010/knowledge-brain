package io.knowledgebrain.content;
import java.text.Normalizer;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Deliberately bounded Wiki syntax scanner; does not render HTML or claim full Markdown parsing. */
final class WikiLinks {
    record Token(int start,int end,String target,String key,UUID requestedId,String label,String fragment) {}
    static String key(String title) { return Normalizer.normalize(title.strip(),Normalizer.Form.NFC); }
    static List<Token> parse(String text) {
        var result=new ArrayList<Token>();char fence=0;int fenceSize=0;
        for(int i=0;i<text.length();) {
            if(i==0 || text.charAt(i-1)=='\n') {
                int end=text.indexOf('\n',i);if(end<0)end=text.length();String line=text.substring(i,end);
                int prefix=0;while(prefix<line.length() && prefix<4 && line.charAt(prefix)==' ')prefix++;
                int run=prefix;char marker=run<line.length()?line.charAt(run):0;
                if(marker=='`' || marker=='~')while(run<line.length() && line.charAt(run)==marker)run++;
                if(fence!=0) {
                    if(prefix<=3 && marker==fence && run-prefix>=fenceSize && line.substring(run).isBlank()) { fence=0;fenceSize=0; }
                    i=end<text.length()?end+1:end;continue;
                }
                if(prefix<=3 && (marker=='`' || marker=='~') && run-prefix>=3) { fence=marker;fenceSize=run-prefix;i=end<text.length()?end+1:end;continue; }
                if(line.startsWith("    ") || line.startsWith("\t")) { i=end<text.length()?end+1:end;continue; }
            }
            if(text.startsWith("<!--",i)) { int end=text.indexOf("-->",i+4);i=end<0?text.length():end+3;continue; }
            if(text.charAt(i)=='\\') { i=Math.min(text.length(),i+2);continue; }
            if(text.charAt(i)=='`') {
                int start=i;while(i<text.length() && text.charAt(i)=='`')i++;int width=i-start,closing=-1;
                for(int j=i;j<text.length();) { if(text.charAt(j)!='`') {j++;continue;}int begin=j;while(j<text.length() && text.charAt(j)=='`')j++;if(j-begin==width){closing=j;break;} }
                if(closing>=0)i=closing;continue;
            }
            if(text.startsWith("[[",i)) {
                int end=text.indexOf("]]",i+2);int newline=text.indexOf('\n',i+2);
                if(end>=0 && (newline<0 || end<newline)) {
                    String raw=text.substring(i+2,end);var token=token(raw,i,end+2);
                    if(token!=null) { result.add(token);if(result.size()>256)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"At most 256 Wiki Links per Note"); }
                    i=end+2;continue;
                }
            }
            i++;
        }
        return List.copyOf(result);
    }
    private static Token token(String raw,int start,int end) {
        if(raw.indexOf('[')>=0 || raw.indexOf(']')>=0 || raw.indexOf('\r')>=0)return null;
        String[] alias=raw.split("\\|",-1);if(alias.length>2)return null;String target=alias[0],label=alias.length==2?alias[1].strip():null;
        int hash=target.indexOf('#');String fragment=hash<0?null:target.substring(hash+1).strip();target=(hash<0?target:target.substring(0,hash)).strip();
        if(target.isEmpty() || target.codePointCount(0,target.length())>200 || (label!=null && label.codePointCount(0,label.length())>200) || (fragment!=null && fragment.codePointCount(0,fragment.length())>200))return null;
        UUID id=null;
        if(target.startsWith("note:"))try { id=UUID.fromString(target.substring(5));if(id.version()!=7 || id.variant()!=2 || !id.toString().equals(target.substring(5)))return null; }catch(IllegalArgumentException error) {return null;}
        return new Token(start,end,target,key(target),id,label,fragment);
    }
}
