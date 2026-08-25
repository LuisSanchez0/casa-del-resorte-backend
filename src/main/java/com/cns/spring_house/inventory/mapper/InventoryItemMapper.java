package com.cns.spring_house.inventory.mapper;

import java.util.List;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.cns.spring_house.inventory.dto.InventoryItemResponseDTO;
import com.cns.spring_house.inventory.entity.InventoryItem;

@Mapper(componentModel = "spring")
public interface InventoryItemMapper {

    /**
     * `attributes` sale del MISMO `inventory.product` que ya se recorre para el
     * nombre, así que no añade ninguna consulta: son las características
     * persistidas de la pieza, no un cálculo a partir de la petición que la
     * creó.
     */
    @Mapping(
            target = "inventoryId",
            source = "inventory.id")
    @Mapping(
            target = "productId",
            source = "inventory.product.id")
    @Mapping(
            target = "productName",
            source = "inventory.product.name")
    @Mapping(
            target = "attributes",
            source = "inventory.product.attributes")
    @Mapping(
            target = "containerId",
            source = "container.id")
    InventoryItemResponseDTO toResponse(
            InventoryItem inventoryItem);

    List<InventoryItemResponseDTO> toResponseList(
            List<InventoryItem> inventoryItems);
}