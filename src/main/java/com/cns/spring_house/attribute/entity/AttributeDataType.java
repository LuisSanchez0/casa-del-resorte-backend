package com.cns.spring_house.attribute.entity;

public enum AttributeDataType {
    
    NUMBER,
    STRING,
    BOOLEAN;

    public boolean isValid(Object value) {
        if (value == null) return false;   
        return switch (this) {
            case STRING -> value instanceof String;
            case NUMBER -> value instanceof Number;
            case BOOLEAN -> value instanceof Boolean;
        };
    }

}
