package com.cns.spring_house.inventory.handler;

import java.util.List;
import java.util.ArrayList;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.cns.spring_house.acquisition.service.InventoryLotService;
import com.cns.spring_house.family.entity.FamilyCategory;
import com.cns.spring_house.inventory.dto.AddStockRequestDTO;
import com.cns.spring_house.inventory.entity.Inventory;
import com.cns.spring_house.inventory.entity.InventoryItem;
import com.cns.spring_house.inventory.entity.InventoryItemStatus;
import com.cns.spring_house.shared.exception.ValidationException;

import lombok.RequiredArgsConstructor;

/**
 * Producto con unidades fisicas identificables. Su entrada tambien es una
 * COMPRA: se reciben N unidades de un proveedor y se crea una fila fisica por
 * cada una.
 *
 * El lote es UNO para las N unidades recibidas, no uno por unidad. La unidad
 * fisica responde "cual es esta pieza"; el lote responde "cuanto costo y a quien
 * se le compro", y esas dos preguntas no tienen por que compartir granularidad.
 *
 * De ahi que el FIFO consuma por CANTIDAD aunque la venta elija una pieza
 * concreta: el flujo fisico lo decide quien despacha, pero el flujo de COSTO es
 * primero-en-entrar-primero-en-salir, que es la convencion contable habitual y
 * no exige que la pieza vendida sea literalmente la mas antigua.
 */
@Component
@RequiredArgsConstructor
public class SpecialInventoryHandler implements InventoryHandler {

    private final InventoryLotService lotService;

    @Override
    public FamilyCategory getType() {
        return FamilyCategory.SPECIAL;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    @Override
    public List<InventoryItem> addStock(
            Inventory inventory,
            AddStockRequestDTO requestDTO,
            StockEntryContext context) {

        if (requestDTO.units() == null) {
            throw new ValidationException(
                    "Las unidades a agregar son necesarias");
        }

        lotService.registerPurchase(
                inventory,
                context.supplier(),
                requestDTO.acquisitionType(),
                requestDTO.units(),
                requestDTO.unitCost(),
                context.employee());

        List<InventoryItem> createdItems = new ArrayList<>();
        for (int i = 0; i < requestDTO.units(); i++) {
            InventoryItem item = InventoryItem.builder()
                    .status(InventoryItemStatus.AVAILABLE)
                    .build();
            inventory.addItem(item);
            createdItems.add(item);
        }
        inventory.addStock(requestDTO.units());
        return List.copyOf(createdItems);
    }

}
