package com.cns.spring_house.exception;

public class InvalidLeafConfigurationException extends BusinessException {

    private InvalidLeafConfigurationException(String message) {
        super(message);
    }

    public static InvalidLeafConfigurationException missingSecondLengthForHoledLeaf(
            String leafNumber) {
        return new InvalidLeafConfigurationException(
                String.format(
                        "La hoja %s tiene agujero y debe tener una segunda longitud",
                        leafNumber));
    }

    public static InvalidLeafConfigurationException secondLengthNotAllowedWithoutHole(
            String leafNumber) {
        return new InvalidLeafConfigurationException(
                String.format(
                        "La hoja %s no tiene agujero y no debe tener segunda longitud",
                        leafNumber));
    }
}
