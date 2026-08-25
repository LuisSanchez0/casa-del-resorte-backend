package com.cns.spring_house.dailylog.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants.ComponentModel;

import com.cns.spring_house.dailylog.dto.DailyLogLineResponseDTO;
import com.cns.spring_house.dailylog.dto.DailyLogResponseDTO;
import com.cns.spring_house.dailylog.entity.DailyLog;
import com.cns.spring_house.dailylog.entity.DailyLogProduct;
import com.cns.spring_house.dailylog.entity.DailyLogService;

@Mapper(componentModel = ComponentModel.SPRING)
public interface DailyLogMapper {

    /**
     * `saleId` sale de la venta referenciada. Se declara explicito porque
     * MapStruct no aplana `sale` -> `saleId` por su cuenta, y sin esto la
     * respuesta salia siempre con la venta en null.
     */
    @Mapping(target = "saleId", source = "sale.id")
    DailyLogResponseDTO toResponse(DailyLog dailyLog);

    /**
     * Productos y servicios comparten forma de salida, pero son tipos distintos:
     * MapStruct necesita ver un metodo por cada uno para poder mapear las dos
     * listas del registro.
     */
    DailyLogLineResponseDTO toLine(DailyLogProduct product);

    DailyLogLineResponseDTO toLine(DailyLogService service);

}
