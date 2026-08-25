package com.cns.spring_house.vehiclemovement.service;

import com.cns.spring_house.vehiclemovement.dto.PoliceDashboardResponseDTO;

public interface PoliceDashboardService {

    /**
     * Estadisticas del dashboard policial del taller autenticado. No recibe
     * parametros a proposito: ni el taller ni el empleado pueden llegar desde el
     * cliente.
     */
    PoliceDashboardResponseDTO getDashboard();

}
