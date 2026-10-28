package com.cns.spring_house.sale.mapper;

import java.util.UUID;

import org.hibernate.proxy.HibernateProxy;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants.ComponentModel;

import com.cns.spring_house.inventory.mapper.InventoryItemMapper;
import com.cns.spring_house.sale.dto.SaleDetailFulfillmentDTO;
import com.cns.spring_house.sale.dto.SaleDetailResponseDTO;
import com.cns.spring_house.sale.dto.SaleDetailWorkResultDTO;
import com.cns.spring_house.sale.dto.SaleResponseDTO;
import com.cns.spring_house.job.entity.JobLog;
import com.cns.spring_house.sale.entity.Fulfillment;
import com.cns.spring_house.sale.entity.Sale;
import com.cns.spring_house.sale.entity.SaleDetail;
import com.cns.spring_house.transformation.entity.Transformation;
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
    @Mapping(target = "fulfillment",
            expression = "java(toFulfillment(saleDetail.getFulfillment()))")
    SaleDetailResponseDTO toResponse(SaleDetail saleDetail);

    /**
     * Solo IDENTIFICADORES de la transformacion y del servicio ejecutado: se
     * leen del proxy sin inicializarlo, asi que publicar el cumplimiento de
     * cada linea no cuesta ninguna consulta. La venta ya carga sus lineas en
     * un solo viaje y esto no puede devolverle el N+1 que su grafo evita.
     */
    default SaleDetailFulfillmentDTO toFulfillment(Fulfillment fulfillment) {
        if (fulfillment == null || fulfillment.getStatus() == null) {
            return null;
        }
        return new SaleDetailFulfillmentDTO(
                fulfillment.getType(),
                fulfillment.area(),
                fulfillment.getStatus(),
                fulfillment.getNote(),
                fulfillment.getBlockReason(),
                fulfillment.getRequiredPrice(),
                fulfillment.getDispatchedAt(),
                fulfillment.getStartedAt(),
                fulfillment.getCompletedAt(),
                transformationId(fulfillment.getTransformation()),
                jobLogId(fulfillment.getJobLog()));
    }

    private static UUID transformationId(Transformation transformation) {
        if (transformation instanceof HibernateProxy proxy) {
            return (UUID) proxy.getHibernateLazyInitializer().getInternalIdentifier();
        }
        return transformation == null ? null : transformation.getId();
    }

    private static UUID jobLogId(JobLog jobLog) {
        if (jobLog instanceof HibernateProxy proxy) {
            return (UUID) proxy.getHibernateLazyInitializer().getInternalIdentifier();
        }
        return jobLog == null ? null : jobLog.getId();
    }

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
