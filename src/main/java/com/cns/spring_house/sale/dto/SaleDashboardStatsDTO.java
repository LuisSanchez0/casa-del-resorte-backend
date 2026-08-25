package com.cns.spring_house.sale.dto;

import java.util.List;

/**
 * Bloque de estadisticas de ventas de UN ambito (taller completo o empleado
 * autenticado). Misma estructura en los dos para que el cliente pinte las
 * mismas graficas cambiando solo el conjunto de datos.
 */
public record SaleDashboardStatsDTO(

    SaleDashboardKpisDTO kpis,
    List<SaleTrendPointDTO> trend,
    List<TopProductDTO> topProductsToday,
    List<PaymentMethodStatDTO> paymentMethodsToday

) {

}
