package com.cns.spring_house.job.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.cns.spring_house.job.entity.JobLog;
import com.cns.spring_house.job.repository.projection.EmployeePerformanceProjection;

public interface JobLogRepository extends JpaRepository<JobLog, UUID>, JpaSpecificationExecutor<JobLog> {

    @Query("""
        SELECT log
        FROM JobLog log
        WHERE log.id = :id
          AND log.job.workshop.id = :workshopId
    """)
    Optional<JobLog> findByIdAndWorkshopId(
            @Param("id") UUID id,
            @Param("workshopId") UUID workshopId);

    /**
     * Empleados con mas SERVICIOS ejecutados en el taller autenticado.
     *
     * `JobLog` es el registro de EJECUCION de un servicio, y su `employee_id` es
     * quien lo realizo. No se cuenta desde `Sale.employee`, que es quien cobro
     * el documento: son personas distintas por diseno —la caja factura lo que el
     * taller ya ejecuto—, y atribuir el trabajo al cajero premiaria a quien
     * atiende el mostrador por el trabajo de quien esta en el taller.
     *
     * El taller entra por `job.workshop_id` y no por el empleado: el servicio
     * pertenece a un taller, que es la misma ruta de aislamiento que usa
     * {@link #findByIdAndWorkshopId}. Filtrar por el taller del empleado daria
     * el mismo resultado hoy pero dejaria de ser cierto en cuanto alguien
     * cambie de taller, y reescribiria su historial de golpe.
     *
     * Desempate por apellido y nombre para que el corte del top no parpadee
     * entre peticiones cuando dos personas empatan.
     */
    @Query(nativeQuery = true, value = """
        SELECT employee.id AS employeeId,
               employee.first_name AS firstName,
               employee.last_name AS lastName,
               COUNT(*) AS total
        FROM job_log log
        JOIN job ON job.id = log.job_id
        JOIN employee ON employee.id = log.employee_id
        WHERE job.workshop_id = :workshopId
        GROUP BY employee.id, employee.first_name, employee.last_name
        ORDER BY total DESC, employee.last_name ASC, employee.first_name ASC, employee.id ASC
        LIMIT :maxRows
    """)
    List<EmployeePerformanceProjection> findTopServiceEmployees(
            @Param("workshopId") UUID workshopId,
            @Param("maxRows") int maxRows);

}
