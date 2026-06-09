package com.cns.spring_house.transformation.handler;

import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.cns.spring_house.family.entity.FamilyCategory;
import com.cns.spring_house.inventory.entity.InventoryItem;
import com.cns.spring_house.inventory.entity.InventoryItemStatus;
import com.cns.spring_house.transformation.entity.Transformation;
import com.cns.spring_house.transformation.entity.TransformationItem;
import com.cns.spring_house.transformation.entity.TransformationItemRole;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class ContainerTransformationHandler implements TransformationHandler {

    @Override
    public FamilyCategory getType() {
        return FamilyCategory.CONTAINER;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    @Override
    public void generate(
                Transformation transformation, 
                InventoryItem inputItem, 
                UUID ouputProduct) {
        TransformationItem item = processInputItem(inputItem);
        transformation.addItem(item);
    }

    private TransformationItem processInputItem(InventoryItem inputItem) {
        inputItem.getContainedItems().forEach(item -> {
            item.setStatus(InventoryItemStatus.AVAILABLE);
            item.getInventory().addStock(1);
        });
        inputItem.setStatus(InventoryItemStatus.CONSUMED);
        inputItem.getInventory().removeStock(1);
        return TransformationItem.builder()
                .item(inputItem)
                .role(TransformationItemRole.INPUT)
                .build();
    }

}
