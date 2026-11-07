package io.knowledgebrain.brainquery;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
@RestControllerAdvice
class ApiErrors {
 record Error(String error) {}
 @ExceptionHandler(ResponseStatusException.class) ResponseEntity<Error> status(ResponseStatusException e){return ResponseEntity.status(e.getStatusCode()).cacheControl(CacheControl.noStore()).body(new Error(e.getReason()));}
 @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class) ResponseEntity<Error> malformed(){return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).body(new Error("Invalid search request"));}
}
