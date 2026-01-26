package com.cns.spring_house.shared.exception;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.validation.FieldError;

public class ExceptionUtils {

    private ExceptionUtils() {

    }

    public static Throwable getRootCause(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }

    public static boolean isCausedBy(Throwable throwable, Class<? extends Throwable> exceptionType) {
        if (throwable == null || exceptionType == null) {
            return false;
        }
        Throwable current = throwable;
        while (current != null) {
            if (exceptionType.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    public static Map<String, String> extractFieldErrors(List<FieldError> errors) {
        return errors.stream()
                .collect(Collectors.toMap(
                        FieldError::getField,
                        FieldError::getDefaultMessage));
    }

}
