package com.cns.spring_house.transformation.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.cns.spring_house.job.repository.projection.EmployeePerformanceProjection;
import com.cns.spring_house.transformation.entity.Transformation;

public interface TransformationRepository extends JpaRepository<Transformation, UUID>, JpaSpecificationExecutor<Transformation> {

    /**
     * Transformacion acotada al taller del usuario. Buscar solo por id dejaba
     * leer la de otro taller —con sus productos, sus unidades fisicas y sus
     * caracteristicas— con solo conocer el UUID.
     *
     * `transformation.workshop_id` es columna propia y NOT NULL (V7), asi que el
     * filtro no necesita pasar por el empleado ni por el inventario.
     */
    Optional<Transformation> findByIdAndWorkshopId(UUID id, UUID workshopId);

    /**
     * Precio del inventario de ENTRADA de cada transformacion, indexado por la
     * unidad de SALIDA que produjo.
     *
     * Recibe todas las unidades de golpe y devuelve una sola lista: la caja pinta
     * decenas de trabajos pendientes y una consulta por trabajo seria un N+1.
     *
     * Cada transformacion tiene exactamente una entrada (`inputItem` es un unico
     * UUID obligatorio y los handlers crean un solo TransformationItem INPUT),
     * asi que la correspondencia salida -> precio de entrada no es ambigua.
     */
    @Query("""
        SELECT output.item.id, inputInventory.price
        FROM TransformationItem output
        JOIN output.transformation transformation
        JOIN transformation.items input
        JOIN input.item inputItem
        JOIN inputItem.inventory inputInventory
        WHERE output.item.id IN :outputItemIds
          AND input.role = com.cns.spring_house.transformation.entity.TransformationItemRole.INPUT
          AND output.role <> com.cns.spring_house.transformation.entity.TransformationItemRole.INPUT
    """)
    List<Object[]> findInputPricesByOutputItemIds(
            @Param("outputItemIds") Collection<UUID> outputItemIds);

    /**
     * Empleados con mas TRANSFORMACIONES ejecutadas en el taller autenticado.
     *
     * Es una metrica DISTINTA de la de servicios, no la misma con otro titulo.
     * El dominio guarda dos hechos de ejecucion separados y sin relacion entre
     * si:
     *
     *   JobLog        -> se ejecuto un servicio del catalogo sobre algo.
     *   Transformation-> se convirtio material de inventario en otras piezas.
     *
     * Cada uno tiene su propia tabla, su propio `performed_date` y su propio
     * `employee_id`, y una persona puede encabezar uno y no aparecer en el otro.
     * Reutilizar aqui el ranking de `JobLog` presentaria como trabajo de taller
     * algo que la base nunca afirmo.
     *
     * `transformation.workshop_id` es columna propia y NOT NULL (V7), asi que el
     * aislamiento no necesita pasar por el empleado ni por el inventario.
     */
    @Query(nativeQuery = true, value = """
        SELECT employee.id AS employeeId,
               employee.first_name AS firstName,
               employee.last_name AS lastName,
               COUNT(*) AS total
        FROM transformation
        JOIN employee ON employee.id = transformation.employee_id
        WHERE transformation.workshop_id = :workshopId
        GROUP BY employee.id, employee.first_name, employee.last_name
        ORDER BY total DESC, employee.last_name ASC, employee.first_name ASC, employee.id ASC
        LIMIT :maxRows
    """)
    List<EmployeePerformanceProjection> findTopTransformationEmployees(
            @Param("workshopId") UUID workshopId,
            @Param("maxRows") int maxRows);

}
