package com.cns.spring_house.inventory.dto;

import java.util.List;
import java.util.UUID;

import com.cns.spring_house.shared.constants.ColumnLimits;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record AddStockRequestDTO(

        @Positive(
                message = "Las unidades a agregar deben ser mayores que cero")
        @Max(value = ColumnLimits.SMALLINT_MAX,
                message = "El valor maximo permitido es {value}")
        Integer units,

        @Size(
                min = 1,
                message = "Debe especificarse al menos un producto contenido")
        List<UUID> containedItems

) {
}