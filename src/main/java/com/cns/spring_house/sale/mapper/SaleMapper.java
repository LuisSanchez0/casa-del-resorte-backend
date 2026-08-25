package com.cns.spring_house.sale.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants.ComponentModel;

import com.cns.spring_house.inventory.mapper.InventoryItemMapper;
import com.cns.spring_house.sale.dto.SaleDetailResponseDTO;
import com.cns.spring_house.sale.dto.SaleDetailWorkResultDTO;
import com.cns.spring_house.sale.dto.SaleResponseDTO;
import com.cns.spring_house.sale.entity.Sale;
import com.cns.spring_house.sale.entity.SaleDetail;
import com.cns.spring_house.workresult.dto.WorkResultSource;
import com.cns.spring_house.workresult.entity.WorkResult;

/**
 * Delega el mapeo de InventoryItem en InventoryItemMapper, igual que
 * TransformationMapper: sin `uses`, MapStruct generaba aqui su propio mapeo por
 * coincidencia de nombres y devolvia en null inventoryId, productId, productName
 * y containerId del item de cada detalle.
 */
@Mapper(componentModel = ComponentModel.SPRING, uses = InventoryItemMapper.class)
public interface SaleMapper {

    SaleResponseDTO toResponse(Sale sale);

    @Mapping(target = "workResult",
            expression = "java(toWorkResult(saleDetail.getWorkResult()))")
    SaleDetailResponseDTO toResponse(SaleDetail saleDetail);

    /**
     * El origen se resuelve con la MISMA regla que {@code WorkResultMapper}: hay
     * JobLog (servicio ejecutado) o hay item (transformacion con salida fisica).
     * Duplicar el criterio con otra fuente —por ejemplo el item del propio
     * detalle— dejaria dos verdades que pueden separarse.
     *
     * Leer {@code getJobLog()} materializa la fila del WorkResult, no la del
     * JobLog: la FK ya viaja en el propio registro. Es como mucho una consulta
     * por linea CON trabajo asociado, el mismo patron perezoso que el item del
     * detalle ya tenia.
     */
    default SaleDetailWorkResultDTO toWorkResult(WorkResult workResult) {
        if (workResult == null) {
            return null;
        }
        return new SaleDetailWorkResultDTO(
                workResult.getId(),
                workResult.getJobLog() != null
                        ? WorkResultSource.JOB
                        : WorkResultSource.TRANSFORMATION);
    }

}
