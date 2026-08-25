package com.cns.spring_house.cashclosure.service;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.cns.spring_house.cashclosure.dto.CashClosureCollectionsDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureInvoicingDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureReceivablesDTO;

/**
 * Las cifras de UN dia de UN taller calculadas contra `sale`, sin arqueo.
 *
 * Es el resultado unico de {@link CashClosureCalculator} y la fuente de las
 * tres rutas que necesitan datos frescos: la previsualizacion dinamica, la
 * previsualizacion final y el cierre definitivo. Que las tres pasen por el
 * mismo objeto es lo que garantiza que lo que se ve antes de cerrar y lo que se
 * persiste al cerrar se calculen con las mismas reglas.
 *
 * En cuanto el cierre se confirma, esto deja de importar: el historico se lee
 * del snapshot.
 */
public record DailyCashFigures(

    LocalDate businessDate,
    CashClosureInvoicingDTO invoicing,
    CashClosureCollectionsDTO collections,
    CashClosureReceivablesDTO receivables

) {

    /**
     * Efectivo que deberia haber en el cajon: el fondo inicial mas lo cobrado
     * EN EFECTIVO durante el dia.
     *
     * Tarjeta y transferencia no entran: ese dinero fue al banco, no al cajon,
     * y sumarlo convertiria cualquier caja correcta en un faltante del tamano
     * de lo cobrado por esas vias.
     */
    public BigDecimal expectedCash(BigDecimal openingCash) {
        return openingCash.add(collections.cashAmount());
    }

    /** Positiva cuando sobra efectivo, negativa cuando falta. */
    public BigDecimal difference(BigDecimal openingCash, BigDecimal countedCash) {
        return countedCash.subtract(expectedCash(openingCash));
    }

}
