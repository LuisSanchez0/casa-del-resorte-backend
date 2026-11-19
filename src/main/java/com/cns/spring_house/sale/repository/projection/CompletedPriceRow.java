package com.cns.spring_house.sale.repository.projection;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Un precio cobrado de verdad: el de una linea de una venta completada. */
public record CompletedPriceRow(BigDecimal price, LocalDateTime completedAt) {
}
