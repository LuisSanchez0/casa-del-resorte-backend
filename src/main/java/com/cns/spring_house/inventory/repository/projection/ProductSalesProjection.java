package com.cns.spring_house.inventory.repository.projection;

import java.util.UUID;

/**
 * Fila del ranking de productos MENOS vendidos.
 *
 * Vive en el modulo de inventario, y no en el de ventas, porque su origen es el
 * inventario del taller: la pregunta que responde es "de lo que este taller
 * tiene en catalogo, que casi no sale", asi que las ventas entran por LEFT JOIN
 * y un producto sin ninguna venta vale cero en lugar de desaparecer. Consultarlo
 * desde {@code sale_detail} solo podria devolver lo que YA se vendio, que es
 * justo lo contrario de lo que se busca.
 */
public interface ProductSalesProjection {

    UUID getProductId();

    String getProductName();

    long getQuantity();

}
