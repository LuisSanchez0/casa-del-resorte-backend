package com.cns.spring_house.dailylog.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Estado del cierre de caja de la fecha de negocio, leido de
 * {@code cash_closure} y no recalculado: existe la fila o no existe.
 *
 * Que la caja siga abierta NO es un error ni un caso excepcional; es el estado
 * normal de cualquier momento anterior al cierre del dia. Por eso se responde
 * como un campo booleano dentro de un 200 y no como el 404 que da
 * {@code GET /v1/cash-closures/by-date} para una fecha sin cierre.
 *
 * {@code closureId} y {@code closedAt} son null mientras {@code completed} sea
 * false.
 */
public record SecretaryDashboardClosureDTO(

        boolean completed,
        UUID closureId,
        LocalDateTime closedAt

) {

    public static SecretaryDashboardClosureDTO open() {
        return new SecretaryDashboardClosureDTO(false, null, null);
    }

}
