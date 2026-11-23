package com.mantis.api.rest;

import com.mantis.api.rest.RestDtos.ErrorDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.server.ResponseStatusException;

import java.util.stream.Collectors;

/**
 * Errors for the Mantis-compatible REST API, in the {@code {"code":..,"message":".."}} shape.
 * Only applies to the {@code com.mantis.api.rest} package, so the web UI keeps its existing {@code {"message":..}} errors.
 */
@RestControllerAdvice(basePackages = "com.mantis.api.rest")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RestExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(RestExceptionHandler.class);

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ErrorDto> status(ResponseStatusException e) {
        int code = e.getStatusCode().value();
        String message = e.getReason() == null ? defaultMessage(code) : e.getReason();
        return ResponseEntity.status(code).body(new ErrorDto(code, message));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorDto> invalid(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest().body(new ErrorDto(400, message));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class})
    ResponseEntity<ErrorDto> malformed(Exception e) {
        return ResponseEntity.badRequest().body(new ErrorDto(400, "Malformed or invalid request"));
    }

    @ExceptionHandler({MissingServletRequestPartException.class, MaxUploadSizeExceededException.class})
    ResponseEntity<ErrorDto> upload(Exception e) {
        return ResponseEntity.badRequest().body(new ErrorDto(400, "File is missing or exceeds the configured attachment limit"));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ErrorDto> mediaType(HttpMediaTypeNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(new ErrorDto(415, "Unsupported content type"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ErrorDto> method(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(new ErrorDto(405, "Method not allowed"));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ErrorDto> integrity(DataIntegrityViolationException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorDto(409, "Operation violates a database constraint (duplicate or still in use)"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorDto> unexpected(Exception e) {
        log.error("Unexpected error in Mantis REST API", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorDto(500, "Internal server error"));
    }

    private static String defaultMessage(int code) {
        return switch (code) {
            case 400 -> "Bad request";
            case 401 -> "Authentication required";
            case 403 -> "Access denied";
            case 404 -> "Not found";
            case 409 -> "Conflict";
            case 429 -> "Too many requests";
            default -> "Request failed";
        };
    }
}
