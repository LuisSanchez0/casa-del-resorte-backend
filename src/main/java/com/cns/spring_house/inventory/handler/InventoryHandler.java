package com.cns.spring_house.inventory.handler;

import java.util.List;

import com.cns.spring_house.family.entity.FamilyCategory;
import com.cns.spring_house.inventory.dto.AddStockRequestDTO;
import com.cns.spring_house.inventory.entity.Inventory;
import com.cns.spring_house.inventory.entity.InventoryItem;

public interface InventoryHandler {

    FamilyCategory getType();

    /**
     * Registra una entrada de stock de esta categoria.
     *
     * Todo handler que INCREMENTE existencias tiene ademas que dejar el lote que
     * las respalda, a traves de {@code InventoryLotService}. No es opcional: el
     * modelo se sostiene sobre que
     * {@code inventory.stock = SUM(quantity_remaining)}, y una entrada sin lote
     * deja existencias que el FIFO no podra costear despues.
     *
     * {@code context} trae lo que decide el servidor —proveedor y empleado— ya
     * resuelto contra el taller del token.
     */
    List<InventoryItem> addStock(
            Inventory inventory,
            AddStockRequestDTO requestDTO,
            StockEntryContext context);

}
