package com.cns.spring_house.shared.controller;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.cns.spring_house.shared.exception.AuthenticationException;
import com.cns.spring_house.shared.exception.EntityAlreadyExistsException;
import com.cns.spring_house.shared.exception.EntityNotFoundException;
import com.cns.spring_house.shared.exception.ExceptionUtils;
import com.cns.spring_house.shared.exception.FileContentTypeException;
import com.cns.spring_house.shared.exception.FileProcessingException;
import com.cns.spring_house.shared.exception.ValidationException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BadCredentialsException.class)
    ProblemDetail handleBadCredentialsException(BadCredentialsException e) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage());
        problemDetail.setTitle("Authentication Error");
        problemDetail.setProperty("error_category", "Auth");
        problemDetail.setProperty("timestamp", Instant.now());
        return problemDetail;
    }

    @ExceptionHandler(EntityNotFoundException.class)
    ProblemDetail handleEntityNotFoundException(EntityNotFoundException e) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problemDetail.setTitle("Entity Not Found");
        problemDetail.setProperty("error_category", "Generic");
        problemDetail.setProperty("timestamp", Instant.now());
        return problemDetail;
    }

    @ExceptionHandler(EntityAlreadyExistsException.class)
    ProblemDetail handleEntityAlreadyExistsException(EntityAlreadyExistsException e) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problemDetail.setTitle("Entity Duplicated");
        problemDetail.setProperty("error_category", "Data Conflict");
        problemDetail.setProperty("timestamp", Instant.now());
        return problemDetail;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleMethodArgumentNotValidException(MethodArgumentNotValidException e) {
        Map<String, String> errors = ExceptionUtils.extractFieldErrors(e.getFieldErrors());
        ProblemDetail problemDetail = ProblemDetail
                .forStatusAndDetail(HttpStatus.BAD_REQUEST, "Se ha producido uno o mas errores de validacion");
        problemDetail.setTitle("Validation Error");
        problemDetail.setProperty("error_category", "Validation");
        problemDetail.setProperty("timestamp", Instant.now());
        problemDetail.setProperty("errors", errors);
        return problemDetail;
    }

    @ExceptionHandler(ValidationException.class)
    public ProblemDetail handleValidationException(ValidationException e) {
        ProblemDetail problemDetail = ProblemDetail
                .forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problemDetail.setTitle("Validation Error");
        problemDetail.setProperty("error_category", "Validation");
        problemDetail.setProperty("timestamp", Instant.now());
        if (e.getErrors() != null) {
            problemDetail.setProperty("errors", e.getErrors());
        }
        return problemDetail;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleHttpMessageNotReadableException(HttpMessageNotReadableException e) {
        String message = "El cuerpo de la solicitud es requerido";
        if (ExceptionUtils.isCausedBy(e, DateTimeParseException.class)) {
            message = "Error al parsear debito a un formato de fecha invalido";
        }
        ProblemDetail problemDetail = ProblemDetail
                .forStatusAndDetail(HttpStatus.BAD_REQUEST, message);
        problemDetail.setTitle("Missing Request");
        problemDetail.setProperty("error_category", "Validation");
        problemDetail.setProperty("timestamp", Instant.now());
        return problemDetail;
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleMethodArgumentTypeMismatchException(MethodArgumentTypeMismatchException e) {
        ProblemDetail problemDetail = ProblemDetail
                .forStatusAndDetail(HttpStatus.BAD_REQUEST, "Es necesario el parametro: " + e.getPropertyName());
        problemDetail.setTitle("Missing Required Propertie");
        problemDetail.setProperty("error_category", "Validation");
        problemDetail.setProperty("timestamp", Instant.now());
        return problemDetail;
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ProblemDetail handleHttpRequestMethodNotSupportedException(HttpRequestMethodNotSupportedException e) {
        ProblemDetail problemDetail = ProblemDetail
                .forStatusAndDetail(HttpStatus.METHOD_NOT_ALLOWED, "El metodo de la solicitud no es valido");
        problemDetail.setTitle("Method Not Allowed");
        problemDetail.setProperty("error_category", "Generic");
        problemDetail.setProperty("timestamp", Instant.now());
        return problemDetail;
    }

    @ExceptionHandler(FileContentTypeException.class)
    public ProblemDetail handleFileContentTypeException(FileContentTypeException e) {
        ProblemDetail problemDetail = ProblemDetail
                .forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problemDetail.setTitle("Validation Error");
        problemDetail.setProperty("error_category", "Generic");
        problemDetail.setProperty("timestamp", Instant.now());
        return problemDetail;
    }

    @ExceptionHandler(FileProcessingException.class)
    public ProblemDetail handleFileProcessingException(FileProcessingException e) {
        ProblemDetail problemDetail = ProblemDetail
                .forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        problemDetail.setTitle("Failed Process");
        problemDetail.setProperty("error_category", "Generic");
        problemDetail.setProperty("timestamp", Instant.now());
        return problemDetail;
    }

    @ExceptionHandler(AuthenticationException.class)
    ProblemDetail handleAuthenticationException(AuthenticationException e) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage());
        problemDetail.setTitle("Authentication Error");
        problemDetail.setProperty("error_category", "Auth");
        problemDetail.setProperty("timestamp", Instant.now());
        return problemDetail;
    }

}
