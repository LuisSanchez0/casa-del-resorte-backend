package com.cns.spring_house.exception;

public class BusinessException extends RuntimeException {

    public BusinessException(String message) {
        super(message);
    }

    public BusinessException() {
        super("Ha ocurrido un error de negocio");
    }
    
}
