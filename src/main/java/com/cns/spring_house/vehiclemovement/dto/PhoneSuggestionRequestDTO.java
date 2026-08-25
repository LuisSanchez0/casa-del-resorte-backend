package com.cns.spring_house.vehiclemovement.dto;

import java.util.Locale;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Parametros de {@code GET /v1/vehicle-movements/phone-suggestion}.
 *
 * NO lleva taller y no puede llevarlo: lo resuelve el servicio desde el token,
 * igual que el resto del modulo. Sin esa regla, un parametro de consulta
 * permitiria pedir el telefono que otro taller registro para una placa.
 *
 * La placa se normaliza y se valida con el MISMO patron que el alta. Que sea
 * obligatorio y completo no es rigidez: sin el, el endpoint aceptaria prefijos
 * ("P", "P12") y se convertiria en un buscador de telefonos por tanteo.
 */
public record PhoneSuggestionRequestDTO(

        @NotBlank(message = "La placa es obligatoria")
        @Pattern(
                regexp = "^[A-Z]{1,2}\\d{3}[A-Z]{3}$",
                message = "La placa no tiene un formato valido. Formato esperado: P123ABC")
        String plate

) {

    /**
     * Misma normalizacion que {@link CreateVehicleMovementRequestDTO}: trim y
     * mayusculas con {@code Locale.ROOT}. Tiene que ser identica, porque la
     * sugerencia se busca comparando la forma canonica de la placa; si aqui se
     * canonizara distinto, la consulta no encontraria las bitacoras que el alta
     * habia guardado.
     */
    public PhoneSuggestionRequestDTO {
        plate = plate == null ? null : plate.trim().toUpperCase(Locale.ROOT);
    }

}
