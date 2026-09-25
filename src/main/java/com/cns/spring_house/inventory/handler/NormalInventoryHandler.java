package com.cns.spring_house.inventory.handler;

import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.cns.spring_house.acquisition.service.InventoryLotService;
import com.cns.spring_house.family.entity.FamilyCategory;
import com.cns.spring_house.inventory.dto.AddStockRequestDTO;
import com.cns.spring_house.inventory.entity.Inventory;
import com.cns.spring_house.inventory.entity.InventoryItem;
import com.cns.spring_house.shared.exception.ValidationException;

import lombok.RequiredArgsConstructor;

/**
 * Producto que se cuenta por cantidad. Su entrada es siempre una COMPRA.
 */
@Component
@RequiredArgsConstructor
public class NormalInventoryHandler implements InventoryHandler {

    private final InventoryLotService lotService;

    @Override
    public FamilyCategory getType() {
        return FamilyCategory.NORMAL;
    }

    /**
     * El lote y las existencias se escriben en la MISMA transaccion, de modo que
     * no puede quedar stock sin su respaldo de costo ni un lote sin stock. Es lo
     * que hace atomica la entrada de inventario: antes eran dos ideas separadas
     * y solo una quedaba registrada.
     */
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

        inventory.addStock(requestDTO.units());
        return List.of();
    }

}
