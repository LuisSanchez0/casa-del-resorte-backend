package com.cns.spring_house.util;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.validation.FieldError;

public class Exception {

    private Exception() {

    }

    public static boolean isCausedBy(Throwable cause, Class<? extends Throwable> exceptionType) {
        while (cause != null) {
            if (exceptionType.isInstance(cause)) {
                return true;
            }
            cause = cause.getCause();
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
