package com.cns.spring_house.shared.controller;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;

import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestCookieException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.cns.spring_house.shared.exception.EntityAlreadyExistsException;
import com.cns.spring_house.shared.exception.EntityNotFoundException;
import com.cns.spring_house.shared.exception.ExceptionUtils;
import com.cns.spring_house.shared.exception.FileContentTypeException;
import com.cns.spring_house.shared.exception.FileProcessingException;
import com.cns.spring_house.shared.exception.ValidationException;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** Único texto que ve el cliente ante un fallo no controlado. */
    private static final String GENERIC_ERROR_MESSAGE =
            "Ocurrió un error interno. Intenta nuevamente.";

    /** Datos ocupados por otra operación: no se cambió nada y se puede reintentar. */
    private static final String BUSY_DATA_MESSAGE =
            "Otra operación está usando estos datos en este momento. No se cambió nada; intenta nuevamente.";

    @ExceptionHandler(BadCredentialsException.class)
    ProblemDetail handleBadCredentialsException(BadCredentialsException e) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage());
        problemDetail.setTitle("Authentication Error");
        problemDetail.setProperty("error_category", "Auth");
        problemDetail.setProperty("timestamp", Instant.now());
        return problemDetail;
    }

    /**
     * Falta una cookie obligatoria. Sin este handler la excepción caía en el
     * genérico de abajo y `/v1/auth/refresh` sin cookie respondía 500, como si
     * el servidor estuviera roto: no tener la credencial es un 401, igual que
     * tenerla inválida o ya consumida, y así el cliente no necesita distinguir.
     *
     * Las únicas cookies obligatorias del proyecto son las de sesión
     * (`refreshToken` en refresh y logout), de ahí que el 401 valga como regla
     * global. El mensaje de Spring —que nombra el parámetro y su tipo— no viaja.
     */
    @ExceptionHandler(MissingRequestCookieException.class)
    ProblemDetail handleMissingRequestCookieException(MissingRequestCookieException e) {
        ProblemDetail problemDetail = ProblemDetail
                .forStatusAndDetail(HttpStatus.UNAUTHORIZED, "No existe una sesión válida");
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

        // Un decimal en un campo entero se rechaza al deserializar, antes de que
        // Bean Validation exista: sin este caso el cliente recibiria "El cuerpo
        // de la solicitud es requerido", que no describe el problema.
        Map<String, String> integerErrors = ExceptionUtils.extractNonIntegerFields(e);

        if (!integerErrors.isEmpty()) {
            ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                    HttpStatus.BAD_REQUEST,
                    "Se ha producido uno o mas errores de validacion");
            problemDetail.setTitle("Validation Error");
            problemDetail.setProperty("error_category", "Validation");
            problemDetail.setProperty("timestamp", Instant.now());
            problemDetail.setProperty("errors", integerErrors);
            return problemDetail;
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

    /**
     * Se declara explícitamente para que el handler genérico de abajo no capture
     * las denegaciones de @EnableMethodSecurity y las convierta en 500.
     */
    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail handleAccessDeniedException(AccessDeniedException e) {
        ProblemDetail problemDetail = ProblemDetail
                .forStatusAndDetail(HttpStatus.FORBIDDEN,
                        "No tienes permisos para realizar esta operación");
        problemDetail.setTitle("Access Denied");
        problemDetail.setProperty("error_category", "Auth");
        problemDetail.setProperty("timestamp", Instant.now());
        return problemDetail;
    }

    /**
     * Otra operación retenía los mismos datos y la espera llegó a su tope (o la
     * base eligió esta transacción para romper un interbloqueo). La transacción
     * ya se deshizo entera, así que reintentar es seguro: 409 con ese mensaje,
     * no el 500 genérico que no dice qué hacer.
     */
    @ExceptionHandler({ TransientDataAccessException.class, TransactionTimedOutException.class })
    ProblemDetail handleBusyDataException(Exception e) {
        log.warn("Operacion cancelada por datos en uso: {}", e.getClass().getSimpleName());
        ProblemDetail problemDetail = ProblemDetail
                .forStatusAndDetail(HttpStatus.CONFLICT, BUSY_DATA_MESSAGE);
        problemDetail.setTitle("Data In Use");
        problemDetail.setProperty("error_category", "Data Conflict");
        problemDetail.setProperty("timestamp", Instant.now());
        return problemDetail;
    }

    /**
     * Red de seguridad para cualquier excepción no contemplada. Sin este handler
     * la respuesta la construía DefaultErrorAttributes, que devolvía al cliente
     * `trace`, el mensaje de Hibernate y el SQL de la sentencia fallida.
     *
     * El detalle completo queda en el log del servidor; el cliente solo recibe
     * un mensaje genérico.
     */
    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpectedException(Exception e) {
        log.error("Error interno no controlado", e);
        ProblemDetail problemDetail = ProblemDetail
                .forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, GENERIC_ERROR_MESSAGE);
        problemDetail.setTitle("Internal Error");
        problemDetail.setProperty("error_category", "Generic");
        problemDetail.setProperty("timestamp", Instant.now());
        return problemDetail;
    }

}
