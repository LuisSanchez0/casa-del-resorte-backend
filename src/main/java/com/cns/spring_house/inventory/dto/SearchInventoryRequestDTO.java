package com.cns.spring_house.inventory.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Filtros de busqueda de inventario.
 *
 * El TALLER NO esta aqui, y su ausencia es la regla de seguridad: el alcance lo
 * resuelve el servidor desde el token (`SecurityUtils.getWorkshopId()`), igual
 * que hacen ventas, servicios y trabajos ejecutados.
 *
 * Antes este record declaraba un `workshop` que el cliente rellenaba, y esa era
 * la unica acotacion que existia: omitirlo devolvia el inventario de TODOS los
 * talleres, y mandar el UUID de otro devolvia el suyo. Un parametro de consulta
 * no puede decidir que datos ve quien pregunta; quitar el campo es lo que hace
 * imposible volver a intentarlo, porque ya no hay nada que enviar. Un
 * `?workshop=...` que llegue hoy es un parametro desconocido y Spring lo ignora
 * al enlazar este record.
 */
public record SearchInventoryRequestDTO(

        UUID product,
        BigDecimal minCost,
        BigDecimal maxCost,
        BigDecimal minPrice,
        BigDecimal maxPrice,
        Boolean isLowStock

) { }
