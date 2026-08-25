package com.cns.spring_house.dashboard.dto;

import java.util.UUID;

/**
 * Fila del ranking de productos menos vendidos.
 *
 * No lleva importe ni margen a proposito: la pregunta que responde es de
 * rotacion —que casi no sale de la bodega—, y un producto con cero ventas no
 * tiene ninguna cifra de dinero que ensenar. {@code quantity} en cero es el
 * dato, no la ausencia de dato.
 */
public record ProductSalesDTO(

        UUID productId,
        String productName,
        long quantity

) {

}
