package com.cns.spring_house.transformation.handler;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.cns.spring_house.family.entity.Family;
import com.cns.spring_house.family.entity.FamilyCategory;
import com.cns.spring_house.inventory.entity.Inventory;
import com.cns.spring_house.inventory.entity.InventoryItem;
import com.cns.spring_house.inventory.entity.InventoryItemStatus;
import com.cns.spring_house.inventory.repository.InventoryRepository;
import com.cns.spring_house.model.entity.Model;
import com.cns.spring_house.product.entity.Product;
import com.cns.spring_house.product.repository.ProductRepository;
import com.cns.spring_house.shared.exception.EntityNotFoundException;
import com.cns.spring_house.shared.security.SecurityUtils;
import com.cns.spring_house.transformation.entity.Transformation;
import com.cns.spring_house.transformation.entity.TransformationItem;
import com.cns.spring_house.transformation.entity.TransformationItemRole;
import com.cns.spring_house.workshop.entity.Workshop;
import com.cns.spring_house.workshop.repository.WorkshopRepository;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class SpecialTransformationHandler implements TransformationHandler {
    
    private final ProductRepository productRepository;
    private final InventoryRepository inventoryRepository;
    private final WorkshopRepository workshopRepository;
    private final ObjectMapper objectMapper;

    @Override
    public FamilyCategory getType() {
        return FamilyCategory.SPECIAL;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    @Override
    public void generate(
            Transformation transformation,
            InventoryItem inputItem,
            UUID ouputProduct) {
        TransformationItem output = generateOutputItem(ouputProduct);
        transformation.addItem(output);
        generateRemainder(
                inputItem.getInventory().getProduct(),
                output.getItem().getInventory().getProduct())
                        .ifPresent(transformation::addItem);
        transformation.addItem(processInputItem(inputItem));
    }

    private TransformationItem processInputItem(InventoryItem inputItem) {
        inputItem.setStatus(InventoryItemStatus.CONSUMED);
        inputItem.getInventory().removeStock(1);
        return TransformationItem.builder()
                .item(inputItem)
                .role(TransformationItemRole.INPUT)
                .build();
    }

    private TransformationItem generateOutputItem(UUID outputProduct) {
        Product targetProduct = productRepository
                .findById(outputProduct)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el producto especificado"));
        InventoryItem item = findOrCreateItem(targetProduct);
        return TransformationItem.builder()
                .item(item)
                .role(TransformationItemRole.OUTPUT)
                .build();
    }

    private Optional<TransformationItem> generateRemainder(
            Product inputProduct, Product outputProduct) {
        boolean hasHole = Boolean.TRUE.equals(
                inputProduct.getAttributes().get("AGUJERO"));
        if (hasHole) {
            return Optional.empty();
        }
        Double inputLength = (Double) inputProduct
                .getAttributes().get("LARGO");
        Double outputLength = (Double) outputProduct
                .getAttributes().get("LARGO");
        if (inputLength == null || outputLength == null) {
            return Optional.empty();
        }
        double difference = (inputLength - outputLength);
        if (difference < inputLength / 2) {
            return Optional.empty();
        }
        Map<String, Object> attributes = new HashMap<>(inputProduct.getAttributes());
        attributes.put("LARGO", difference);
        Product productRemainder = findOrCreateProduct(
                inputProduct.getFamily(),
                inputProduct.getModel(),
                attributes);
        InventoryItem item = findOrCreateItem(productRemainder);
        return Optional.of(TransformationItem.builder()
                .role(TransformationItemRole.REMAINDER)
                .item(item)
                .build());
    }

    private Product findOrCreateProduct(
            Family family, Model model, Map<String, Object> attributes) {
        UUID modelId = model != null ? model.getId() : null;
        String attrs = objectMapper.writeValueAsString(attributes);
        return productRepository
                .findTargetProduct(family.getId(), modelId, attrs)
                .orElseGet(() -> productRepository.save(
                        Product.builder()
                                .name("PRODUCTO RESULTANTE")
                                .family(family)
                                .model(model)
                                .attributes(attributes)
                                .build()));
    }

    private InventoryItem findOrCreateItem(Product targetProduct) {
        Inventory inventory = inventoryRepository
                .findByProductIdAndWorkshopId(
                        targetProduct.getId(),
                        SecurityUtils.getWorkshopId())
                .orElseGet(() -> createInventory(targetProduct));
        InventoryItem item = InventoryItem.builder()
                .status(InventoryItemStatus.AVAILABLE)
                .build();
        inventory.addItem(item);
        inventory.addStock(1);
        return item;
    }

    private Inventory createInventory(Product targetProduct) {
        Workshop workshop = workshopRepository  
                .findById(SecurityUtils.getWorkshopId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el taller especificado"));
        Inventory newInventory = Inventory.builder()
                .product(targetProduct)
                .workshop(workshop)
                .price(BigDecimal.ZERO)
                .cost(BigDecimal.ZERO)
                .minStock(0)
                .build();
        return inventoryRepository.save(newInventory);
    }

}
