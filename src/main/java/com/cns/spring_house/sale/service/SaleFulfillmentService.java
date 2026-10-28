package com.cns.spring_house.sale.service;

import java.util.List;
import java.util.UUID;

import com.cns.spring_house.inventory.dto.InventoryItemResponseDTO;
import com.cns.spring_house.sale.dto.BlockFulfillmentRequestDTO;
import com.cns.spring_house.sale.dto.FulfillTransformationRequestDTO;
import com.cns.spring_house.sale.dto.FulfillmentSourceDTO;
import com.cns.spring_house.sale.dto.FulfillmentTaskResponseDTO;
import com.cns.spring_house.sale.dto.ReleaseFulfillmentRequestDTO;
import com.cns.spring_house.sale.entity.FulfillmentArea;
import com.cns.spring_house.sale.entity.FulfillmentStatus;

/**
 * Lo que Control y el Operador hacen con las solicitudes de la comanda.
 *
 * Ninguna operacion toca precio, identidad fiscal ni cobro: eso es del
 * vendedor.
 */
public interface SaleFulfillmentService {

    List<FulfillmentTaskResponseDTO> getQueue(
            FulfillmentArea area, List<FulfillmentStatus> statuses, Integer limit);

    /** Pagina acotada de la misma cola y del mismo taller autenticado. */
    List<FulfillmentTaskResponseDTO> getQueue(
            FulfillmentArea area, List<FulfillmentStatus> statuses, Integer limit, Integer page);

    FulfillmentTaskResponseDTO get(UUID id);

    /** Unidades que Control puede entregar: la reservada y las disponibles. */
    List<InventoryItemResponseDTO> getEligibleItems(UUID id);

    FulfillmentTaskResponseDTO release(UUID id, ReleaseFulfillmentRequestDTO requestDTO);

    FulfillmentTaskResponseDTO start(UUID id);

    FulfillmentTaskResponseDTO block(UUID id, BlockFulfillmentRequestDTO requestDTO);

    /**
     * Piezas de las que el Operador puede fabricar lo pedido. Con `query`, solo
     * las que coinciden con lo buscado (nombre, SKU, familia, modelo, marca,
     * caracteristicas o codigo de la unidad); buscar no amplia lo elegible.
     * Null o en blanco no filtra.
     */
    List<FulfillmentSourceDTO> getSources(UUID id, String query);

    FulfillmentTaskResponseDTO transform(UUID id, FulfillTransformationRequestDTO requestDTO);

    FulfillmentTaskResponseDTO completeService(UUID id);

}
