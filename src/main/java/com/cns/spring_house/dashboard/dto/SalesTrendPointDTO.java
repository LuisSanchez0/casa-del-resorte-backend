package com.cns.spring_house.dashboard.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Un punto de la serie de ventas generadas.
 *
 * {@code bucket} es el PRIMER dia del intervalo en las tres granularidades
 * —{@code 2026-08-15}, {@code 2026-08-01}, {@code 2026-01-01}—, de modo que el
 * cliente rotula el eje segun la granularidad que pidio sin tener que analizar
 * un formato distinto por caso.
 *
 * {@code generatedAmount} es {@code BigDecimal} de extremo a extremo: la suma se
 * hace en PostgreSQL sobre {@code NUMERIC(10,2)} y llega sin pasar por coma
 * flotante.
 */
public record SalesTrendPointDTO(

        LocalDate bucket,
        long salesCount,
        BigDecimal generatedAmount

) {

}
