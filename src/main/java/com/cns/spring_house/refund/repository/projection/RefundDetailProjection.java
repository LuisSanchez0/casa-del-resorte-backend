package com.cns.spring_house.refund.repository.projection;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/** Una devolucion del periodo, con lo que el cierre necesita mostrar de ella. */
public interface RefundDetailProjection {

    UUID getRefundId();

    UUID getSaleId();

    BigDecimal getAmount();

    LocalDateTime getEffectiveAt();

    String getPaymentMethod();

    /**
     * Nombre ya compuesto del empleado. Se resuelve en SQL para no traer la
     * entidad entera de cada responsable ni provocar un SELECT por fila.
     */
    String getPerformedByName();

    String getReason();

}
