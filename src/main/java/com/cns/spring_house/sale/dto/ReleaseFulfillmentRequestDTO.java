package com.cns.spring_house.sale.dto;

import java.util.UUID;

/**
 * Entrega de Control. `item` es la unidad fisica que realmente sale, por su
 * InventoryItem.id (el que codifica su codigo de barras). Null entrega la
 * unidad que el sistema reservo al enviar la comanda.
 */
public record ReleaseFulfillmentRequestDTO(

    UUID item

) {

}
