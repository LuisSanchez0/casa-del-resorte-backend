package com.cns.spring_house.vehiclemovement.repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.cns.spring_house.vehiclemovement.entity.VehicleMovement;
import com.cns.spring_house.vehiclemovement.repository.projection.CurrentVehicleProjection;
import com.cns.spring_house.vehiclemovement.repository.projection.EntriesByDayProjection;
import com.cns.spring_house.vehiclemovement.repository.projection.OccupancyByDayProjection;
import com.cns.spring_house.vehiclemovement.repository.projection.TopPlateProjection;

public interface VehicleMovementRepository extends JpaRepository<VehicleMovement, UUID>, JpaSpecificationExecutor<VehicleMovement> {

    /**
     * Toda lectura de un movimiento concreto pasa por aqui: buscar solo por id
     * permitia que un taller leyera o modificara registros de otro conociendo el
     * UUID. El taller viaja siempre desde el token, nunca desde la peticion.
     */
    Optional<VehicleMovement> findByIdAndWorkshopId(UUID id, UUID workshopId);

    /**
     * La regla es "una placa no puede tener dos movimientos abiertos en el MISMO
     * taller". Sin el taller la comprobacion era global y un taller bloqueaba a
     * los demas al dejar una entrada sin salida.
     */
    boolean existsByPlateAndWorkshopIdAndExitIsNull(String plate, UUID workshopId);

    /**
     * Telefono de la bitacora MAS RECIENTE de esa placa que tenga uno, dentro del
     * taller indicado. Es la unica fuente de la sugerencia del alta.
     *
     * Devuelve UN valor y nada mas: ni el movimiento del que sale, ni su fecha,
     * ni la lista de telefonos que ha tenido la placa. Sugerir el proximo numero
     * no necesita ensenar el historial de contacto de un vehiculo.
     *
     * `workshopId` es obligatorio y se aplica EN EL SQL: el aislamiento ocurre en
     * la sentencia, no filtrando despues en Java. Una placa puede haber estado en
     * varios talleres y el telefono que registro otro no es dato de este.
     *
     * `phone IS NOT NULL` va en el WHERE y no despues: una visita posterior SIN
     * telefono —una bitacora anterior a V31— no puede borrar la sugerencia que
     * dejo una anterior que si lo tenia. Se busca el ultimo telefono conocido, no
     * el ultimo movimiento.
     *
     * `UPPER(BTRIM(plate))` es la misma forma canonica que usa el indice unico de
     * la placa abierta desde V28, y tiene que escribirse igual caracter por
     * caracter: es lo que permite a PostgreSQL resolverlo por el indice parcial
     * `idx_vehicle_movement_phone_suggestion` que crea V31.
     *
     * El desempate por `id DESC` es solo determinismo. Dos entradas de la misma
     * placa con el mismo `entry` al microsegundo no ocurren —la regla de placa
     * abierta lo impide—, pero sin desempate el resultado de un empate teorico
     * podria alternar entre peticiones; el UUID no aporta recencia, solo un orden
     * estable.
     *
     * LIMIT 1 en SQL: la consulta es un top-1 acotado y devuelve una fila como
     * mucho, sin cargar el historico de la placa para ordenarlo en memoria.
     */
    @Query(nativeQuery = true, value = """
        SELECT movement.phone
        FROM vehicle_movement movement
        WHERE movement.workshop_id = :workshopId
          AND UPPER(BTRIM(movement.plate)) = :plate
          AND movement.phone IS NOT NULL
        ORDER BY movement.entry DESC, movement.id DESC
        LIMIT 1
    """)
    Optional<String> findLatestPhoneByPlate(
            @Param("workshopId") UUID workshopId,
            @Param("plate") String plate);

    // ── Agregados del dashboard policial ─────────────────────────
    //
    // Todos reciben el taller como parametro obligatorio y lo aplican en el SQL:
    // el aislamiento ocurre en la sentencia, no filtrando despues en Java. Las
    // variantes personales anaden el empleado; como la columna es NULL en los
    // movimientos anteriores a V23, esas filas quedan excluidas por construccion
    // y nunca se atribuyen a nadie.
    //
    // Se cuenta en la base de datos y no cargando entidades: el historico puede
    // crecer sin limite y estas cifras son conteos puros.

    /** Eventos de ENTRADA del taller: filas, no placas distintas. */
    long countByWorkshopId(UUID workshopId);

