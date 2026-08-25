package com.cns.spring_house.dashboard.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Fila del ranking de productos mas vendidos.
 *
 * Las dos cifras son HISTORICAS y exactas: {@code quantity} sale de
 * {@code sale_detail.quantity} y {@code generatedAmount} de la suma de
 * {@code sale_detail.sub_total}, snapshots escritos al facturar que nada vuelve
 * a modificar. Un descuento concedido en su dia sigue reflejado, y cambiar hoy
 * el precio de catalogo no reescribe el pasado.
 *
 * {@code generatedAmount} es MONTO GENERADO, no margen ni rentabilidad. El DTO
 * no expone —ni el servicio calcula— ningun costo: restarlo responderia una
 * pregunta que este panel no hace.
 */
public record ProductPerformanceDTO(

        UUID productId,
        String productName,
        long quantity,
        BigDecimal generatedAmount

) {

}
