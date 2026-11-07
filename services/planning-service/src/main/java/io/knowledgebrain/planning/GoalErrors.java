package io.knowledgebrain.planning;
import org.springframework.web.bind.annotation.*;import org.springframework.http.*;import org.springframework.web.server.ResponseStatusException;
@RestControllerAdvice class GoalErrors {@ExceptionHandler(Exception.class) ResponseEntity<ProblemDetail> error(Exception e){var status=e instanceof ResponseStatusException r?r.getStatusCode():HttpStatus.SERVICE_UNAVAILABLE;var p=ProblemDetail.forStatusAndDetail(status,"Planning request unavailable");return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(p);}}
