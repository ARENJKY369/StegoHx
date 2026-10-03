package com.stegohx.backend.api;

import java.time.Instant;
import java.util.Map;

import com.stegohx.backend.core.StegoException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import jakarta.servlet.http.HttpServletRequest;

/** Uniform JSON error envelope for all API failures. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(StegoException.class)
    public ResponseEntity<Map<String, Object>> stegoError(StegoException e) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }

    @ExceptionHandler({MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class, IllegalArgumentException.class})
    public ResponseEntity<Map<String, Object>> badRequest(Exception e) {
        return build(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> tooLarge(MaxUploadSizeExceededException e) {
        return build(HttpStatus.PAYLOAD_TOO_LARGE, "upload exceeds the 30 MB limit");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> internal(Exception e) {
        return build(HttpStatus.INTERNAL_SERVER_ERROR,
                "internal error: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
    }

    private static ResponseEntity<Map<String, Object>> build(HttpStatus status, String message) {
        String path = null;
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            HttpServletRequest request = attrs.getRequest();
            path = request.getRequestURI();
        }
        return ResponseEntity.status(status).body(Map.of(
                "timestamp", Instant.now().toString(),
                "status", status.value(),
                "error", status.getReasonPhrase(),
                "message", message == null ? "" : message,
                "path", path == null ? "" : path));
    }
}
