package com.cns.spring_house.validators.pastornextyear;

import java.time.LocalDate;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class PastOrNextYearValidator implements ConstraintValidator<PastOrNextYear, Integer> {

    @Override
    public boolean isValid(Integer value, ConstraintValidatorContext context) {
        if (value == null) return true;
        int nextYear = LocalDate.now().getYear() + 1;
        return value <= nextYear;
    }
    
}
