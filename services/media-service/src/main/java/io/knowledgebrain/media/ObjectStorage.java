package io.knowledgebrain.media;
import java.time.*;
import java.util.*;
interface ObjectStorage {
 record UploadForm(String url,Map<String,String> fields,Instant expiresAt){}
 record Download(String url,Instant expiresAt){}
 UploadForm upload(MediaStore.Info media);
 String verifyAndSeal(MediaStore.Info media);
 Download download(MediaStore.Stored media);
 boolean ready();
}
