package com.cns.spring_house.shared.validator.pastornextyear;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

@Target({ ElementType.FIELD })
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = PastOrNextYearValidator.class)
public @interface PastOrNextYear {

    String message() default "Year must be less than or equal to the next year";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

}