    long countByWorkshopIdAndEmployeeId(UUID workshopId, UUID employeeId);

    /**
     * Entradas dentro de un intervalo semiabierto `[from, to)`. Se usa para el
     * KPI de hoy con los limites que calcula el servicio, de modo que la fecha
     * de negocio se decide en un unico sitio y es comprobable en pruebas.
     */
    long countByWorkshopIdAndEntryGreaterThanEqualAndEntryLessThan(
            UUID workshopId, LocalDateTime from, LocalDateTime to);

    long countByWorkshopIdAndEmployeeIdAndEntryGreaterThanEqualAndEntryLessThan(
            UUID workshopId, UUID employeeId, LocalDateTime from, LocalDateTime to);

    /** Vehiculos actualmente dentro: la regla autoritativa es `exit IS NULL`. */
    long countByWorkshopIdAndExitIsNull(UUID workshopId);

    long countByWorkshopIdAndEmployeeIdAndExitIsNull(UUID workshopId, UUID employeeId);

    /**
     * Placas distintas, normalizadas en mayusculas igual que el indice unico de
     * V19. Es una magnitud diferente del conteo de entradas: una placa con ocho
     * visitas suma ocho entradas y UNA placa unica.
     */
    @Query("""
        SELECT COUNT(DISTINCT UPPER(movement.plate))
        FROM VehicleMovement movement
        WHERE movement.workshop.id = :workshopId
    """)
    long countDistinctPlatesByWorkshop(@Param("workshopId") UUID workshopId);

    @Query("""
        SELECT COUNT(DISTINCT UPPER(movement.plate))
        FROM VehicleMovement movement
        WHERE movement.workshop.id = :workshopId
          AND movement.employee.id = :employeeId
    """)
    long countDistinctPlatesByWorkshopAndEmployee(
            @Param("workshopId") UUID workshopId,
            @Param("employeeId") UUID employeeId);

