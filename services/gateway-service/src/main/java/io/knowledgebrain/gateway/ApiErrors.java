package io.knowledgebrain.gateway;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
@RestControllerAdvice
class ApiErrors {
    record Error(String error) {}
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Error> status(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode()).cacheControl(CacheControl.noStore()).body(new Error(exception.getReason()));
    }
}
