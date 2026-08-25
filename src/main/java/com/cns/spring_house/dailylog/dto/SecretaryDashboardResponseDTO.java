package com.cns.spring_house.dailylog.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Respuesta completa de {@code GET /v1/daily-logs/dashboard}: hoy, el periodo y
 * la serie diaria en UNA sola peticion.
 *
 * Todo viene acotado al taller del token, y el ambito personal se resuelve en
 * el servidor a partir del empleado autenticado: el cliente no envia —ni puede
 * enviar— identificadores de taller o de empleado.
 *
 * {@code businessDate} es la fecha operativa del servidor, la MISMA que usa el
 * cierre de caja ({@code CashClosureServiceImpl.businessDate()}, reloj anclado
 * a {@code TimeConfig.BUSINESS_ZONE}). El navegador no deduce el dia por su
 * cuenta: si lo hiciera, una secretaria con la zona horaria del sistema mal
 * configurada veria un "hoy" distinto del que cierra la caja.
 *
 * {@code personalScopeAvailable} es false cuando la cuenta autenticada no tiene
 * empleado asociado; entonces las cifras propias llegan en cero y la interfaz
 * lo dice, en vez de enseñar ceros como si fueran una jornada sin trabajo.
 */
public record SecretaryDashboardResponseDTO(

        LocalDate businessDate,
        boolean personalScopeAvailable,
        SecretaryDashboardTodayDTO today,
        SecretaryDashboardPeriodDTO period,
        List<SecretaryActivityPointDTO> series

) {

}
