package com.cns.spring_house.workresult.mapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants.ComponentModel;

import com.cns.spring_house.product.entity.Product;
import com.cns.spring_house.workresult.dto.WorkResultResponseDTO;
import com.cns.spring_house.workresult.dto.WorkResultSource;
import com.cns.spring_house.workresult.entity.WorkResult;

@Mapper(componentModel = ComponentModel.SPRING)
public interface WorkResultMapper {

    /**
     * Sin precio sugerido: lo usan los caminos que no necesitan la referencia
     * (por ejemplo un JOB, que nunca la tiene).
     */
    @Mapping(target = "suggestedPrice", ignore = true)
    @Mapping(target = "jobLogId", source = "jobLog.id")
    @Mapping(target = "itemId", source = "item.id")
    @Mapping(target = "source",
            expression = "java(resolveSource(workResult))")
    @Mapping(target = "performedDate",
            expression = "java(resolvePerformedDate(workResult))")
    @Mapping(target = "productId",
            expression = "java(resolveProduct(workResult).getId())")
    @Mapping(target = "productName",
            expression = "java(resolveProduct(workResult).getName())")
    WorkResultResponseDTO toResponse(WorkResult workResult);

    /**
     * El precio sugerido llega ya resuelto desde el servicio, que lo pide para
     * toda la lista en una sola consulta. El mapper no consulta nada.
     */
    @Mapping(target = "suggestedPrice", source = "suggestedPrice")
    @Mapping(target = "jobLogId", source = "workResult.jobLog.id")
    @Mapping(target = "itemId", source = "workResult.item.id")
    @Mapping(target = "source", expression = "java(resolveSource(workResult))")
    @Mapping(target = "performedDate",
            expression = "java(resolvePerformedDate(workResult))")
    @Mapping(target = "id", source = "workResult.id")
    @Mapping(target = "status", source = "workResult.status")
    @Mapping(target = "productId",
            expression = "java(resolveProduct(workResult).getId())")
    @Mapping(target = "productName",
            expression = "java(resolveProduct(workResult).getName())")
    WorkResultResponseDTO toResponse(WorkResult workResult, BigDecimal suggestedPrice);

    default WorkResultSource resolveSource(WorkResult workResult) {
        return workResult.getJobLog() != null
                ? WorkResultSource.JOB
                : WorkResultSource.TRANSFORMATION;
    }

    default LocalDateTime resolvePerformedDate(WorkResult workResult) {
        LocalDateTime sourceDate = workResult.getJobLog() != null
                ? workResult.getJobLog().getPerformedDate()
                : workResult.getItem().getCreatedAt();
        return sourceDate != null ? sourceDate : workResult.getCreatedAt();
    }

    default Product resolveProduct(WorkResult workResult) {
        return workResult.getJobLog() != null
                ? workResult.getJobLog().getJob().getProduct()
                : workResult.getItem().getInventory().getProduct();
    }

}
