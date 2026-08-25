package com.cns.spring_house.job.repository.projection;

import java.util.UUID;

/**
 * Fila de un ranking de empleados por trabajo EJECUTADO.
 *
 * Vive en el modulo de servicios porque {@code JobLog} es su primer consumidor,
 * pero la forma —quien y cuantos— es la misma que necesita el ranking de
 * transformaciones, asi que {@code TransformationRepository} la reutiliza en
 * lugar de declarar una proyeccion gemela.
 *
 * El nombre llega partido en dos columnas y no concatenado en SQL: componerlo
 * es presentacion, y hacerlo en la sentencia impediria que la interfaz
 * decidiera como abreviar un nombre largo.
 */
public interface EmployeePerformanceProjection {

    UUID getEmployeeId();

    String getFirstName();

    String getLastName();

    long getTotal();

}
