package com.cns.spring_house.dailylog.dto;

import com.cns.spring_house.shared.validator.notblank.NotBlankIfPresent;

/**
 * Lo unico editable de un registro es su nota general.
 *
 * Ni la venta ni las lineas se pueden cambiar: son el historico. Corregir un
 * registro equivocado es borrarlo y volverlo a levantar, no reescribir lo que
 * ya se declaro.
 */
public record UpdateDailyLogRequestDTO(

        @NotBlankIfPresent(message = "La descripcion no puede estar en blanco")
        String description

) {
}
