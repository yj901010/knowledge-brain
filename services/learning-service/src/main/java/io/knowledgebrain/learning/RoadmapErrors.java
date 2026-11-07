package io.knowledgebrain.learning;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.dao.DataAccessException;
import java.util.Map;
@RestControllerAdvice(basePackageClasses=RoadmapController.class)
final class RoadmapErrors {
 @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class) ResponseEntity<Map<String,String>> status(org.springframework.web.server.ResponseStatusException failure){return ResponseEntity.status(failure.getStatusCode()).cacheControl(CacheControl.noStore()).body(Map.of("error",java.util.Optional.ofNullable(failure.getReason()).orElse("Roadmap request unavailable")));}
 @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,java.io.IOException.class}) ResponseEntity<Map<String,String>> malformed(Exception failure){return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).body(Map.of("error","Invalid Roadmap request"));}
 @ExceptionHandler({DataAccessException.class,IllegalStateException.class}) ResponseEntity<Map<String,String>> unavailable(RuntimeException failure){org.slf4j.LoggerFactory.getLogger(getClass()).warn("roadmap persistence unavailable; transaction/revision retained");return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(Map.of("error","Roadmap unavailable"));}
}
