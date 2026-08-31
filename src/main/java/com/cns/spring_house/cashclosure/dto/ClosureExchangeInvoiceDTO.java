package com.cns.spring_house.cashclosure.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import com.cns.spring_house.sale.entity.PaymentMethod;

/**
 * Una factura cambiaria dentro del cierre.
 *
 * {@code collected} es el estado EN EL PERIODO que describe el cierre, no el de
 * hoy: en un snapshot confirmado se lee de la copia guardada, de modo que cobrar
 * manana un documento de hoy no reescribe lo que hoy dijo.
 *
 * {@code documentCode} es el codigo corto impreso en el papel. El UUID viaja
 * aparte, en {@code saleId}, solo para poder abrir el documento; no se muestra.
 */
public record ClosureExchangeInvoiceDTO(

    UUID saleId,
    String documentCode,
    /** Null en una venta de mostrador sin cliente asociado. */
    String customerName,
    LocalDateTime issuedAt,
    BigDecimal amount,
    boolean collected,
    /** Null mientras siguiera pendiente. */
    LocalDateTime paidAt,
    /** Null mientras siguiera pendiente. */
    PaymentMethod paymentMethod

) { }
