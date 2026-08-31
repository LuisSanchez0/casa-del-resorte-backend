package com.cns.spring_house.dashboard.service;

import com.cns.spring_house.acquisition.entity.AcquisitionType;
import com.cns.spring_house.dashboard.dto.AcquisitionTrendResponseDTO;
import com.cns.spring_house.dashboard.dto.AdminDashboardOverviewResponseDTO;
import com.cns.spring_house.dashboard.dto.SalesCostProfitTrendResponseDTO;
import com.cns.spring_house.dashboard.dto.TopSuppliersResponseDTO;
import com.cns.spring_house.dashboard.dto.SalesGranularity;
import com.cns.spring_house.dashboard.dto.SalesTrendResponseDTO;
import com.cns.spring_house.dashboard.dto.VehicleOccupancyResponseDTO;

public interface AdminDashboardService {

    AdminDashboardOverviewResponseDTO getOverview();

    /**
     * @param period intervalo pedido: {@code YYYY-MM} para DAY, {@code YYYY}
     *               para MONTH, ignorado en YEAR. Null toma el actual.
     */
    SalesTrendResponseDTO getSalesTrend(SalesGranularity granularity, String period);

    /**
     * Serie de GASTO EN COMPRAS, fechada por la adquisicion.
     *
     * @param acquisitionType null para nacional e importacion juntas.
     */
    AcquisitionTrendResponseDTO getAcquisitionTrend(
            SalesGranularity granularity, String period, AcquisitionType acquisitionType);

    /** Serie de COSTO DE LO VENDIDO y GANANCIA BRUTA, fechada por la venta. */
    SalesCostProfitTrendResponseDTO getSalesCostProfitTrend(
            SalesGranularity granularity, String period);

    /** Ranking de proveedores del periodo seleccionado. */
    TopSuppliersResponseDTO getTopSuppliers(SalesGranularity granularity, String period);

    VehicleOccupancyResponseDTO getVehicleOccupancy(int days);

}
