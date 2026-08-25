package com.cns.spring_house.workresult.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import com.cns.spring_house.workresult.entity.WorkResultStatus;

public record WorkResultResponseDTO(

    UUID id,
    WorkResultStatus status,
    WorkResultSource source,
    UUID productId,
    String productName,
    UUID jobLogId,
    UUID itemId,
    LocalDateTime performedDate,
    /**
     * Precio SUGERIDO para vender este trabajo, solo informativo.
     *
     * Solo aplica a TRANSFORMATION y sale del inventario del producto de ENTRADA
     * que se transformo, no del de salida: la transformacion crea el inventario
     * de salida con `price = 0` porque todavia nadie le ha puesto precio, asi que
     * el unico precio real de referencia es el de la pieza de origen.
     *
     * Es null cuando no hay una referencia utilizable: en JOB, cuando no se
     * encuentra la entrada, o cuando el propio origen tiene precio 0 (el mismo
     * centinela de "sin configurar" que usa la transformacion). Nunca se
     * devuelve 0 como si fuera un precio.
     *
     * No se persiste en ningun sitio: quien manda al vender es
     * `SaleDetail.price`, y el minimo lo sigue imponiendo el costo del producto
     * de salida.
     */
    BigDecimal suggestedPrice

) {

}
