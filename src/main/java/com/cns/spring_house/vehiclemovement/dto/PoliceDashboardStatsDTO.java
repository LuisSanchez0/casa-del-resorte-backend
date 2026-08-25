package com.cns.spring_house.vehiclemovement.dto;

import java.util.List;

/**
 * Bloque de estadisticas de un ambito (taller completo o empleado autenticado).
 * La estructura es identica en los dos para que el cliente pinte las mismas
 * graficas cambiando unicamente el conjunto de datos.
 */
public record PoliceDashboardStatsDTO(

        DashboardKpisDTO kpis,
        List<EntriesByDayDTO> entriesByDay,
        List<TopPlateDTO> topPlates

) {

}
