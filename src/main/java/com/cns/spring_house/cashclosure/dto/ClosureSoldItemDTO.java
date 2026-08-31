package com.cns.spring_house.cashclosure.dto;

import java.math.BigDecimal;
import java.util.UUID;

import com.cns.spring_house.family.entity.FamilyCategory;

/**
 * Un articulo vendido en el periodo.
 *
 * Responde la primera pregunta del cierre: que se vendio, cuanto y cuanto
 * genero. Es actividad FACTURADA, asi que una cambiaria emitida y aun sin
 * cobrar aporta sus articulos aqui; su dinero no entra en lo cobrado hasta que
 * se pague.
 *
 * {@code type} viaja para que la pantalla distinga mercaderia de servicios sin
 * que ninguna linea que aporta dinero quede fuera de la tabla.
 */
public record ClosureSoldItemDTO(

    /** Identidad estable por la que se agrupo. */
    UUID productId,
    String name,
    FamilyCategory type,
    long quantity,
    /** Suma del subtotal HISTORICO de las lineas, con su ajuste de precio. */
    BigDecimal billedAmount

) { }
