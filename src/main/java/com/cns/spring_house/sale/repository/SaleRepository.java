package com.cns.spring_house.sale.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.cns.spring_house.sale.dto.ExchangeInvoiceSummaryDTO;
import com.cns.spring_house.sale.dto.SaleSummaryDTO;
import com.cns.spring_house.sale.entity.InvoiceType;
import com.cns.spring_house.sale.entity.PaymentStatus;
import com.cns.spring_house.sale.entity.Sale;
import com.cns.spring_house.sale.entity.SaleStatus;
import com.cns.spring_house.sale.repository.projection.ClosureExchangeInvoiceProjection;
import com.cns.spring_house.sale.repository.projection.ClosureSoldItemProjection;
import com.cns.spring_house.sale.repository.projection.CustomerPerformanceProjection;
import com.cns.spring_house.sale.repository.projection.InvoiceTypeStatProjection;
import com.cns.spring_house.sale.repository.projection.PaymentMethodStatProjection;
import com.cns.spring_house.sale.repository.projection.ProductPerformanceProjection;
import com.cns.spring_house.sale.repository.projection.SalesBucketProjection;
import com.cns.spring_house.sale.repository.projection.SalesByDayProjection;
import com.cns.spring_house.sale.repository.projection.SalesCostProfitBucketProjection;
import com.cns.spring_house.sale.repository.projection.SalesSummaryProjection;
import com.cns.spring_house.sale.repository.projection.TopProductProjection;

public interface SaleRepository extends JpaRepository<Sale, UUID>, JpaSpecificationExecutor<Sale> {

    /**
     * Toda operacion sobre una venta concreta pasa por aqui: buscar solo por id
     * permitia leer o modificar ventas de otro taller conociendo el UUID.
     *
     * El grafo trae el trabajo que respalda cada linea en el MISMO viaje.
     * `SaleDetail.workResult` es LAZY, asi que resolver su origen al mapear
     * disparaba un SELECT a `work_result` POR LINEA: medido, una venta de
     * cincuenta lineas mandaba cincuenta consultas extra, y una de una sola
     * mandaba una. Con el grafo son cero, a cualquier tamano.
     *
     * Va en ESTE metodo, el unico por el que se lee una venta concreta, y no en
     * uno aparte para el camino de respuesta: separarlos dejaba dos formas de
     * cargar una venta y solo una correcta, de modo que el siguiente que llamara
     * a la otra reintroducia el N+1 sin enterarse.
     *
     * Por el mismo motivo baja tambien por `item -> inventory -> product`, que
     * el mapeo recorre para cada linea. Medido con productos DISTINTOS por
     * linea, esa rama costaba `2N` lecturas de `inventory_item`, `N` de
     * `inventory` y `N` de `product`: una venta de cincuenta lineas mandaba 204
     * sentencias donde una de una linea mandaba 8.
     *
     * Todo lo que se añade son relaciones A UNO: `details` sigue siendo la UNICA
     * coleccion unida, de modo que no hay producto cartesiano. `item.containedItems`
     * queda deliberadamente FUERA —es la segunda coleccion, y unirla multiplicaria
     * las filas de la venta por las unidades de cada contenedor—; se acota por
     * lotes en la propia entidad. Ver {@code InventoryItem.containedItems}.
     */
    @EntityGraph(attributePaths = {
        "details",
        "details.workResult",
        "details.product.family",
        "details.product.model",
        "details.item",
        "details.item.inventory",
        "details.item.inventory.product",
        "details.item.inventory.product.family",
        "details.item.inventory.product.model" })
    Optional<Sale> findByIdAndWorkshopId(UUID id, UUID workshopId);

    /**
     * El listado de ventas, con el mismo grafo y por el mismo motivo.
     *
     * Aqui el N+1 de `workResult` no se paga una vez sino una por cada venta del
     * resultado, asi que dejarlo perezoso es peor que en el detalle. Traerlo en
     * la misma consulta no es "cargar de mas": {@code Sale.details} ya es EAGER,
     * de modo que las lineas se leen igual; lo unico que cambia es que el trabajo
     * de cada una viene en el mismo viaje en vez de en uno propio.
     *
     * El grafo es el MISMO que el del detalle, y no por comodidad: este listado
     * serializa el mismo `SaleResponseDTO`, con las mismas lineas, items y
     * productos, asi que necesita exactamente los mismos datos. Darle un grafo
     * mas pobre no ahorraria nada: volveria a leerlos uno a uno.
     *
     * Sigue sin paginar a proposito: unir una coleccion con `Pageable` obligaria
     * a Hibernate a paginar en memoria. El listado paginado de verdad es la
     * cartera, que usa proyeccion y no toca `sale_detail`.
     */
    @Override
    @EntityGraph(attributePaths = {
        "details",
        "details.workResult",
        "details.product.family",
        "details.product.model",
        "details.item",
        "details.item.inventory",
        "details.item.inventory.product",
        "details.item.inventory.product.family",
        "details.item.inventory.product.model" })
    List<Sale> findAll(Specification<Sale> specification);

