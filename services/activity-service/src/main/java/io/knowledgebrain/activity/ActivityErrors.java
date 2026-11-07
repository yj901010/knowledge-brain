package io.knowledgebrain.activity;
import org.springframework.web.bind.annotation.*;import org.springframework.http.*;import org.springframework.web.server.ResponseStatusException;
@RestControllerAdvice final class ActivityErrors {
 @ExceptionHandler(Exception.class) ResponseEntity<ProblemDetail> handle(Exception e){HttpStatusCode status=e instanceof ResponseStatusException r?r.getStatusCode():e instanceof IllegalArgumentException?HttpStatus.BAD_REQUEST:HttpStatus.SERVICE_UNAVAILABLE;var problem=ProblemDetail.forStatusAndDetail(status,status.value()==503?"Activity dependency unavailable":"Activity request unavailable");return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(problem);}
}
