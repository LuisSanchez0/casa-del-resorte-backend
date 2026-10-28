package com.cns.spring_house.shared.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.TransactionTimedOutException;

import com.cns.spring_house.shared.exception.EntityNotFoundException;
import com.cns.spring_house.shared.exception.ValidationException;

/**
 * Contrato de errores: un 4xx controlado conserva su mensaje de negocio; un 5xx
 * inesperado solo devuelve un texto genérico, nunca internals.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    /** Rastros que jamás deben viajar al cliente. */
    private static final String[] INTERNALS = {
        "jdbc", "select", "insert into", "sqlexception", "hibernate",
        "org.springframework", "com.cns", "runtimeexception", "stacktrace",
        "dataintegrityviolation", ".java:", "at ",
    };

    private void assertHasNoInternals(ProblemDetail problemDetail) {
        String body = String.valueOf(problemDetail.getDetail())
                + String.valueOf(problemDetail.getTitle())
                + String.valueOf(problemDetail.getProperties());
        String normalized = body.toLowerCase(Locale.ROOT);
        for (String forbidden : INTERNALS) {
            assertThat(normalized)
                    .as("el cuerpo no debe contener '%s'", forbidden)
                    .doesNotContain(forbidden);
        }
    }

    @Test
    void validationExceptionKeepsItsBusinessMessage() {
        ProblemDetail problemDetail = handler.handleValidationException(
                new ValidationException("El item no esta disponible"));

        assertThat(problemDetail.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problemDetail.getDetail()).isEqualTo("El item no esta disponible");
        assertHasNoInternals(problemDetail);
    }

    @Test
    void entityNotFoundKeepsItsBusinessMessage() {
        ProblemDetail problemDetail = handler.handleEntityNotFoundException(
                new EntityNotFoundException("No existe el producto especificado"));

        assertThat(problemDetail.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(problemDetail.getDetail()).isEqualTo("No existe el producto especificado");
        assertHasNoInternals(problemDetail);
    }

    @Test
    void anUnexpectedExceptionIsReducedToAGenericMessage() {
        RuntimeException leaky = new RuntimeException(
                "could not execute statement [ERROR: null value in column \"item_id\"] "
                        + "jdbc SELECT * FROM inventory_item; org.hibernate.exception.X");

        ProblemDetail problemDetail = handler.handleUnexpectedException(leaky);

        assertThat(problemDetail.getStatus())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(problemDetail.getDetail())
                .isEqualTo("Ocurrió un error interno. Intenta nuevamente.");
        assertThat(problemDetail.getDetail()).doesNotContain(leaky.getMessage());
        assertHasNoInternals(problemDetail);
    }

    /**
     * Una espera de cerrojo cancelada por el tope, un interbloqueo o el plazo de
     * la transaccion vencido: la transaccion se deshizo entera, así que el
     * cliente recibe un 409 que se puede reintentar, sin el SQL de la sentencia.
     */
    @Test
    void dataHeldByAnotherOperationIsARetryableConflict() {
        for (Exception busy : new Exception[] {
            new QueryTimeoutException("ERROR: canceling statement due to user request; select * from sale for no key update"),
            new CannotAcquireLockException("could not obtain lock on row in relation \"inventory\""),
            new TransactionTimedOutException("Transaction timed out: deadline was Mon Oct 24"),
        }) {
            ProblemDetail problemDetail = handler.handleBusyDataException(busy);

            assertThat(problemDetail.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
            assertThat(problemDetail.getDetail()).isEqualTo(
                    "Otra operación está usando estos datos en este momento. No se cambió nada; intenta nuevamente.");
            assertHasNoInternals(problemDetail);
        }
    }

    /**
     * Un decimal en un campo entero se rechaza al deserializar, antes de que
     * exista Bean Validation. El cliente debe recibir el mismo contrato de
     * validación que en cualquier otro campo inválido, con el nombre del campo y
     * sin una línea del parser de Jackson.
     */
    @Test
    void aFractionalValueForAnIntegerFieldIsAFieldValidationError() {
        HttpMessageNotReadableException notReadable = new HttpMessageNotReadableException(
                "JSON parse error: Cannot coerce Floating-point value (2.5)",
                integerCoercionFailure("minStock"),
                new org.springframework.http.server.ServletServerHttpRequest(
                        new org.springframework.mock.web.MockHttpServletRequest()));

        ProblemDetail problemDetail =
                handler.handleHttpMessageNotReadableException(notReadable);

        assertThat(problemDetail.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problemDetail.getProperties())
                .extracting("errors")
                .isEqualTo(java.util.Map.of("minStock", "El valor debe ser un numero entero"));
        assertHasNoInternals(problemDetail);
        assertThat(String.valueOf(problemDetail.getProperties()))
                .doesNotContainIgnoringCase("jackson")
                .doesNotContainIgnoringCase("coerce");
    }

    /** Un cuerpo ilegible por otro motivo conserva su mensaje de siempre. */
    @Test
    void anUnreadableBodyForAnotherReasonKeepsItsMessage() {
        HttpMessageNotReadableException notReadable = new HttpMessageNotReadableException(
                "Required request body is missing",
                new org.springframework.http.server.ServletServerHttpRequest(
                        new org.springframework.mock.web.MockHttpServletRequest()));

        ProblemDetail problemDetail =
                handler.handleHttpMessageNotReadableException(notReadable);

        assertThat(problemDetail.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problemDetail.getDetail()).isEqualTo("El cuerpo de la solicitud es requerido");
        assertHasNoInternals(problemDetail);
    }

    /** Reproduce el fallo tal y como lo construye Jackson al abortar la coerción. */
    private static Throwable integerCoercionFailure(String field) {
        tools.jackson.databind.exc.MismatchedInputException mismatch =
                tools.jackson.databind.exc.MismatchedInputException.from(
                        null, Integer.class,
                        "Cannot coerce Floating-point value (2.5) to `java.lang.Integer`");
        mismatch.prependPath(new tools.jackson.core.JacksonException.Reference(Object.class, field));
        return mismatch;
    }

    @Test
    void accessDeniedIsForbiddenInsteadOfInternalError() {
        ProblemDetail problemDetail = handler.handleAccessDeniedException(
                new org.springframework.security.access.AccessDeniedException("Access is denied"));

        assertThat(problemDetail.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertHasNoInternals(problemDetail);
    }

}