    /**
     * Cartera cambiaria paginada.
     *
     * Es una proyeccion y no `findAll(Specification, Pageable)` a proposito:
     * devolver entidades Sale arrastra `details`, que es EAGER, de modo que
     * listar 20 documentos disparaba un SELECT de lineas —con su producto, su
     * familia y su InventoryItem— por cada fila. Aqui el SELECT nombra las once
     * columnas que la pantalla necesita y el unico JOIN es el del cliente, asi
     * que `sale_detail` no aparece en el SQL.
     *
     * `paymentStatus` nulo devuelve toda la cartera, cobrada y pendiente. El
     * taller siempre lo pone el servicio desde el token.
     */
    @Query(value = """
        SELECT new com.cns.spring_house.sale.dto.ExchangeInvoiceSummaryDTO(
            sale.id,
            customer.id,
            customer.nit,
            customer.name,
            sale.taxMode,
            sale.customerName,
            sale.customerNit,
            sale.plate,
            sale.invoiceType,
            sale.paymentStatus,
            sale.paymentMethod,
            sale.total,
            sale.completedAt,
            sale.paidAt)
        FROM Sale sale
        LEFT JOIN sale.customer customer
        WHERE sale.workshop.id = :workshopId
          AND sale.invoiceType = com.cns.spring_house.sale.entity.InvoiceType.EXCHANGE_INVOICE
          AND (:paymentStatus IS NULL OR sale.paymentStatus = :paymentStatus)
    """,
            countQuery = """
        SELECT COUNT(sale)
        FROM Sale sale
        WHERE sale.workshop.id = :workshopId
          AND sale.invoiceType = com.cns.spring_house.sale.entity.InvoiceType.EXCHANGE_INVOICE
          AND (:paymentStatus IS NULL OR sale.paymentStatus = :paymentStatus)
    """)
    Page<ExchangeInvoiceSummaryDTO> findExchangeInvoiceSummaries(
            @Param("workshopId") UUID workshopId,
            @Param("paymentStatus") PaymentStatus paymentStatus,
            Pageable pageable);

    /**
     * Sugerencias del buscador de facturas: ventas COMPLETED del taller que
     * coinciden con un fragmento de texto, acotadas por el Pageable que pasa el
     * servicio.
     *
     * Es una proyeccion por los mismos motivos que la cartera: devolver
     * entidades Sale arrastra `details`, que es EAGER, y sugerir diez facturas
     * disparaba un SELECT de lineas —con producto, familia e InventoryItem— por
     * cada sugerencia. Aqui el unico JOIN es el del cliente.
     *
     * El fragmento se compara contra el id de la venta CONVERTIDO A TEXTO: la
     * secretaria lee el identificador impreso en la factura y teclea sus
     * primeros caracteres, no el UUID entero. Tambien coincide contra nit,
     * nombre de cliente —el registrado o, a Consumidor Final, el escrito en la
     * venta— y placa, que es como se reconoce un documento cuando no se tiene el
     * papel delante.
     *
     * `pattern` nulo devuelve las mas recientes sin filtrar por texto; el
     * servicio lo normaliza a minusculas y le pone los comodines, porque el
     * comodin es parte del valor y no de la consulta.
     *
     * El `ESCAPE` declarado es lo que permite buscar un `%` o un `_` literales:
     * {@link com.cns.spring_house.shared.util.LikePatterns} los neutraliza en
     * el valor, y aqui se nombra el caracter con el que lo hizo en vez de
     * confiar en el que PostgreSQL usa por defecto.
     *
     * CANCELLED, REVERSED y PENDING quedan fuera en el propio SQL: una venta
     * que no llego a emitirse, o que se deshizo, no se registra en la bitacora.
     * Una cambiaria pendiente de cobro SI aparece: esta emitida, que es lo unico
     * que exige la bitacora.
     */
    @Query("""
        SELECT new com.cns.spring_house.sale.dto.SaleSummaryDTO(
            sale.id,
            customer.id,
            customer.nit,
            customer.name,
            sale.taxMode,
            sale.customerName,
            sale.customerNit,
            sale.plate,
            sale.invoiceType,
            sale.paymentStatus,
            sale.paymentMethod,
            sale.total,
            sale.completedAt,
            sale.paidAt)
        FROM Sale sale
        LEFT JOIN sale.customer customer
        WHERE sale.workshop.id = :workshopId
          AND sale.status = com.cns.spring_house.sale.entity.SaleStatus.COMPLETED
          AND (:pattern IS NULL
               OR LOWER(CAST(sale.id AS String)) LIKE :pattern ESCAPE '\\'
               OR LOWER(customer.nit) LIKE :pattern ESCAPE '\\'
               OR LOWER(customer.name) LIKE :pattern ESCAPE '\\'
               OR LOWER(sale.customerName) LIKE :pattern ESCAPE '\\'
               OR LOWER(sale.customerNit) LIKE :pattern ESCAPE '\\'
               OR LOWER(sale.plate) LIKE :pattern ESCAPE '\\')
    """)
    List<SaleSummaryDTO> findCompletedSaleSummaries(
            @Param("workshopId") UUID workshopId,
            @Param("pattern") String pattern,
            Pageable pageable);

    // ── Agregados del dashboard de ventas ─────────────────────────
    //
    // Todos reciben el taller como parametro obligatorio y lo aplican en el
    // SQL: el aislamiento ocurre en la sentencia, no filtrando despues en Java.
    // Las variantes "ForEmployee" anaden el empleado para el ambito personal.
    //
    // CANCELLED y REVERSED quedan fuera de todas estas consultas a proposito:
    // ninguna cuenta como venta exitosa ni aporta a ingresos.

    long countByWorkshopIdAndStatus(UUID workshopId, SaleStatus status);

    long countByWorkshopIdAndEmployeeIdAndStatus(
            UUID workshopId, UUID employeeId, SaleStatus status);

    long countByWorkshopIdAndStatusAndInvoiceTypeAndPaymentStatus(
            UUID workshopId, SaleStatus status, InvoiceType invoiceType,
            PaymentStatus paymentStatus);

    long countByWorkshopIdAndEmployeeIdAndStatusAndInvoiceTypeAndPaymentStatus(
            UUID workshopId, UUID employeeId, SaleStatus status,
            InvoiceType invoiceType, PaymentStatus paymentStatus);