    /**
     * Entradas agrupadas por dia natural de `entry`. Devuelve SOLO los dias con
     * actividad; el servicio rellena los ceros para que la serie sea densa.
     *
     * Nativa porque el truncado a dia es de SQL, y con la fecha como texto para
     * no depender de la conversion de {@code java.sql.Date}.
     */
    @Query(nativeQuery = true, value = """
        SELECT TO_CHAR(movement.entry, 'YYYY-MM-DD') AS entryDate,
               COUNT(*) AS entries
        FROM vehicle_movement movement
        WHERE movement.workshop_id = :workshopId
          AND movement.entry >= :from
          AND movement.entry < :to
        GROUP BY TO_CHAR(movement.entry, 'YYYY-MM-DD')
        ORDER BY entryDate
    """)
    List<EntriesByDayProjection> findEntriesByDay(
            @Param("workshopId") UUID workshopId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    @Query(nativeQuery = true, value = """
        SELECT TO_CHAR(movement.entry, 'YYYY-MM-DD') AS entryDate,
               COUNT(*) AS entries
        FROM vehicle_movement movement
        WHERE movement.workshop_id = :workshopId
          AND movement.employee_id = :employeeId
          AND movement.entry >= :from
          AND movement.entry < :to
        GROUP BY TO_CHAR(movement.entry, 'YYYY-MM-DD')
        ORDER BY entryDate
    """)
    List<EntriesByDayProjection> findEntriesByDayForEmployee(
            @Param("workshopId") UUID workshopId,
            @Param("employeeId") UUID employeeId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    /**
     * Ranking de placas por numero de ENTRADAS historicas, agrupando por la placa
     * normalizada para que `p123abc` y `P123ABC` sean la misma.
     *
     * El desempate por `plate ASC` no es cosmetico: sin el, dos placas con el
     * mismo numero de entradas pueden intercambiar posicion entre peticiones y el
     * corte del top parpadearia. El limite se aplica en SQL, no en Java.
     */
    @Query(nativeQuery = true, value = """
        SELECT UPPER(movement.plate) AS plate,
               COUNT(*) AS entries
        FROM vehicle_movement movement
        WHERE movement.workshop_id = :workshopId
        GROUP BY UPPER(movement.plate)
        ORDER BY entries DESC, plate ASC
        LIMIT :maxRows
    """)
    List<TopPlateProjection> findTopPlates(
            @Param("workshopId") UUID workshopId,
            @Param("maxRows") int maxRows);

    @Query(nativeQuery = true, value = """
        SELECT UPPER(movement.plate) AS plate,
               COUNT(*) AS entries
        FROM vehicle_movement movement
        WHERE movement.workshop_id = :workshopId
          AND movement.employee_id = :employeeId
        GROUP BY UPPER(movement.plate)
        ORDER BY entries DESC, plate ASC
        LIMIT :maxRows
    """)
    List<TopPlateProjection> findTopPlatesForEmployee(
            @Param("workshopId") UUID workshopId,
            @Param("employeeId") UUID employeeId,
            @Param("maxRows") int maxRows);

    /**
     * Vehiculos dentro del taller. `SIZE(...)` cuenta las fotografias con una
     * subconsulta en lugar de traer la coleccion EAGER de cada movimiento, y el
     * LEFT JOIN conserva las filas historicas sin empleado.
     */
    @Query("""
        SELECT movement.id AS id,
               movement.plate AS plate,
               movement.entry AS entry,
               employee.id AS employeeId,
               employee.firstName AS firstName,
               employee.lastName AS lastName,
               SIZE(movement.photos) AS photos,
               movement.notes AS notes
        FROM VehicleMovement movement
        LEFT JOIN movement.employee employee
        WHERE movement.workshop.id = :workshopId
          AND movement.exit IS NULL
        ORDER BY movement.entry DESC
    """)
    List<CurrentVehicleProjection> findCurrentVehicles(@Param("workshopId") UUID workshopId);

    /**
     * Entradas del taller sin actor: filas anteriores a V23. Se expone para poder
     * explicar en la interfaz por que la suma de los ambitos personales no llega
     * al total historico, en lugar de dejar el hueco sin justificar.
     */
    long countByWorkshopIdAndEmployeeIsNull(UUID workshopId);

    // ── Agregados del dashboard administrativo ───────────────────

    /**
     * OCUPACION del taller al cerrar cada dia del periodo: cuantos vehiculos
     * seguian dentro, no cuantos entraron.
     *
     * Es historia REAL, no una proyeccion del estado actual: `entry` y `exit`
     * son marcas de tiempo persistidas, asi que la pertenencia de un movimiento
     * a un dia pasado se decide comparando su propio intervalo con el limite de
     * ese dia. Un vehiculo cuenta en el dia D cuando entro antes del cierre de D
     * y no habia salido todavia a esa hora.
     *
     * El `generate_series` produce los dias DENTRO de la sentencia y el LEFT
     * JOIN los conserva aunque no haya ni un vehiculo: la serie sale densa de la
     * base y un dia vacio vale cero. Sin el, habria que emitir una consulta por
     * dia —treinta consultas para un mes— o dejar huecos que la grafica uniria
     * con una recta inventando ocupacion intermedia.
     *
     * `seriesEnd` es lo que hace honesto el ultimo punto. Para un dia ya
     * terminado el limite es su medianoche; para HOY esa medianoche todavia no
     * ha llegado, asi que `LEAST` recorta el limite al instante actual y el
     * ultimo punto de la serie coincide exactamente con
     * {@link #countByWorkshopIdAndExitIsNull}, el KPI de vehiculos dentro. Sin
     * el recorte, un vehiculo que entro y salio hoy quedaria fuera del punto de
     * hoy mientras el KPI y la grafica se contradicen a la vista.
     */
    @Query(nativeQuery = true, value = """
        SELECT TO_CHAR(day.bucket, 'YYYY-MM-DD') AS bucketDate,
               COUNT(movement.id) AS vehicles
        FROM generate_series(
                 CAST(:from AS date),
                 CAST(:to AS date),
                 INTERVAL '1 day') AS day(bucket)
        LEFT JOIN vehicle_movement movement
               ON movement.workshop_id = :workshopId
              AND movement.entry < LEAST(
                      day.bucket + INTERVAL '1 day',
                      CAST(:seriesEnd AS timestamp))
              AND (movement.exit IS NULL
                   OR movement.exit >= LEAST(
                          day.bucket + INTERVAL '1 day',
                          CAST(:seriesEnd AS timestamp)))
        GROUP BY day.bucket
        ORDER BY day.bucket
    """)
    List<OccupancyByDayProjection> findOccupancyByDay(
            @Param("workshopId") UUID workshopId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to,
            @Param("seriesEnd") LocalDateTime seriesEnd);

}
