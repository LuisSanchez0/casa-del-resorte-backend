package com.cns.spring_house.transformation.handler;

import java.util.UUID;

import com.cns.spring_house.family.entity.FamilyCategory;
import com.cns.spring_house.inventory.entity.InventoryItem;
import com.cns.spring_house.transformation.entity.Transformation;

public interface TransformationHandler {
    
    FamilyCategory getType();

    void generate(
            Transformation transformation,
            InventoryItem inputItem,
            UUID ouputProduct);

}
