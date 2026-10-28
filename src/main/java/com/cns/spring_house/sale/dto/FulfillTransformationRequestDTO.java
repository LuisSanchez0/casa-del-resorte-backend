package com.cns.spring_house.sale.dto;

import java.util.UUID;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * El Operador fabrica la pieza pedida a partir de una unidad que el elige.
 *
 * El RESULTADO no viaja aqui: es el producto que pidio el vendedor y lo pone el
 * servidor. El operador decide de que pieza sale y, si la venta dijo que lleva
 * colochos sin fijar cuantos (o no lo dijo), PUEDE registrarlos.
 *
 * Los colochos del sobrante tampoco viajan: en una comanda el sobrante vuelve
 * al inventario sin colochos, y lo decide el servidor.
 */
public record FulfillTransformationRequestDTO(

    @NotNull(message = "Elija la pieza de origen")
    UUID inputItem,
    /**
     * Opcional, y solo si la pieza pedida no fija su cantidad de colochos: el
     * operador puede registrar los que lleva la pieza resultante. Null la deja
     * sin colochos registrados, como la pidio la venta; nunca se lee como 0.
     */
    @Min(value = 0, message = "La cantidad de colochos debe ser mayor o igual a cero")
    Integer outputCurlCount

) {

}
