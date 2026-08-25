package com.cns.spring_house.inventory.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.cns.spring_house.inventory.entity.InventoryItemStatus;

public record InventoryItemResponseDTO(

        UUID id,
        UUID inventoryId,
        UUID productId,
        String productName,
        /**
         * Características persistidas del producto al que pertenece la unidad,
         * tal y como estan guardadas en su JSONB. Son la fuente de verdad de lo
         * que la pieza ES: el historial las muestra sin recalcular nada a partir
         * de la peticion que la creo.
         *
         * Sale del mismo `inventory.product` que ya se recorre para `productName`,
         * asi que no anade ninguna consulta. Puede ser nulo: un producto puede no
         * tener ninguna caracteristica guardada.
         */
        Map<String, Object> attributes,
        UUID containerId,
        InventoryItemStatus status,
        List<InventoryItemResponseDTO> containedItems

) {
}