package io.knowledgebrain.media;
import io.minio.*;
import io.minio.http.Method;
import java.net.*;
import java.time.*;
import java.nio.file.*;
import java.security.*;
import java.security.cert.CertificateFactory;
import java.util.*;
import javax.net.ssl.*;
import okhttp3.OkHttpClient;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
final class MinioStorage implements ObjectStorage {
 private final MinioClient internal,signer;private final Clock clock;private final String publicOrigin;private static final String BUCKET="brain-media";
 MinioStorage(String endpoint,String publicEndpoint,String access,String secret,String trustFile,Clock clock) throws Exception {
  origin(endpoint);origin(publicEndpoint);this.clock=clock;publicOrigin=publicEndpoint;var trust=KeyStore.getInstance(KeyStore.getDefaultType());trust.load(null,null);try(var in=Files.newInputStream(Path.of(trustFile))){int n=0;for(var cert:CertificateFactory.getInstance("X.509").generateCertificates(in))trust.setCertificateEntry("storage-"+n++,cert);}var factory=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());factory.init(trust);var manager=(X509TrustManager)factory.getTrustManagers()[0];var tls=SSLContext.getInstance("TLS");tls.init(null,new TrustManager[]{manager},null);var http=new OkHttpClient.Builder().sslSocketFactory(tls.getSocketFactory(),manager).connectTimeout(3,java.util.concurrent.TimeUnit.SECONDS).readTimeout(10,java.util.concurrent.TimeUnit.SECONDS).callTimeout(12,java.util.concurrent.TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build();internal=MinioClient.builder().endpoint(endpoint).credentials(access,secret).region("us-east-1").httpClient(http).build();signer=MinioClient.builder().endpoint(publicEndpoint).credentials(access,secret).region("us-east-1").httpClient(http).build();
 }
 private static void origin(String value){var uri=URI.create(value);if(!"https".equals(uri.getScheme()) || uri.getHost()==null || uri.getRawUserInfo()!=null || uri.getRawQuery()!=null || uri.getRawFragment()!=null || !uri.getRawPath().isEmpty())throw new IllegalArgumentException("Fixed HTTPS storage origin required");}
 private static String staging(MediaStore.Info media){return "uploads/staging/"+media.spaceId()+"/"+media.mediaId();}
 private static String sealed(MediaStore.Info media){return "uploads/sealed/"+media.spaceId()+"/"+media.mediaId();}
 public UploadForm upload(MediaStore.Info media){if(!"PENDING".equals(media.state()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Upload is already complete");try{Instant expires=clock.instant().plusSeconds(300);var policy=new PostPolicy(BUCKET,ZonedDateTime.ofInstant(expires,ZoneOffset.UTC));policy.addEqualsCondition("key",staging(media));policy.addEqualsCondition("Content-Type",media.mimeType());policy.addEqualsCondition("x-amz-meta-sha256",media.sha256());policy.addContentLengthRangeCondition(media.byteSize(),media.byteSize());var fields=new HashMap<>(signer.getPresignedPostFormData(policy));fields.put("key",staging(media));fields.put("Content-Type",media.mimeType());fields.put("x-amz-meta-sha256",media.sha256());return new UploadForm(publicOrigin+"/"+BUCKET,Map.copyOf(fields),expires);}catch(Exception e){throw unavailable();}}
 public String verifyAndSeal(MediaStore.Info media){try{
  var stat=internal.statObject(StatObjectArgs.builder().bucket(BUCKET).object(staging(media)).build());if(stat.size()!=media.byteSize() || !media.mimeType().equals(stat.contentType()))throw invalid();String version=stat.versionId();if(version==null || version.equals("null"))throw unavailable();var digest=MessageDigest.getInstance("SHA-256");long count=0;try(var stream=internal.getObject(GetObjectArgs.builder().bucket(BUCKET).object(staging(media)).versionId(version).build())){byte[] buffer=new byte[8192];int n;while((n=stream.read(buffer))!=-1){count+=n;if(count>media.byteSize())throw invalid();digest.update(buffer,0,n);}}if(count!=media.byteSize() || !HexFormat.of().formatHex(digest.digest()).equals(media.sha256()))throw invalid();
  var result=internal.copyObject(CopyObjectArgs.builder().bucket(BUCKET).object(sealed(media)).source(CopySource.builder().bucket(BUCKET).object(staging(media)).versionId(version).build()).build());if(result.versionId()==null || result.versionId().equals("null"))throw unavailable();return result.versionId();
 }catch(ResponseStatusException e){throw e;}catch(io.minio.errors.ErrorResponseException e){if(Set.of("NoSuchKey","NoSuchObject").contains(e.errorResponse().code()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Upload bytes are not available");throw unavailable();}catch(Exception e){throw unavailable();}}
 public Download download(MediaStore.Stored media){if(!"AVAILABLE".equals(media.info().state()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Upload is not complete");try{String filename=URLEncoder.encode(media.info().filename(),java.nio.charset.StandardCharsets.UTF_8).replace("+","%20");String url=signer.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder().method(Method.GET).bucket(BUCKET).object(sealed(media.info())).versionId(media.objectVersion()).expiry(60).extraQueryParams(Map.of("response-content-type","application/octet-stream","response-content-disposition","attachment; filename=\"download\"; filename*=UTF-8''"+filename)).build());return new Download(url,clock.instant().plusSeconds(60));}catch(Exception e){throw unavailable();}}
 public boolean ready(){try{return internal.getBucketVersioning(GetBucketVersioningArgs.builder().bucket(BUCKET).build()).status().toString().equalsIgnoreCase("Enabled");}catch(Exception e){return false;}}
 private static ResponseStatusException unavailable(){return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Object storage unavailable");}
 private static ResponseStatusException invalid(){return new ResponseStatusException(HttpStatus.BAD_REQUEST,"Uploaded size, MIME or SHA-256 does not match");}
}