    /**
     * Conteo e importe de ventas COMPLETED dentro de un intervalo semiabierto
     * `[from, to)`, calculado por `completedAt`. Se usa para el KPI de hoy con
     * los limites que decide el servicio a partir del reloj inyectado.
     */
    @Query("""
        SELECT COUNT(sale) AS salesCount, COALESCE(SUM(sale.total), 0) AS amount
        FROM Sale sale
        WHERE sale.workshop.id = :workshopId
          AND sale.status = com.cns.spring_house.sale.entity.SaleStatus.COMPLETED
          AND sale.completedAt >= :from
          AND sale.completedAt < :to
    """)
    SalesSummaryProjection summarizeCompletedSales(
            @Param("workshopId") UUID workshopId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    @Query("""
        SELECT COUNT(sale) AS salesCount, COALESCE(SUM(sale.total), 0) AS amount
        FROM Sale sale
        WHERE sale.workshop.id = :workshopId
          AND sale.employee.id = :employeeId
          AND sale.status = com.cns.spring_house.sale.entity.SaleStatus.COMPLETED
          AND sale.completedAt >= :from
          AND sale.completedAt < :to
    """)
    SalesSummaryProjection summarizeCompletedSalesForEmployee(
            @Param("workshopId") UUID workshopId,
            @Param("employeeId") UUID employeeId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    /**
     * Ventas COMPLETED agrupadas por dia natural de `completed_at`. Devuelve
     * SOLO los dias con actividad; el servicio rellena los ceros para que la
     * serie sea densa. Nativa porque el truncado a dia es de SQL.
     */
    @Query(nativeQuery = true, value = """
        SELECT TO_CHAR(sale.completed_at, 'YYYY-MM-DD') AS saleDate,
               COUNT(*) AS salesCount,
               COALESCE(SUM(sale.total), 0) AS amount
        FROM sale
        WHERE sale.workshop_id = :workshopId
          AND sale.status = 'COMPLETED'
          AND sale.completed_at >= :from
          AND sale.completed_at < :to
        GROUP BY TO_CHAR(sale.completed_at, 'YYYY-MM-DD')
        ORDER BY saleDate
    """)
    List<SalesByDayProjection> findSalesByDay(
            @Param("workshopId") UUID workshopId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    @Query(nativeQuery = true, value = """
        SELECT TO_CHAR(sale.completed_at, 'YYYY-MM-DD') AS saleDate,
               COUNT(*) AS salesCount,
               COALESCE(SUM(sale.total), 0) AS amount
        FROM sale
        WHERE sale.workshop_id = :workshopId
          AND sale.employee_id = :employeeId
          AND sale.status = 'COMPLETED'
          AND sale.completed_at >= :from
          AND sale.completed_at < :to
        GROUP BY TO_CHAR(sale.completed_at, 'YYYY-MM-DD')
        ORDER BY saleDate
    """)
    List<SalesByDayProjection> findSalesByDayForEmployee(
            @Param("workshopId") UUID workshopId,
            @Param("employeeId") UUID employeeId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    /**
     * Productos mas vendidos HOY, agrupados por `Product.id` y no por
     * `invoice_description`: el mismo producto puede facturarse con textos
     * distintos y sigue siendo la misma pieza para este ranking.
     */
    @Query(nativeQuery = true, value = """
        SELECT product.id AS productId,
               product.name AS productName,
               SUM(detail.quantity) AS quantity,
               COALESCE(SUM(detail.sub_total), 0) AS amount
        FROM sale_detail detail
        JOIN sale ON sale.id = detail.sale_id
        JOIN product ON product.id = detail.product_id
        WHERE sale.workshop_id = :workshopId
          AND sale.status = 'COMPLETED'
          AND sale.completed_at >= :from
          AND sale.completed_at < :to
        GROUP BY product.id, product.name
        ORDER BY quantity DESC, product.name ASC
        LIMIT :maxRows
    """)
    List<TopProductProjection> findTopProductsToday(
            @Param("workshopId") UUID workshopId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("maxRows") int maxRows);

    @Query(nativeQuery = true, value = """
        SELECT product.id AS productId,
               product.name AS productName,
               SUM(detail.quantity) AS quantity,
               COALESCE(SUM(detail.sub_total), 0) AS amount
        FROM sale_detail detail
        JOIN sale ON sale.id = detail.sale_id
        JOIN product ON product.id = detail.product_id
        WHERE sale.workshop_id = :workshopId
          AND sale.employee_id = :employeeId
          AND sale.status = 'COMPLETED'
          AND sale.completed_at >= :from
          AND sale.completed_at < :to
        GROUP BY product.id, product.name
        ORDER BY quantity DESC, product.name ASC
        LIMIT :maxRows
    """)
    List<TopProductProjection> findTopProductsTodayForEmployee(
            @Param("workshopId") UUID workshopId,
            @Param("employeeId") UUID employeeId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("maxRows") int maxRows);

    /**
     * Cobros por forma de pago dentro de `[from, to)`: solo ventas COMPLETED,
     * `PaymentStatus = PAID` y `paidAt` dentro de la ventana. Una factura
     * cambiaria pendiente de cobro nunca aparece aqui, aunque se haya emitido
     * hoy; y una emitida hace un mes y cobrada dentro de la ventana si aparece,
     * porque lo que se agrega es el COBRO y no la emision.
     *
     * Se llamaba `findPaymentMethodsToday`: el nombre describia al unico
     * consumidor que tenia entonces —el KPI de hoy del dashboard—, no lo que la
     * consulta hace. El cierre de caja necesita exactamente esta agregacion
     * para su propia ventana, asi que se renombro en vez de duplicarla.
     */
    @Query(nativeQuery = true, value = """
        SELECT sale.payment_method AS method,
               COUNT(*) AS saleCount,
               COALESCE(SUM(sale.total), 0) AS amount
        FROM sale
        WHERE sale.workshop_id = :workshopId
          AND sale.status = 'COMPLETED'
          AND sale.payment_status = 'PAID'
          AND sale.paid_at >= :from
          AND sale.paid_at < :to
        GROUP BY sale.payment_method
        ORDER BY amount DESC
    """)
    List<PaymentMethodStatProjection> findPaymentMethodsBetween(
            @Param("workshopId") UUID workshopId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    /**
     * Lo COBRADO por forma de pago dentro de `[from, to)`, para el cierre de
     * caja.
     *
     * Existe separada de {@link #findPaymentMethodsBetween} porque las dos
     * responden preguntas distintas, y la diferencia importa:
     *
     *   dashboard  cobros de ventas VIGENTES. No tiene columna de
     *              devoluciones, asi que incluir una venta revertida le haria
     *              enseñar dinero que quizas ya salio, sin forma de verlo.
     *   cierre     el MOVIMIENTO DE CAJA. Aqui si existe la contraparte —las
     *              devoluciones y el neto— asi que puede decir la verdad
     *              completa: entraron Q1,000 y salieron Q400.
     *
     * Compartian consulta mientras significaban lo mismo. Ya no significan lo
     * mismo, y forzarlas a compartirla obligaria a que una de las dos mintiera.
     *
     * UN COBRO NO SE BORRA PORQUE LA VENTA SE REVIERTA DESPUES: el dinero entro
     * cuando entro. Lo que la reversion produce, si hubo devolucion, es una
     * salida aparte con su propia fecha.
     *
     * El conjunto de estados es exacto y no un `!= CANCELLED`:
     *   COMPLETED + PAID   cobro normal
     *   REVERSED  + PAID   se cobro y luego se deshizo la venta
     *   PENDING            nunca esta PAID (registerPayment exige COMPLETED)
     *   CANCELLED          solo se cancela lo PENDING, que nunca estuvo pagado
     */
    @Query(nativeQuery = true, value = """
        SELECT sale.payment_method AS method,
               COUNT(*) AS saleCount,
               COALESCE(SUM(sale.total), 0) AS amount
        FROM sale
        WHERE sale.workshop_id = :workshopId
          AND sale.status IN ('COMPLETED', 'REVERSED')
          AND sale.payment_status = 'PAID'
          AND sale.paid_at >= :from
          AND sale.paid_at < :to
        GROUP BY sale.payment_method
        ORDER BY amount DESC
    """)
    List<PaymentMethodStatProjection> findCollectedPaymentMethodsBetween(
            @Param("workshopId") UUID workshopId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    @Query(nativeQuery = true, value = """
        SELECT sale.payment_method AS method,
               COUNT(*) AS saleCount,
               COALESCE(SUM(sale.total), 0) AS amount
        FROM sale
        WHERE sale.workshop_id = :workshopId
          AND sale.employee_id = :employeeId
          AND sale.status = 'COMPLETED'
          AND sale.payment_status = 'PAID'
          AND sale.paid_at >= :from
          AND sale.paid_at < :to
        GROUP BY sale.payment_method
        ORDER BY amount DESC
    """)
    List<PaymentMethodStatProjection> findPaymentMethodsBetweenForEmployee(
            @Param("workshopId") UUID workshopId,
            @Param("employeeId") UUID employeeId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    // ── Agregados del cierre de caja ──────────────────────────────
    //
    // Mismo principio que los del dashboard: se agrega en la sentencia y el
    // taller viaja como parametro obligatorio. La diferencia esta en QUE fecha
    // decide cada cifra, y es deliberada:
    //
    //   FACTURADO -> completed_at (cuando se emitio el documento)
    //   COBRADO   -> paid_at      (cuando entro el dinero)
    //
    // Mezclarlas haria que una cambiaria emitida hoy por Q10,000 y cobrada tres
    // dias despues contara dos veces, o ninguna.

    /**
     * Facturacion de `[from, to)` agrupada por tipo de documento, por
     * `completed_at`. Devuelve una fila por tipo con actividad; el servicio
     * completa con ceros los tipos ausentes.
     *
     * Una cambiaria pendiente de cobro SI cuenta: ya se emitio, y eso es lo que
     * mide la facturacion del dia.
     */
    @Query(nativeQuery = true, value = """
        SELECT sale.invoice_type AS invoiceType,
               COUNT(*) AS saleCount,
               COALESCE(SUM(sale.total), 0) AS amount
        FROM sale
        WHERE sale.workshop_id = :workshopId
          AND sale.status = 'COMPLETED'
          AND sale.completed_at >= :from
          AND sale.completed_at < :to
        GROUP BY sale.invoice_type
    """)
    List<InvoiceTypeStatProjection> findInvoicingBetween(
            @Param("workshopId") UUID workshopId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    /**
     * Cambiarias COBRADAS dentro de `[from, to)`, sin importar cuando se
     * emitieron. Es un subconjunto de {@link #findPaymentMethodsBetween}:
     * responde "de todo lo que entro hoy, cuanto venia de cartera".
     */
    @Query(nativeQuery = true, value = """
        SELECT COUNT(*) AS salesCount,
               COALESCE(SUM(sale.total), 0) AS amount
        FROM sale
        WHERE sale.workshop_id = :workshopId
          -- UN COBRO NO SE BORRA PORQUE LA VENTA SE REVIERTA DESPUES.
          --
          -- El dinero entro cuando entro. Si a las 10:00 se cobraron Q1,000 y a
          -- las 15:00 se revirtio la venta, ese ingreso ocurrio: lo que la
          -- reversion produce, si hubo devolucion, es una SALIDA aparte y con
          -- su propia fecha. Filtrar por `status = COMPLETED` borraba el
          -- ingreso del dia y dejaba la devolucion restando sola, con lo que la
          -- caja cerraba en negativo sin faltar un quetzal en el cajon.
          --
          -- Los cuatro estados posibles, y por que este es el conjunto exacto:
          --   COMPLETED + PAID   el cobro normal
          --   REVERSED  + PAID   se cobro y despues se deshizo la venta: el
          --                      ingreso sigue siendo real
          --   PENDING            nunca esta PAID (registerPayment exige
          --                      COMPLETED), asi que no puede aportar nada
          --   CANCELLED          solo se cancela una venta PENDING, que nunca
          --                      estuvo pagada; se excluye de forma explicita
          --                      para que ningun cambio futuro pueda colar un
          --                      cobro inventado
          AND sale.status IN ('COMPLETED', 'REVERSED')
          AND sale.invoice_type = 'EXCHANGE_INVOICE'
          AND sale.payment_status = 'PAID'
          AND sale.paid_at >= :from
          AND sale.paid_at < :to
    """)
    SalesSummaryProjection summarizeExchangeInvoicesCollectedBetween(
            @Param("workshopId") UUID workshopId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    /**
     * De las cambiarias cobradas en `[from, to)`, solo las EMITIDAS ANTES de la
     * ventana: cobro de cartera vieja, que entra en caja sin sumar un quetzal a
     * la facturacion del dia.
     *
     * No se deduce del resto de cifras del cierre —haria falta cruzar emision y
     * cobro documento a documento—, de ahi que sea su propia consulta.
     */
    @Query(nativeQuery = true, value = """
        SELECT COUNT(*) AS salesCount,
               COALESCE(SUM(sale.total), 0) AS amount
        FROM sale
        WHERE sale.workshop_id = :workshopId
          -- Mismo criterio de cobro que arriba: revertir despues no borra
          -- que el dinero entro.
          AND sale.status IN ('COMPLETED', 'REVERSED')
          AND sale.invoice_type = 'EXCHANGE_INVOICE'
          AND sale.payment_status = 'PAID'
          AND sale.paid_at >= :from
          AND sale.paid_at < :to
          AND sale.completed_at < :from
    """)
    SalesSummaryProjection summarizePreviousExchangeInvoicesCollectedBetween(
            @Param("workshopId") UUID workshopId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    /**
     * ARTICULOS VENDIDOS en el periodo, agregados por la base de datos.
     *
     * Es actividad FACTURADA, no cobrada: se fecha por `completed_at`, de modo
     * que una cambiaria emitida y aun sin cobrar SI aporta sus articulos aqui.
     * Su dinero no entra en lo cobrado hasta que se pague, y eso lo decide otra
     * consulta distinta.
     *
     * Se agrupa por `product.id`, la identidad ESTABLE, y NO por el nombre: el
     * catalogo real tiene productos distintos que se llaman igual, y agruparlos
     * por texto los presentaria como un solo articulo.
     *
     * NO se filtra por categoria. Los servicios (JOB) entran igual que la
     * mercaderia: la pregunta es "que se facturo", y una instalacion facturada
     * es dinero facturado. La categoria viaja en `itemType` para que la pantalla
     * pueda distinguirlos sin que ninguna linea que aporta dinero se pierda por
     * el camino.
     *
     * `sub_total` es el importe HISTORICO de la linea, con el ajuste manual de
     * precio ya incluido.
     *
     * Viajan tambien las DIMENSIONES COMERCIALES del producto —familia, marca y
     * modelo, con id y nombre— para que el cierre las copie en su snapshot. No
     * cambian la granularidad: son funcionalmente dependientes de `product.id`,
     * asi que anadirlas al GROUP BY no parte ni fusiona ninguna fila.
     *
     * Marca y modelo llegan por LEFT JOIN y pueden ser NULL: la marca se alcanza
     * a traves del modelo (`product.model_id` -> `model.brand_id`, NOT NULL), y
     * una familia con `requires_model` en false no tiene ninguno de los dos. El
     * NULL significa NO APLICA, no un dato que falte.
     *
     * Son TRES joins mas en la misma consulta agregada, no una consulta por
     * linea de venta.
     *
     * El orden termina en `product.id` para que sea total: dos productos
     * homonimos con las mismas cifras podrian alternarse entre peticiones
     * identicas, y el snapshot quedaria distinto segun el plan de ejecucion.
     */
    @Query(nativeQuery = true, value = """
        SELECT product.id AS productId,
               product.name AS itemName,
               family.category AS itemType,
               family.id AS familyId,
               family.name AS familyName,
               brand.id AS brandId,
               brand.name AS brandName,
               model.id AS modelId,
               model.name AS modelName,
               SUM(detail.quantity) AS quantity,
               COALESCE(SUM(detail.sub_total), 0) AS billedAmount
        FROM sale_detail detail
        JOIN sale ON sale.id = detail.sale_id
        JOIN product ON product.id = detail.product_id
        JOIN family ON family.id = product.family_id
        LEFT JOIN model ON model.id = product.model_id
        LEFT JOIN brand ON brand.id = model.brand_id
        WHERE sale.workshop_id = :workshopId
          AND sale.status = 'COMPLETED'
          AND sale.completed_at >= :from
          AND sale.completed_at < :to
        GROUP BY product.id, product.name, family.category,
                 family.id, family.name, brand.id, brand.name,
                 model.id, model.name
        ORDER BY billedAmount DESC, quantity DESC, product.name ASC, product.id ASC
    """)
    List<ClosureSoldItemProjection> findSoldItemsBetween(
            @Param("workshopId") UUID workshopId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    /**
     * Las cambiarias EMITIDAS en el periodo, con su estado de cobro AHORA.
     *
     * Se fecha por `completed_at`. El estado que devuelve es el actual; quien la
     * llama al cerrar lo congela en el snapshot, y a partir de ahi el historico
     * ya no vuelve a preguntarlo.
     *
     * Orden determinista por fecha de emision e id: sin la segunda clave, dos
     * documentos emitidos en el mismo instante podrian alternarse.
     */
    @Query(nativeQuery = true, value = """
        SELECT sale.id AS saleId,
               -- El nombre CONGELADO en la venta primero: una edicion posterior
               -- del cliente no puede cambiar la etiqueta de un cierre. El del
               -- cliente queda solo para una fila que no lo tuviera.
               COALESCE(sale.customer_name, customer.name) AS customerName,
               sale.completed_at AS issuedAt,
               sale.total AS amount,
               sale.payment_status AS paymentStatus,
               sale.paid_at AS paidAt,
               sale.payment_method AS paymentMethod
        FROM sale
        LEFT JOIN customer ON customer.id = sale.customer_id
        WHERE sale.workshop_id = :workshopId
          AND sale.status = 'COMPLETED'
          AND sale.invoice_type = 'EXCHANGE_INVOICE'
          AND sale.completed_at >= :from
          AND sale.completed_at < :to
        ORDER BY sale.completed_at ASC, sale.id ASC
    """)
    List<ClosureExchangeInvoiceProjection> findExchangeInvoicesIssuedBetween(
            @Param("workshopId") UUID workshopId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    /**
     * Las cambiarias COBRADAS en el periodo, se emitieran cuando se emitieran.
     *
     * Se fecha por `paid_at` y NO se exige que `completed_at` caiga en la misma
     * ventana: ese es exactamente el caso que el cierre tiene que recoger —una
     * cambiaria del lunes cobrada el miercoles pertenece a los cobros del
     * miercoles—. Filtrar tambien por fecha de emision la haria desaparecer de
     * los dos dias.
     */
    @Query(nativeQuery = true, value = """
        SELECT sale.id AS saleId,
               -- El nombre CONGELADO en la venta primero: una edicion posterior
               -- del cliente no puede cambiar la etiqueta de un cierre. El del
               -- cliente queda solo para una fila que no lo tuviera.
               COALESCE(sale.customer_name, customer.name) AS customerName,
               sale.completed_at AS issuedAt,
               sale.total AS amount,
               sale.payment_status AS paymentStatus,
               sale.paid_at AS paidAt,
               sale.payment_method AS paymentMethod
        FROM sale
        LEFT JOIN customer ON customer.id = sale.customer_id
        WHERE sale.workshop_id = :workshopId
          -- Mismo criterio de cobro: el documento se cobro, y eso no lo
          -- deshace un cambio de estado posterior.
          AND sale.status IN ('COMPLETED', 'REVERSED')
          AND sale.invoice_type = 'EXCHANGE_INVOICE'
          AND sale.payment_status = 'PAID'
          AND sale.paid_at >= :from
          AND sale.paid_at < :to
        ORDER BY sale.paid_at ASC, sale.id ASC
    """)
    List<ClosureExchangeInvoiceProjection> findExchangeInvoicesCollectedBetween(
            @Param("workshopId") UUID workshopId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    /**
     * De las cambiarias EMITIDAS en el periodo, lo que sigue sin cobrarse.
     *
     * No es la cartera global del taller: solo mira los documentos del periodo.
     * La cartera global es otra pregunta y tiene su propia consulta.
     */
    @Query(nativeQuery = true, value = """
        SELECT COUNT(*) AS salesCount,
               COALESCE(SUM(sale.total), 0) AS amount
        FROM sale
        WHERE sale.workshop_id = :workshopId
          AND sale.status = 'COMPLETED'
          AND sale.invoice_type = 'EXCHANGE_INVOICE'
          AND sale.payment_status = 'PENDING'
          AND sale.completed_at >= :from
          AND sale.completed_at < :to
    """)
    SalesSummaryProjection summarizeIssuedExchangeInvoicesPendingBetween(
            @Param("workshopId") UUID workshopId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    /**
     * Saldo cambiario pendiente del taller AHORA. Sin limites de fecha a
     * proposito: la cartera pendiente no es "lo que quedo por cobrar de hoy",
     * es todo lo que el taller tiene por cobrar en este momento, incluidos
     * documentos de meses anteriores.
     */
    @Query(nativeQuery = true, value = """
        SELECT COUNT(*) AS salesCount,
               COALESCE(SUM(sale.total), 0) AS amount
        FROM sale
        WHERE sale.workshop_id = :workshopId
          AND sale.status = 'COMPLETED'
          AND sale.invoice_type = 'EXCHANGE_INVOICE'
          AND sale.payment_status = 'PENDING'
    """)
    SalesSummaryProjection summarizePendingExchangeInvoices(
            @Param("workshopId") UUID workshopId);

    // ── Agregados del dashboard administrativo ────────────────────
    //
    // Mismo principio que los bloques anteriores: el taller viaja como
    // parametro obligatorio y se aplica DENTRO de la sentencia. Ninguna de
    // estas consultas devuelve entidades, asi que `sale_detail` no se carga
    // para contarlo en Java y el mapeo no puede recorrer relaciones perezosas
    // fila a fila.
    //
    // LA REGLA DE ESTE BLOQUE, y lo que lo separa del cierre de caja:
    //
    //   caja      -> DINERO COBRADO   -> COMPLETED + PAID, fechado por paid_at
    //   dashboard -> VENTA GENERADA   -> COMPLETED,        fechado por completed_at
    //
    // El panel del administrador mide RENDIMIENTO COMERCIAL: cuanto ha vendido
    // el taller. Una factura cambiaria emitida y aun no cobrada YA genero esa
    // venta —la mercaderia salio y el documento existe—, asi que cuenta aqui
    // aunque no haya entrado el dinero todavia. Por eso el filtro es solo
    // `status = 'COMPLETED'` y la fecha es la de EMISION, no la de cobro:
    // mezclarlas haria que una cambiaria cobrada tres meses despues apareciera
    // en el mes equivocado del rendimiento comercial.
    //
    // CANCELLED y REVERSED quedan fuera de todo: la primera nunca llego a
    // venderse y la segunda se deshizo. PENDING tampoco: es un carrito abierto,
    // todavia editable, que no ha generado ninguna venta.

    /**
     * Monto TOTAL generado por las ventas del taller en toda su historia.
     *
     * Suma `sale.total`, que es el importe historico del documento: se compone
     * de los `sub_total` de sus lineas al facturar y ya incluye cualquier ajuste
     * manual de precio o descuento que se aplicara en la venta. No se recalcula
     * desde el catalogo, de modo que cambiar hoy el precio de un producto no
     * altera lo que el taller vendio el mes pasado.
     *
     * NO se resta ningun costo. Esta cifra es lo que las ventas generaron, no un
     * margen: el costo de adquisicion no interviene en ninguna consulta de este
     * bloque.
     */
    @Query(nativeQuery = true, value = """
        SELECT COUNT(*) AS salesCount,
               COALESCE(SUM(sale.total), 0) AS amount
        FROM sale
        WHERE sale.workshop_id = :workshopId
          AND sale.status = 'COMPLETED'
    """)
    SalesSummaryProjection summarizeGeneratedSales(
            @Param("workshopId") UUID workshopId);

    /**
     * Fecha de la PRIMERA venta completada del taller. Acota los selectores de
     * periodo del frontend a la historia que de verdad existe, en lugar de
     * ofrecer anos vacios hacia atras. Null en un taller que no ha vendido nada.
     */
    @Query(nativeQuery = true, value = """
        SELECT MIN(sale.completed_at)
        FROM sale
        WHERE sale.workshop_id = :workshopId
          AND sale.status = 'COMPLETED'
    """)
    LocalDateTime findFirstCompletedSaleDate(@Param("workshopId") UUID workshopId);

    /**
     * Serie de ventas generadas dentro de `[from, to)`, agrupada por la unidad
     * que pide el administrador y fechada por `completed_at`.
     *
     * UNA sola consulta para las tres granularidades. `unit` es el argumento de
     * `DATE_TRUNC` y llega como parametro enlazado, no concatenado; ademas el
     * servicio lo deriva de un enum, de modo que solo puede valer 'day', 'month'
     * o 'year'. Tres consultas casi identicas podian divergir con el tiempo.
     *
     * La clave sale normalizada al PRIMER dia del intervalo (`2026-08-01` para
     * agosto, `2026-01-01` para 2026), asi que el servicio reconstruye la serie
     * densa avanzando en la misma unidad sin tener que interpretar el formato.
     *
     * El `GROUP BY` es POSICIONAL y no repite la expresion del SELECT: al
     * enlazarse, cada `:unit` se convierte en su propio marcador, de modo que
     * PostgreSQL veia dos expresiones distintas y rechazaba la consulta con
     * "column sale.completed_at must appear in the GROUP BY clause". Agrupar por
     * la primera columna proyectada nombra la MISMA expresion sin repetirla, y
     * de paso hace imposible que las dos se separen al editar una sola.
     *
     * Devuelve SOLO los intervalos con ventas; los ceros los rellena el
     * servicio, igual que en el dashboard policial y en el de ventas.
     */
    @Query(nativeQuery = true, value = """
        SELECT TO_CHAR(DATE_TRUNC(CAST(:unit AS text), sale.completed_at), 'YYYY-MM-DD') AS bucketDate,
               COUNT(*) AS salesCount,
               COALESCE(SUM(sale.total), 0) AS amount
        FROM sale
        WHERE sale.workshop_id = :workshopId
          AND sale.status = 'COMPLETED'
          AND sale.completed_at >= :from
          AND sale.completed_at < :to
        GROUP BY 1
        ORDER BY 1
    """)
    List<SalesBucketProjection> findGeneratedSalesBuckets(
            @Param("workshopId") UUID workshopId,
            @Param("unit") String unit,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    /**
     * Serie de COSTO DE LO VENDIDO y GANANCIA BRUTA dentro de `[from, to)`.
     *
     * Es una pregunta DISTINTA de la de {@link #findGeneratedSalesBuckets} y de
     * la del gasto en compras, y la diferencia importa:
     *
     * <pre>
     * ventas generadas   lo que se facturo            por completed_at
     * gasto en compras   lo que se pago a proveedores por acquired_at
     * costo vendido      lo que costo lo despachado   por completed_at
     * </pre>
     *
     * Restar el gasto de un mes a la venta de ese mes NO da la ganancia: la
     * mercaderia se compra en un mes y se vende en otro. La ganancia sale de
     * aqui, linea a linea, comparando cada venta con el costo de SU propia
     * mercaderia.
     *
     * Se fecha por `completed_at` y se filtra por COMPLETED, exactamente el
     * mismo universo que la serie de ventas generadas. No por `paid_at`: eso es
     * caja, tiene su propia regla y su propio dueño (el cierre diario), y una
     * cambiaria emitida sin cobrar ya entrego mercaderia y ya genero margen.
     * Compartir universo con la serie de ventas es lo que permite leer las dos
     * graficas juntas sin que se contradigan.
     *
     * CANCELLED y REVERSED quedan fuera, igual que en el resto de agregados.
     *
     * `cost_total` NULL se trata como "sin costo conocido", NUNCA como cero:
     * los importes suman con `FILTER` sobre las lineas cubiertas y las
     * descubiertas se reportan aparte. Sumarlas como cero convertiria su precio
     * integro en ganancia e inflaria el margen del taller.
     *
     * Los servicios quedan fuera POR CATEGORIA, con el mismo
     * `family.category <> 'JOB'` que usan los rankings de producto: un servicio
     * no tiene costo de adquisicion en este modelo —`Job.price` es precio, no
     * costo— y contarlo como linea descubierta ensuciaria la cobertura con algo
     * que nunca podra cubrirse.
     *
     * `GROUP BY` posicional por el mismo motivo que la serie de ventas: al
     * enlazarse, cada `:unit` es un marcador distinto y PostgreSQL rechazaria la
     * consulta si la expresion se repitiera.
     */
    @Query(nativeQuery = true, value = """
        SELECT TO_CHAR(DATE_TRUNC(CAST(:unit AS text), sale.completed_at), 'YYYY-MM-DD') AS bucketDate,
               COALESCE(SUM(detail.sub_total)
                        FILTER (WHERE detail.cost_total IS NOT NULL), 0) AS coveredRevenue,
               COALESCE(SUM(detail.cost_total), 0) AS costOfGoodsSold,
               COALESCE(SUM(detail.sub_total - detail.cost_total)
                        FILTER (WHERE detail.cost_total IS NOT NULL), 0) AS grossProfit,
               COUNT(*) FILTER (WHERE detail.cost_total IS NOT NULL) AS coveredLines,
               COUNT(*) FILTER (WHERE detail.cost_total IS NULL) AS uncoveredLines,
               COALESCE(SUM(detail.sub_total)
                        FILTER (WHERE detail.cost_total IS NULL), 0) AS uncoveredRevenue
        FROM sale_detail detail
        JOIN sale ON sale.id = detail.sale_id
        JOIN product ON product.id = detail.product_id
        JOIN family ON family.id = product.family_id
        WHERE sale.workshop_id = :workshopId
          AND sale.status = 'COMPLETED'
          AND family.category <> 'JOB'
          AND sale.completed_at >= :from
          AND sale.completed_at < :to
        GROUP BY 1
        ORDER BY 1
    """)
    List<SalesCostProfitBucketProjection> findSalesCostProfitBuckets(
            @Param("workshopId") UUID workshopId,
            @Param("unit") String unit,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    /**
     * Productos mas vendidos del taller, por UNIDADES despachadas.
     *
     * `generatedAmount` es la suma de `sale_detail.sub_total`, el importe
     * HISTORICO de cada linea: se escribio al vender, incluye el ajuste manual
     * de precio si lo hubo, y nada vuelve a tocarlo. No se reconstruye
     * multiplicando por el precio de catalogo actual, de modo que subir hoy el
     * precio de un producto no reescribe lo que genero en ventas pasadas.
     *
     * NO se une con `inventory` ni interviene ningun costo. Esta columna es
     * MONTO GENERADO, no margen: restar el costo responderia una pregunta de
     * rentabilidad que este panel no hace, y ademas seria imposible de hacer
     * bien —el modelo no guarda el costo del dia de cada venta—.
     *
     * Se agrupa por `Product.id` y no por `invoice_description`: el mismo
     * producto puede facturarse con textos distintos y sigue siendo la misma
     * pieza para este ranking.
     *
     * SOLO PRODUCTOS. El JOIN a `family` no esta para leer la categoria sino
     * para EXCLUIR los servicios, y hace falta: un servicio tambien apunta a un
     * `Product` —`JobSaleDetailHandler` construye la linea con
     * `job.getProduct()`—, asi que sin este filtro las instalaciones y la mano
     * de obra competian por los puestos de un ranking titulado "productos mas
     * vendidos". Medido contra los datos reales del taller, dos de las diez
     * filas eran servicios.
     *
     * Se excluye por CATEGORIA y no exigiendo fila en `inventory`, que seria la
     * otra forma de dejarlos fuera. `family.category` es el mismo discriminador
     * con el que `SaleDetailHandlerRegistry` decide como vender cada linea, de
     * modo que el ranking usa la definicion del dominio y no una consecuencia
     * de ella. Ademas el inventario es estado ACTUAL y mutable: si manana se
     * borra el inventario de un producto descatalogado, sus ventas pasadas no
     * deben desaparecer del historico.
     *
     * Quedan dentro NORMAL, SPECIAL y CONTAINER, que es lo que el taller vende
     * como mercaderia. La salida de una transformacion tambien: se vende como
     * el producto que es, con su propia familia, y llegar por transformacion no
     * la hace menos producto.
     *
     * El desempate termina en `product.id`: el catalogo real tiene productos
     * distintos con el mismo nombre, y dos de ellos empatados en unidades e
     * importe podian intercambiar posicion entre peticiones identicas. El id es
     * unico, asi que el orden queda total.
     */
    @Query(nativeQuery = true, value = """
        SELECT product.id AS productId,
               product.name AS productName,
               SUM(detail.quantity) AS quantity,
               COALESCE(SUM(detail.sub_total), 0) AS generatedAmount
        FROM sale_detail detail
        JOIN sale ON sale.id = detail.sale_id
        JOIN product ON product.id = detail.product_id
        JOIN family ON family.id = product.family_id
        WHERE sale.workshop_id = :workshopId
          AND sale.status = 'COMPLETED'
          AND family.category <> 'JOB'
        GROUP BY product.id, product.name
        ORDER BY quantity DESC, generatedAmount DESC, product.name ASC, product.id ASC
        LIMIT :maxRows
    """)
    List<ProductPerformanceProjection> findTopSellingProducts(
            @Param("workshopId") UUID workshopId,
            @Param("maxRows") int maxRows);

    /**
     * Clientes que mas venta han generado en el taller.
     *
     * Misma regla que {@link #summarizeGeneratedSales}: ventas COMPLETED, sin
     * mirar el estado de cobro. Un cliente con una cambiaria emitida y pendiente
     * SI aparece —esa venta se genero—, de modo que la suma de este ranking es
     * comparable con el KPI de monto generado y no con la caja.
     *
     * El JOIN a cliente es INNER a proposito: una venta de mostrador sin cliente
     * asociado no pertenece a nadie y no puede encabezar un ranking de clientes.
     *
     * `purchaseCount` cuenta exactamente los documentos que suman en el importe,
     * de modo que las dos cifras de una misma fila se leen juntas.
     *
     * Desempate estable y completo —importe, compras, nombre, id—: sin la ultima
     * clave, dos clientes homonimos con las mismas cifras podian alternarse en
     * el corte del top entre peticiones.
     */
    @Query(nativeQuery = true, value = """
        SELECT customer.id AS customerId,
               customer.name AS customerName,
               customer.nit AS customerNit,
               COUNT(*) AS purchaseCount,
               COALESCE(SUM(sale.total), 0) AS generatedAmount
        FROM sale
        JOIN customer ON customer.id = sale.customer_id
        WHERE sale.workshop_id = :workshopId
          AND sale.status = 'COMPLETED'
        GROUP BY customer.id, customer.name, customer.nit
        ORDER BY generatedAmount DESC, purchaseCount DESC, customer.name ASC, customer.id ASC
        LIMIT :maxRows
    """)
    List<CustomerPerformanceProjection> findTopCustomersByGeneratedSales(
            @Param("workshopId") UUID workshopId,
            @Param("maxRows") int maxRows);

}
