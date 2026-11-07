package io.knowledgebrain.integration;
import java.util.*;import java.net.URI;import java.nio.charset.StandardCharsets;import java.security.MessageDigest;import org.springframework.http.*;import org.springframework.web.server.ResponseStatusException;
final class GitHubRules {
 static ResponseStatusException bad(){return new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid GitHub input");}
 static UUID id(String raw){try{UUID id=UUID.fromString(raw);if(id.version()!=7||id.variant()!=2||!id.toString().equals(raw))throw bad();return id;}catch(RuntimeException e){throw bad();}}
 static String url(String raw){try{if(raw==null||raw.length()>256||!raw.equals(raw.strip()))throw bad();var u=URI.create(raw);if(!"https".equals(u.getScheme())||!"github.com".equalsIgnoreCase(u.getHost())||u.getPort()!=-1||u.getRawUserInfo()!=null||u.getRawQuery()!=null||u.getRawFragment()!=null||u.getRawPath().contains("%"))throw bad();String path=u.getRawPath();if(path.endsWith("/"))path=path.substring(0,path.length()-1);String[] p=path.split("/",-1);if(p.length!=3||!p[1].matches("[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?")||!p[2].matches("[A-Za-z0-9_.-]{1,100}")||Set.of(".","..").contains(p[2]))throw bad();return "https://github.com/"+p[1].toLowerCase(Locale.ROOT)+"/"+p[2].toLowerCase(Locale.ROOT);}catch(RuntimeException e){throw bad();}}
 static String key(String raw){if(raw==null||!raw.matches("[0-9a-f]{32}"))throw bad();return raw;}
 static String hash(String raw){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
 static String sha(String raw){if(raw==null||!raw.matches("(?:[0-9a-f]{40}|[0-9a-f]{64})"))throw bad();return raw;}
 static String text(String raw,int max,boolean required){if(raw==null||required&&raw.isBlank()||raw.codePointCount(0,raw.length())>max||raw.codePoints().anyMatch(c->Character.isISOControl(c)||c>=0xd800&&c<=0xdfff))throw bad();return raw;}
 static void version(long value){if(value<1)throw bad();}
}
