package com.cns.spring_house.employee.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.cns.spring_house.employee.entity.Employee;

public interface EmployeeRepository extends JpaRepository<Employee, UUID>, JpaSpecificationExecutor<Employee> {

    boolean existsByDpi(String dpi);

    boolean existsByDpiAndIdNot(String dpi, UUID id);

    /**
     * Trabajadores registrados en el taller autenticado.
     *
     * Se cuenta en la base y no cargando la plantilla para medirla en Java.
     *
     * Es "registrados" y no "activos" a proposito: `Employee` no tiene ninguna
     * columna de estado ni de baja —ni `isActive`, ni `deletedAt`, ni fecha de
     * salida—, y `EmployeeService` no expone borrado, de modo que la unica
     * afirmacion que la base sostiene es que la persona pertenece al taller.
     * Rotular esta cifra como "activos" daria por cierta una distincion que el
     * dominio no guarda. `Workshop.isActive` existe, pero describe al TALLER, no
     * a sus trabajadores.
     */
    long countByWorkshopId(UUID workshopId);

}
