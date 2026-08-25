package com.cns.spring_house.dailylog.dto;

import java.time.LocalDate;
import java.util.UUID;

/**
 * El taller NO es un filtro: el servicio lo impone desde el token. Aceptarlo
 * como parametro dejaba leer la bitacora de otro taller conociendo su UUID.
 */
public record SearchDailyLogRequestDTO(

        UUID sale,
        UUID employee,
        LocalDate minDate,
        LocalDate maxDate

) {
}
