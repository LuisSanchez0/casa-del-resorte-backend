package com.cns.spring_house.inventory.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.UUID;

import com.cns.spring_house.product.dto.ProductResponseDTO;
import com.cns.spring_house.workshop.dto.WorkshopResponseDTO;

public record InventoryResponseDTO(

        UUID id,
        WorkshopResponseDTO workshop,
        ProductResponseDTO product,
        BigDecimal price,
        BigDecimal cost,
        Integer stock,
        Integer minStock,
        /**
         * Unidades fisicas de este inventario que todavia pueden operarse: las
         * disponibles, mas las maletas a medio desarmar que aun guardan
         * componentes. Es el mismo predicado con el que el servidor decide si
         * una unidad puede entrar en una transformacion.
         *
         * NO es `stock` con otro nombre. `stock` cuenta existencia vendible y se
         * mueve con sus propias reglas; esto cuenta unidades sobre las que aun
         * se puede actuar. Un contenedor a medio desarmar suma aqui y un
         * inventario con todas sus unidades consumidas suma cero aunque conserve
         * su fila.
         */
        Integer processableUnits,
        @JsonInclude(JsonInclude.Include.NON_NULL) InventoryMutationDetailsDTO mutation

) {
    public InventoryResponseDTO(UUID id, WorkshopResponseDTO workshop,
            ProductResponseDTO product, BigDecimal price, BigDecimal cost,
            Integer stock, Integer minStock, Integer processableUnits) {
        this(id, workshop, product, price, cost, stock, minStock, processableUnits, null);
    }

    public InventoryResponseDTO withMutation(InventoryMutationDetailsDTO details) {
        return new InventoryResponseDTO(id, workshop, product, price, cost,
                stock, minStock, processableUnits, details);
    }
}
