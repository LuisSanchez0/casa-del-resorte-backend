package com.cns.spring_house.dailylog.service;

import com.cns.spring_house.dailylog.dto.SecretaryDashboardResponseDTO;

/**
 * Agregaciones del dashboard de secretaria.
 *
 * Es una interfaz aparte de {@code DailyLogService} —igual que
 * {@code SaleDashboardService} lo es de {@code SaleService}— porque no comparte
 * nada con el CRUD de la bitacora: no escribe, no valida contra el catalogo y
 * su unidad de trabajo es un conteo, no un registro.
 */
public interface SecretaryDashboardService {

    /**
     * El panel completo para el taller y el empleado del token.
     *
     * @param days tamaño del periodo en dias, extremos incluidos. Un valor
     *             fuera de los admitidos cae al valor por defecto en lugar de
     *             fallar: un parametro raro en la URL no debe romper la
     *             pantalla de inicio del rol.
     */
    SecretaryDashboardResponseDTO getDashboard(Integer days);

}
