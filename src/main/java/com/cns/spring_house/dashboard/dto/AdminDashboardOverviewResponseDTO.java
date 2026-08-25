package com.cns.spring_house.dashboard.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Resumen del panel administrativo: los KPI estables y los cinco rankings, en
 * UNA sola peticion.
 *
 * Se separa de las series de tiempo a proposito. Los rankings y los KPI no
 * dependen del periodo que el administrador tenga seleccionado, asi que
 * meterlos en el mismo endpoint obligaria a recalcularlos cada vez que alguien
 * cambia la granularidad de una grafica. Al reves —un endpoint por tarjeta—
 * convertiria la carga inicial en una decena de peticiones. Dos endpoints de
 * lectura mas uno de ocupacion es el punto donde ninguna de las dos cosas pasa.
 *
 * Todo llega acotado al taller del token; el cliente no envia —ni puede
 * enviar— identificador de taller alguno.
 *
 * {@code totalGeneratedSales} es el MONTO GENERADO por las ventas completadas
 * del taller, no el dinero cobrado: una factura cambiaria emitida y pendiente de
 * cobro suma aqui, porque la venta ya se genero. El nombre lo dice para que
 * nadie lo lea como caja; la caja la mide el cierre diario, con su propia regla.
 *
 * {@code businessDate} es la fecha operativa del servidor, la misma referencia
 * que usan el cierre de caja y el dashboard de secretaria. El navegador no
 * deduce el dia por su cuenta.
 *
 * {@code firstSaleDate} es null en un taller que aun no ha vendido nada; sirve
 * para que los selectores de periodo del cliente se limiten a la historia que de
 * verdad existe.
 */
public record AdminDashboardOverviewResponseDTO(

        LocalDate businessDate,
        LocalDate firstSaleDate,

        BigDecimal totalGeneratedSales,
        long completedSalesCount,
        long currentVehicles,
        long workerCount,

        List<ProductPerformanceDTO> topSellingProducts,
        List<ProductSalesDTO> leastSellingProducts,
        List<EmployeePerformanceDTO> topServiceEmployees,
        List<EmployeePerformanceDTO> topTransformationEmployees,
        List<CustomerPerformanceDTO> topCustomers

) {

}
