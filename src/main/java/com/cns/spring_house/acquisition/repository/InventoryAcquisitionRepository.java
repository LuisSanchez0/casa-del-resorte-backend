package com.cns.spring_house.acquisition.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.cns.spring_house.acquisition.entity.InventoryAcquisition;
import com.cns.spring_house.acquisition.repository.projection.AcquisitionBucketProjection;
import com.cns.spring_house.acquisition.repository.projection.SupplierPerformanceProjection;

import jakarta.persistence.LockModeType;

public interface InventoryAcquisitionRepository
        extends JpaRepository<InventoryAcquisition, UUID> {

    /**
     * Los lotes disponibles de un inventario EN ORDEN FIFO y BLOQUEADOS para
     * escritura.
     *
     * Tres decisiones, cada una necesaria:
     *
     * 1. `PESSIMISTIC_WRITE` traduce a `SELECT ... FOR UPDATE`. Sin el, dos
     *    ventas simultaneas del mismo producto LEEN las dos el mismo remanente,
     *    las dos calculan en Java que alcanza y las dos guardan: diez unidades
     *    disponibles servian catorce. El cerrojo hace que la segunda espere a
     *    que la primera confirme y vuelva a leer el valor ya descontado. No
     *    basta con el CHECK del esquema: el CHECK evita el negativo, pero
     *    convertiria la carrera en un 500 en vez de en una espera.
     *
     * 2. `quantity_remaining > 0` deja fuera los lotes agotados, que en un
     *    inventario con historia son la mayoria. Ademas es la condicion del
     *    indice parcial `idx_inventory_acquisition_fifo`, de modo que la
     *    consulta lo puede usar.
     *
     * 3. El orden es `acquiredAt` y luego `id`. El id no es adorno: varios lotes
     *    pueden compartir instante —dos entradas registradas en la misma
     *    peticion, o una carga inicial— y sin segunda clave PostgreSQL puede
     *    devolverlos en cualquier orden, de modo que la MISMA venta costaria
     *    distinto en dos ejecuciones identicas. Con el id el orden es total y el
     *    FIFO es reproducible.
     *
     * No lleva `Pageable`: el FIFO no sabe de antemano cuantos lotes necesita
     * —depende de lo que quede en cada uno—, y acotarlo a N obligaria a volver a
     * consultar cuando N lotes no alcanzan. Lo que acota la lectura es que solo
     * hay lotes vivos de UN inventario.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT acquisition
        FROM InventoryAcquisition acquisition
        WHERE acquisition.inventory.id = :inventoryId
          AND acquisition.quantityRemaining > 0
        ORDER BY acquisition.acquiredAt ASC, acquisition.id ASC
    """)
    List<InventoryAcquisition> findAvailableForUpdate(
            @Param("inventoryId") UUID inventoryId);

    /**
     * Un lote concreto, bloqueado. Lo usa la DEVOLUCION: al deshacer una venta
     * hay que sumar de nuevo a lotes que pueden estar a cero, de modo que la
     * consulta del FIFO —que filtra los agotados— no sirve aqui.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT acquisition
        FROM InventoryAcquisition acquisition
        WHERE acquisition.id IN :ids
        ORDER BY acquisition.acquiredAt ASC, acquisition.id ASC
    """)
    List<InventoryAcquisition> findAllByIdForUpdate(
            @Param("ids") Iterable<UUID> ids);

    /** Suma de remanentes de un inventario. Solo la usa la reconciliacion. */
    @Query("""
        SELECT COALESCE(SUM(acquisition.quantityRemaining), 0)
        FROM InventoryAcquisition acquisition
        WHERE acquisition.inventory.id = :inventoryId
    """)
    long sumRemaining(@Param("inventoryId") UUID inventoryId);

    // ── Agregados del panel administrativo ────────────────────────
    //
    // Todos reciben el taller como parametro obligatorio y lo aplican DENTRO
    // del SQL: el aislamiento ocurre en la sentencia, no filtrando en Java.
    //
    // Todos filtran ademas `origin = 'PURCHASE'`. Un lote de produccion no es
    // gasto: nadie pago por el, y sumarlo inventaria compras que no ocurrieron.

    /**
     * Serie de GASTO EN COMPRAS dentro de `[from, to)`, agrupada por la unidad
     * que pide el administrador y fechada por `acquired_at`.
     *
     * UNA sola consulta para las tres granularidades y para los tres filtros de
     * tipo, siguiendo exactamente el patron ya establecido en
     * {@code SaleRepository.findGeneratedSalesBuckets}:
     *
     * - `unit` es el argumento de `DATE_TRUNC` y llega como parametro ENLAZADO,
     *   nunca concatenado; el servicio lo deriva de un enum, de modo que solo
     *   puede valer 'day', 'month' o 'year'.
     * - El `GROUP BY` es POSICIONAL. Al enlazarse, cada `:unit` se convierte en
     *   su propio marcador y PostgreSQL veria dos expresiones distintas,
     *   rechazando la consulta con "column must appear in the GROUP BY clause".
     *   Agrupar por la primera columna proyectada nombra la MISMA expresion sin
     *   repetirla.
     * - La clave sale normalizada al PRIMER dia del intervalo, asi que el
     *   servicio densifica avanzando en la misma unidad.
     *
     * `acquisitionType` nulo devuelve nacional e importacion juntas; con valor,
     * solo ese tipo. Es un parametro y no tres consultas porque la unica
     * diferencia es una condicion, y tres sentencias casi identicas divergen con
     * el tiempo.
     *
     * El gasto se calcula como `quantity_received * unit_cost` y no sobre el
     * remanente: lo que se pago se pago entero el dia de la compra, y vaya
     * vendiendose despues o no, ese desembolso no cambia.
     *
     * Devuelve SOLO los intervalos con compras; los ceros los rellena el
     * servicio, igual que el resto de dashboards del proyecto.
     */
    @Query(nativeQuery = true, value = """
        SELECT TO_CHAR(DATE_TRUNC(CAST(:unit AS text), acquisition.acquired_at), 'YYYY-MM-DD') AS bucketDate,
               COUNT(*) AS acquisitionCount,
               COALESCE(SUM(acquisition.quantity_received), 0) AS unitsReceived,
               COALESCE(SUM(acquisition.quantity_received * acquisition.unit_cost), 0) AS amount,
               COALESCE(SUM(acquisition.quantity_received * acquisition.unit_cost)
                        FILTER (WHERE acquisition.acquisition_type = 'NATIONAL'), 0) AS nationalAmount,
               COALESCE(SUM(acquisition.quantity_received * acquisition.unit_cost)
                        FILTER (WHERE acquisition.acquisition_type = 'IMPORTATION'), 0) AS importationAmount
        FROM inventory_acquisition acquisition
        WHERE acquisition.workshop_id = :workshopId
          AND acquisition.origin = 'PURCHASE'
          AND acquisition.acquired_at >= :from
          AND acquisition.acquired_at < :to
          AND (CAST(:acquisitionType AS text) IS NULL
               OR acquisition.acquisition_type = CAST(:acquisitionType AS text))
        GROUP BY 1
        ORDER BY 1
    """)
    List<AcquisitionBucketProjection> findPurchaseBuckets(
            @Param("workshopId") UUID workshopId,
            @Param("unit") String unit,
            @Param("from") java.time.LocalDateTime from,
            @Param("to") java.time.LocalDateTime to,
            @Param("acquisitionType") String acquisitionType);

    /**
     * Proveedores con los que MAS ha trabajado el taller, medido por gasto real.
     *
     * Se rankea por importe y no por numero de compras a proposito: veinte
     * pedidos pequeños no describen al proveedor principal tan bien como lo que
     * de verdad se le ha comprado. Las otras dos cifras viajan igualmente en
     * cada fila para que la lectura no dependa de una sola.
     *
     * El desempate es total —importe, numero de compras, nombre e id—: sin la
     * ultima clave, dos proveedores homonimos con las mismas cifras podian
     * alternarse en el corte del top entre peticiones identicas. Mismo criterio
     * que los rankings de producto y cliente ya existentes.
     *
     * El rango es OPCIONAL: con `from`/`to` nulos el ranking es historico, y con
     * valores se acota al periodo. Se resuelve con una condicion y no con dos
     * consultas por el mismo motivo que arriba.
     */
    @Query(nativeQuery = true, value = """
        SELECT supplier.id AS supplierId,
               supplier.name AS supplierName,
               COUNT(*) AS acquisitionCount,
               COALESCE(SUM(acquisition.quantity_received), 0) AS unitsReceived,
               COALESCE(SUM(acquisition.quantity_received * acquisition.unit_cost), 0) AS totalSpend,
               COALESCE(SUM(acquisition.quantity_received * acquisition.unit_cost)
                        FILTER (WHERE acquisition.acquisition_type = 'NATIONAL'), 0) AS nationalSpend,
               COALESCE(SUM(acquisition.quantity_received * acquisition.unit_cost)
                        FILTER (WHERE acquisition.acquisition_type = 'IMPORTATION'), 0) AS importationSpend
        FROM inventory_acquisition acquisition
        JOIN supplier ON supplier.id = acquisition.supplier_id
        WHERE acquisition.workshop_id = :workshopId
          AND acquisition.origin = 'PURCHASE'
          AND (CAST(:from AS timestamp) IS NULL OR acquisition.acquired_at >= :from)
          AND (CAST(:to AS timestamp) IS NULL OR acquisition.acquired_at < :to)
        GROUP BY supplier.id, supplier.name
        ORDER BY totalSpend DESC, acquisitionCount DESC, supplier.name ASC, supplier.id ASC
        LIMIT :maxRows
    """)
    List<SupplierPerformanceProjection> findTopSuppliers(
            @Param("workshopId") UUID workshopId,
            @Param("from") java.time.LocalDateTime from,
            @Param("to") java.time.LocalDateTime to,
            @Param("maxRows") int maxRows);

    /** Primera compra del taller. Acota el selector de años a la historia real. */
    @Query(nativeQuery = true, value = """
        SELECT MIN(acquisition.acquired_at)
        FROM inventory_acquisition acquisition
        WHERE acquisition.workshop_id = :workshopId
          AND acquisition.origin = 'PURCHASE'
    """)
    java.time.LocalDateTime findFirstPurchaseDate(@Param("workshopId") UUID workshopId);

}
