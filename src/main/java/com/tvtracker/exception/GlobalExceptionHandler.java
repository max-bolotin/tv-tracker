package com.tvtracker.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ShowNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(ShowNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(errorBody(ex.getMessage()));
    }

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleMaxUpload(org.springframework.web.multipart.MaxUploadSizeExceededException ex) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(errorBody("File too large. Increase spring.servlet.multipart.max-file-size if needed."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(errorBody(ex.getMessage()));
    }

    private Map<String, Object> errorBody(String message) {
        return Map.of("error", message, "timestamp", Instant.now().toString());
    }
}
