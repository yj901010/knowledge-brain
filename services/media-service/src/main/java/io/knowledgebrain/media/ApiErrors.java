package io.knowledgebrain.media;

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
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    ResponseEntity<Error> persistence() {
        return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(new Error("Content persistence unavailable"));
    }
    @ExceptionHandler({org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,org.springframework.http.converter.HttpMessageNotReadableException.class})
    ResponseEntity<Error> malformed() {
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).body(new Error("Invalid Media request"));
    }
}
