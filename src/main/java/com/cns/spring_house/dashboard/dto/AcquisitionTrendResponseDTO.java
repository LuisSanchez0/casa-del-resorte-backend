package com.cns.spring_house.dashboard.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.cns.spring_house.acquisition.entity.AcquisitionType;

/**
 * Respuesta de la serie de GASTO EN COMPRAS.
 *
 * Es DINERO QUE SALIO a proveedores, fechado por la adquisicion. No es costo de
 * lo vendido y no debe restarse de la venta del mismo periodo: lo que se compra
 * en agosto puede venderse en octubre, y esa resta daria un margen inventado.
 * El costo de lo vendido tiene su propia serie.
 *
 * {@code from} y {@code to} son los limites REALES ya recortados por el
 * servidor, misma convencion que la serie de ventas: pedir el mes en curso
 * devuelve hasta hoy, no hasta el 31.
 *
 * {@code acquisitionType} es el filtro que se aplico; null significa que la
 * serie trae nacional e importacion juntas.
 *
 * Los totales los suma el SERVIDOR sobre estos mismos puntos: el cliente no
 * vuelve a sumar importes para rotular la tarjeta.
 */
public record AcquisitionTrendResponseDTO(

        SalesGranularity granularity,
        AcquisitionType acquisitionType,
        LocalDate from,
        LocalDate to,
        BigDecimal totalAmount,
        BigDecimal totalNationalAmount,
        BigDecimal totalImportationAmount,
        long acquisitionCount,
        long unitsReceived,
        List<AcquisitionTrendPointDTO> points

) { }
