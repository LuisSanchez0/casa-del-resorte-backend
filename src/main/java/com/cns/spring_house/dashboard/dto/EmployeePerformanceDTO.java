package com.cns.spring_house.dashboard.dto;

import java.util.UUID;

/**
 * Fila de los rankings de empleados por trabajo ejecutado.
 *
 * El nombre viaja ya compuesto porque las dos columnas de origen no significan
 * nada por separado para quien lee el panel, y el id acompana para que la
 * interfaz tenga una identidad estable con la que hacer {@code track} sin
 * depender de un nombre que puede repetirse entre dos personas.
 */
public record EmployeePerformanceDTO(

        UUID employeeId,
        String employeeName,
        long total

) {

}
