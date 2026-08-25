package com.cns.spring_house.dashboard.service;

import com.cns.spring_house.dashboard.dto.AdminDashboardOverviewResponseDTO;
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

    VehicleOccupancyResponseDTO getVehicleOccupancy(int days);

}
