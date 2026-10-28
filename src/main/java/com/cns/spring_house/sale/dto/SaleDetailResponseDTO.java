package com.cns.spring_house.sale.dto;

import java.math.BigDecimal;
import java.util.UUID;

import com.cns.spring_house.inventory.dto.InventoryItemResponseDTO;
import com.cns.spring_house.product.dto.ProductResponseDTO;

public record SaleDetailResponseDTO(

    UUID id,
    /** Producto real: su nombre tecnico nunca se sustituye por el fiscal. */
    ProductResponseDTO product,
    InventoryItemResponseDTO item,
    Integer quantity,
    BigDecimal price,
    BigDecimal subTotal,
    /** Descripcion alterna para factura; null significa "usar product.name". */
    String invoiceDescription,
    /**
     * Trabajo ya ejecutado que respalda la linea, o null si la linea es la venta
     * corriente de una pieza o de un servicio de catalogo.
     *
     * Es la UNICA forma de saber que lo cobrado fue una transformacion: el
     * producto y el item de la linea son los mismos campos que llena cualquier
     * venta SPECIAL. Ver {@link SaleDetailWorkResultDTO}.
     */
    SaleDetailWorkResultDTO workResult,
    /** Indicaciones del vendedor para Control u Operador; null si no hay. */
    String instructions,
    /**
     * Si la pieza pedida lleva colochos, tal como lo decidio el vendedor; null
     * si no se pregunto (linea anterior a la regla o familia sin colochos).
     */
    Boolean requestedCurls,
    /** Cuantos pidio el vendedor; null si no los fijo o la pieza no lleva. */
    Integer requestedCurlCount,
    /**
     * Ruta y estado de la linea en el taller. Null en una linea directa, que
     * salio del inventario al agregarla o respalda un trabajo ya hecho.
     */
    SaleDetailFulfillmentDTO fulfillment

) {

}
