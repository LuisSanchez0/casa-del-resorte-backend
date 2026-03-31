package com.cns.spring_house.transformation.handler;

import com.cns.spring_house.inventory.entity.InventoryItem;
import com.cns.spring_house.transformation.dto.CreateTransformationRequestDTO;
import com.cns.spring_house.transformation.entity.Transformation;

import lombok.Getter;

@Getter
public class TransformationContext {

    private Transformation transformation;
    private InventoryItem inputItem;
    private Double newLength;

    public TransformationContext(
            Transformation transformation, 
            InventoryItem inputItem,
            CreateTransformationRequestDTO requestDTO) {
        this.transformation = transformation;
        this.inputItem = inputItem;
        this.newLength = requestDTO.newLength();
    }
    
}
