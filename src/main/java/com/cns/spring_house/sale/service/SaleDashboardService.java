package com.cns.spring_house.sale.service;

import com.cns.spring_house.sale.dto.SaleDashboardResponseDTO;

public interface SaleDashboardService {

    /**
     * Estadisticas del dashboard de ventas del taller autenticado, en una sola
     * peticion. Ni el taller ni el empleado del ambito personal son parametros
     * de entrada: los resuelve el servicio desde el contexto de seguridad.
     *
     * @param days ventana de la tendencia solicitada por el cliente (7, 30 o
     *             90). Cualquier otro valor, incluido {@code null}, se
     *             normaliza al valor por defecto.
     */
    SaleDashboardResponseDTO getDashboard(Integer days);

}
