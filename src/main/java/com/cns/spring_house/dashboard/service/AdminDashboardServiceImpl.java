package com.cns.spring_house.dashboard.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cns.spring_house.acquisition.entity.AcquisitionType;
import com.cns.spring_house.acquisition.repository.InventoryAcquisitionRepository;
import com.cns.spring_house.acquisition.repository.projection.AcquisitionBucketProjection;
import com.cns.spring_house.acquisition.repository.projection.SupplierPerformanceProjection;
import com.cns.spring_house.dashboard.dto.AcquisitionTrendPointDTO;
import com.cns.spring_house.dashboard.dto.AcquisitionTrendResponseDTO;
import com.cns.spring_house.dashboard.dto.AdminDashboardOverviewResponseDTO;
import com.cns.spring_house.dashboard.dto.CostCoverage;
import com.cns.spring_house.dashboard.dto.SalesCostProfitPointDTO;
import com.cns.spring_house.dashboard.dto.SalesCostProfitTrendResponseDTO;
import com.cns.spring_house.dashboard.dto.SupplierPerformanceDTO;
import com.cns.spring_house.dashboard.dto.TopSuppliersResponseDTO;
import com.cns.spring_house.sale.repository.projection.SalesCostProfitBucketProjection;
import com.cns.spring_house.dashboard.dto.CustomerPerformanceDTO;
import com.cns.spring_house.dashboard.dto.EmployeePerformanceDTO;
import com.cns.spring_house.dashboard.dto.ProductPerformanceDTO;
import com.cns.spring_house.dashboard.dto.ProductSalesDTO;
import com.cns.spring_house.dashboard.dto.SalesGranularity;
import com.cns.spring_house.dashboard.dto.SalesTrendPointDTO;
import com.cns.spring_house.dashboard.dto.SalesTrendResponseDTO;
import com.cns.spring_house.dashboard.dto.VehicleOccupancyPointDTO;
import com.cns.spring_house.dashboard.dto.VehicleOccupancyResponseDTO;
import com.cns.spring_house.employee.repository.EmployeeRepository;
import com.cns.spring_house.inventory.repository.InventoryRepository;
import com.cns.spring_house.inventory.repository.projection.ProductSalesProjection;
import com.cns.spring_house.job.repository.JobLogRepository;
import com.cns.spring_house.job.repository.projection.EmployeePerformanceProjection;
import com.cns.spring_house.sale.repository.SaleRepository;
import com.cns.spring_house.sale.repository.projection.CustomerPerformanceProjection;
import com.cns.spring_house.sale.repository.projection.ProductPerformanceProjection;
import com.cns.spring_house.sale.repository.projection.SalesBucketProjection;
import com.cns.spring_house.sale.repository.projection.SalesSummaryProjection;
import com.cns.spring_house.shared.exception.ValidationException;
import com.cns.spring_house.shared.security.SecurityUtils;
import com.cns.spring_house.transformation.repository.TransformationRepository;
import com.cns.spring_house.vehiclemovement.repository.VehicleMovementRepository;
import com.cns.spring_house.vehiclemovement.repository.projection.OccupancyByDayProjection;

import lombok.RequiredArgsConstructor;

/**
 * Agregaciones del panel administrativo.
 *
 * Dos reglas gobiernan todo lo que hay aqui:
 *
 * 1. Se cuenta y se suma en la base de datos. Ninguna cifra sale de cargar
 *    entidades para recorrerlas en Java, ni siquiera las que hoy caben en
 *    memoria: el historico de un taller crece sin limite y estas consultas son
 *    agregados puros.
 *
 * 2. El taller sale SIEMPRE del token, nunca de la peticion. Ningun metodo de
 *    esta clase acepta un identificador de taller, de modo que no hay forma de
 *    pedir las cifras de otro.
 *
 * El servicio vive en su propio paquete y no dentro de un modulo de dominio
 * porque cruza seis: ventas, inventario, movimientos, servicios,
 * transformaciones y plantilla. Las CONSULTAS, en cambio, viven cada una en el
 * repositorio de la tabla que consultan, igual que las del cierre de caja viven
 * en {@code SaleRepository} aunque las use {@code CashClosureCalculator}.
 *
 * Lo que mide este panel es RENDIMIENTO COMERCIAL —cuanto ha vendido el
 * taller—, no caja. Una venta COMPLETED cuenta aunque su cobro siga pendiente,
 * y ninguna cifra resta costos: no hay margen ni rentabilidad en ningun punto
 * de esta clase. La caja, con su regla propia (COMPLETED + PAID por `paid_at`),
 * es el cierre diario y vive en otro sitio.
 */
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Service
public class AdminDashboardServiceImpl implements AdminDashboardService {

    /** Filas del ranking de productos mas vendidos. */
    static final int TOP_PRODUCTS_LIMIT = 10;

    /** Filas de los rankings de rotacion baja, empleados y clientes. */
    static final int TOP_RANKING_LIMIT = 5;

    /** Ventana por defecto de la grafica de ocupacion, incluyendo hoy. */
    static final int DEFAULT_OCCUPANCY_DAYS = 30;

    /**
     * Tope de la ventana de ocupacion. No es una restriccion de rendimiento
     * —la consulta es una sola— sino de legibilidad: mas de un ano de puntos
     * diarios no cabe en el ancho de una tarjeta.
     */
    static final int MAX_OCCUPANCY_DAYS = 366;

    private final SaleRepository saleRepository;
    private final VehicleMovementRepository vehicleMovementRepository;
    private final JobLogRepository jobLogRepository;
    private final TransformationRepository transformationRepository;
    private final EmployeeRepository employeeRepository;
    private final InventoryRepository inventoryRepository;
    private final InventoryAcquisitionRepository acquisitionRepository;
    private final Clock clock;

    // ── Resumen ──────────────────────────────────────────────────

    @Override
    public AdminDashboardOverviewResponseDTO getOverview() {
        UUID workshopId = SecurityUtils.getWorkshopId();
        LocalDate businessDate = LocalDate.now(clock);

        SalesSummaryProjection generated = saleRepository
                .summarizeGeneratedSales(workshopId);
        LocalDateTime firstSale = saleRepository
                .findFirstCompletedSaleDate(workshopId);

        return new AdminDashboardOverviewResponseDTO(
                businessDate,
                firstSale == null ? null : firstSale.toLocalDate(),
                amountOf(generated),
                generated == null ? 0L : generated.getSalesCount(),
                // COUNT(*) sobre movimientos abiertos es exacto porque el indice
                // parcial `uq_vehicle_movement_open_plate_per_workshop` impide
                // que un mismo vehiculo tenga dos entradas abiertas en el taller.
                // Sin esa garantia habria que contar placas distintas, que es
                // tapar un problema de integridad en vez de resolverlo.
                vehicleMovementRepository.countByWorkshopIdAndExitIsNull(workshopId),
                employeeRepository.countByWorkshopId(workshopId),
                saleRepository.findTopSellingProducts(workshopId, TOP_PRODUCTS_LIMIT)
                        .stream()
                        .map(AdminDashboardServiceImpl::toProductPerformance)
                        .toList(),
                inventoryRepository.findLeastSellingProducts(workshopId, TOP_RANKING_LIMIT)
                        .stream()
                        .map(AdminDashboardServiceImpl::toProductSales)
                        .toList(),
                jobLogRepository.findTopServiceEmployees(workshopId, TOP_RANKING_LIMIT)
                        .stream()
                        .map(AdminDashboardServiceImpl::toEmployeePerformance)
                        .toList(),
                transformationRepository
                        .findTopTransformationEmployees(workshopId, TOP_RANKING_LIMIT)
                        .stream()
                        .map(AdminDashboardServiceImpl::toEmployeePerformance)
                        .toList(),
                saleRepository.findTopCustomersByGeneratedSales(workshopId, TOP_RANKING_LIMIT)
                        .stream()
                        .map(AdminDashboardServiceImpl::toCustomerPerformance)
                        .toList());
    }

    // ── Ventas generadas ─────────────────────────────────────────

    /**
     * La serie de ventas generadas.
     *
     * La primera venta del taller solo se consulta para YEAR, que es la unica
     * granularidad cuyo rango depende de la historia disponible; DAY y MONTH lo
     * derivan del periodo pedido y de la fecha de negocio, asi que pedirlo
     * siempre seria una consulta desperdiciada en el caso comun.
     */
    @Override
    public SalesTrendResponseDTO getSalesTrend(
            SalesGranularity granularity, String period) {

        UUID workshopId = SecurityUtils.getWorkshopId();
        LocalDate businessDate = LocalDate.now(clock);

        LocalDate historyStart = null;
        if (granularity == SalesGranularity.YEAR) {
            LocalDateTime firstSale = saleRepository
                    .findFirstCompletedSaleDate(workshopId);
            historyStart = firstSale == null ? null : firstSale.toLocalDate();
        }

        SalesPeriod resolved = SalesPeriod.resolve(
                granularity, period, businessDate, historyStart);

        if (resolved.isEmpty()) {
            return new SalesTrendResponseDTO(
                    granularity, resolved.from(), resolved.to(),
                    BigDecimal.ZERO, 0L, List.of());
        }

        List<SalesTrendPointDTO> points = densifySales(
                saleRepository.findGeneratedSalesBuckets(
                        workshopId,
                        granularity.sqlUnit(),
                        resolved.from().atStartOfDay(),
                        exclusiveEnd(resolved)),
                resolved);

        BigDecimal total = points.stream()
                .map(SalesTrendPointDTO::generatedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long salesCount = points.stream()
                .mapToLong(SalesTrendPointDTO::salesCount)
                .sum();

        return new SalesTrendResponseDTO(
                granularity, resolved.from(), resolved.to(), total, salesCount, points);
    }

    /**
     * Limite superior EXCLUSIVO de la consulta, en la misma convencion
     * `[from, to)` que el resto del proyecto: el dia siguiente al ultimo dia,
     * el mes siguiente al ultimo mes o el ano siguiente al ultimo ano.
     *
     * Es el limite del INTERVALO completo y no de la fecha de negocio: una venta
     * completada hoy a las 18:00 tiene que caer dentro del punto de hoy, y con
     * `to` a secas quedaria fuera de su propio dia.
     */
    private LocalDateTime exclusiveEnd(SalesPeriod period) {
        return switch (period.granularity()) {
            case DAY -> period.to().plusDays(1).atStartOfDay();
            case MONTH -> period.to().plusMonths(1).atStartOfDay();
            case YEAR -> period.to().plusYears(1).atStartOfDay();
        };
    }

    /**
     * Convierte las filas con ventas en una serie continua.
     *
     * Un intervalo sin ventas es informacion real —ese dia no se vendio—, asi
     * que vale cero y no se omite: omitiendolo, la grafica uniria dos fechas
     * lejanas con una recta y aparentaria ventas intermedias que nunca
     * existieron. Mismo criterio que el resto de dashboards del proyecto.
     *
     * El avance usa la unidad de la granularidad, no dias sueltos: la clave que
     * devuelve la consulta ya viene normalizada al primer dia del intervalo, de
     * modo que sumar un mes o un ano cae exactamente sobre la siguiente clave.
     */
    private List<SalesTrendPointDTO> densifySales(
            List<SalesBucketProjection> rows, SalesPeriod period) {

        Map<LocalDate, SalesBucketProjection> byBucket = new HashMap<>();
        for (SalesBucketProjection row : rows) {
            byBucket.put(LocalDate.parse(row.getBucketDate()), row);
        }

        UnaryOperator<LocalDate> next = switch (period.granularity()) {
            case DAY -> date -> date.plusDays(1);
            case MONTH -> date -> date.plusMonths(1);
            case YEAR -> date -> date.plusYears(1);
        };

        List<SalesTrendPointDTO> series = new ArrayList<>();
        for (LocalDate bucket = period.from();
                !bucket.isAfter(period.to());
                bucket = next.apply(bucket)) {

            SalesBucketProjection row = byBucket.get(bucket);
            series.add(new SalesTrendPointDTO(
                    bucket,
                    row == null ? 0L : row.getSalesCount(),
                    row == null ? BigDecimal.ZERO : row.getAmount()));
        }
        return series;
    }

    // ── Gasto en compras ─────────────────────────────────────────

    /**
     * La serie de GASTO EN COMPRAS.
     *
     * Misma maquinaria que la serie de ventas —resolver periodo, una consulta
     * agregada, densificar, sumar en el servidor— porque es el mismo problema
     * con otra tabla y otra fecha. Lo que NO comparte es el significado: aqui se
     * mide dinero que SALIO hacia proveedores, fechado por `acquiredAt`, y en la
     * otra dinero que se facturo. Restarlas por periodo no da ganancia.
     *
     * La primera compra solo se consulta para YEAR, la unica granularidad cuyo
     * rango depende de la historia disponible, igual que en la serie de ventas:
     * pedirla siempre seria una consulta desperdiciada en el caso comun.
     */
    @Override
    public AcquisitionTrendResponseDTO getAcquisitionTrend(
            SalesGranularity granularity, String period, AcquisitionType acquisitionType) {

        UUID workshopId = SecurityUtils.getWorkshopId();
        LocalDate businessDate = LocalDate.now(clock);

        LocalDate historyStart = null;
        if (granularity == SalesGranularity.YEAR) {
            LocalDateTime firstPurchase = acquisitionRepository
                    .findFirstPurchaseDate(workshopId);
            historyStart = firstPurchase == null ? null : firstPurchase.toLocalDate();
        }

        SalesPeriod resolved = SalesPeriod.resolve(
                granularity, period, businessDate, historyStart);

        if (resolved.isEmpty()) {
            return new AcquisitionTrendResponseDTO(
                    granularity, acquisitionType, resolved.from(), resolved.to(),
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    0L, 0L, List.of());
        }

        // El enum se convierte a texto AQUI y no en la consulta: asi lo unico
        // que puede llegar al SQL es un literal del enum, nunca texto libre.
        String typeFilter = acquisitionType == null ? null : acquisitionType.name();

        List<AcquisitionTrendPointDTO> points = densifyAcquisitions(
                acquisitionRepository.findPurchaseBuckets(
                        workshopId,
                        granularity.sqlUnit(),
                        resolved.from().atStartOfDay(),
                        exclusiveEnd(resolved),
                        typeFilter),
                resolved);

        BigDecimal total = BigDecimal.ZERO;
        BigDecimal national = BigDecimal.ZERO;
        BigDecimal importation = BigDecimal.ZERO;
        long count = 0L;
        long units = 0L;

        for (AcquisitionTrendPointDTO point : points) {
            total = total.add(point.amount());
            national = national.add(point.nationalAmount());
            importation = importation.add(point.importationAmount());
            count += point.acquisitionCount();
            units += point.unitsReceived();
        }

        return new AcquisitionTrendResponseDTO(
                granularity, acquisitionType, resolved.from(), resolved.to(),
                total, national, importation, count, units, points);
    }

    /**
     * Igual que {@link #densifySales}: un intervalo sin compras vale cero y no
     * se omite. Omitirlo uniria dos fechas lejanas con una recta y aparentaria
     * compras intermedias que nunca existieron.
     */
    private List<AcquisitionTrendPointDTO> densifyAcquisitions(
            List<AcquisitionBucketProjection> rows, SalesPeriod period) {

        Map<LocalDate, AcquisitionBucketProjection> byBucket = new HashMap<>();
        for (AcquisitionBucketProjection row : rows) {
            byBucket.put(LocalDate.parse(row.getBucketDate()), row);
        }

        UnaryOperator<LocalDate> next = nextBucket(period);

        List<AcquisitionTrendPointDTO> series = new ArrayList<>();
        for (LocalDate bucket = period.from();
                !bucket.isAfter(period.to());
                bucket = next.apply(bucket)) {

            AcquisitionBucketProjection row = byBucket.get(bucket);
            series.add(new AcquisitionTrendPointDTO(
                    bucket,
                    row == null ? 0L : row.getAcquisitionCount(),
                    row == null ? 0L : row.getUnitsReceived(),
                    row == null ? BigDecimal.ZERO : row.getAmount(),
                    row == null ? BigDecimal.ZERO : row.getNationalAmount(),
                    row == null ? BigDecimal.ZERO : row.getImportationAmount()));
        }
        return series;
    }

    // ── Rentabilidad de ventas ───────────────────────────────────

    /**
     * La serie de COSTO DE LO VENDIDO y GANANCIA BRUTA.
     *
     * Se fecha por la emision de la venta y comparte universo exacto con la
     * serie de ventas generadas (COMPLETED, por `completedAt`), de modo que las
     * dos graficas se pueden leer una al lado de la otra. La ganancia sale de
     * restar a cada linea SU propio costo historico, no de restar el gasto del
     * periodo a la venta del periodo.
     *
     * La cobertura se calcula sobre los totales del periodo y viaja en la
     * respuesta: si alguna linea no tenia costo, la interfaz lo dice en vez de
     * presentar una ganancia parcial como si fuera completa.
     */
    @Override
    public SalesCostProfitTrendResponseDTO getSalesCostProfitTrend(
            SalesGranularity granularity, String period) {

        UUID workshopId = SecurityUtils.getWorkshopId();
        LocalDate businessDate = LocalDate.now(clock);

        LocalDate historyStart = null;
        if (granularity == SalesGranularity.YEAR) {
            LocalDateTime firstSale = saleRepository
                    .findFirstCompletedSaleDate(workshopId);
            historyStart = firstSale == null ? null : firstSale.toLocalDate();
        }

        SalesPeriod resolved = SalesPeriod.resolve(
                granularity, period, businessDate, historyStart);

        if (resolved.isEmpty()) {
            return new SalesCostProfitTrendResponseDTO(
                    granularity, resolved.from(), resolved.to(),
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    0L, 0L, BigDecimal.ZERO, CostCoverage.EMPTY, List.of());
        }

        List<SalesCostProfitPointDTO> points = densifyCostProfit(
                saleRepository.findSalesCostProfitBuckets(
                        workshopId,
                        granularity.sqlUnit(),
                        resolved.from().atStartOfDay(),
                        exclusiveEnd(resolved)),
                resolved);

        BigDecimal coveredRevenue = BigDecimal.ZERO;
        BigDecimal cost = BigDecimal.ZERO;
        BigDecimal profit = BigDecimal.ZERO;
        BigDecimal uncoveredRevenue = BigDecimal.ZERO;
        long coveredLines = 0L;
        long uncoveredLines = 0L;

        for (SalesCostProfitPointDTO point : points) {
            coveredRevenue = coveredRevenue.add(point.coveredRevenue());
            cost = cost.add(point.costOfGoodsSold());
            profit = profit.add(point.grossProfit());
            uncoveredRevenue = uncoveredRevenue.add(point.uncoveredRevenue());
            coveredLines += point.coveredLines();
            uncoveredLines += point.uncoveredLines();
        }

        return new SalesCostProfitTrendResponseDTO(
                granularity, resolved.from(), resolved.to(),
                coveredRevenue, cost, profit,
                coveredLines, uncoveredLines, uncoveredRevenue,
                coverageOf(coveredLines, uncoveredLines), points);
    }

    /**
     * Un periodo sin ninguna linea de producto es EMPTY y no COMPLETE: no hay
     * nada que cubrir, que no es lo mismo que haberlo cubierto todo. Rotular de
     * "historico completo" un mes sin ventas seria una afirmacion vacia.
     */
    private static CostCoverage coverageOf(long coveredLines, long uncoveredLines) {
        if (coveredLines == 0L && uncoveredLines == 0L) {
            return CostCoverage.EMPTY;
        }
        return uncoveredLines == 0L ? CostCoverage.COMPLETE : CostCoverage.PARTIAL;
    }

    private List<SalesCostProfitPointDTO> densifyCostProfit(
            List<SalesCostProfitBucketProjection> rows, SalesPeriod period) {

        Map<LocalDate, SalesCostProfitBucketProjection> byBucket = new HashMap<>();
        for (SalesCostProfitBucketProjection row : rows) {
            byBucket.put(LocalDate.parse(row.getBucketDate()), row);
        }

        UnaryOperator<LocalDate> next = nextBucket(period);

        List<SalesCostProfitPointDTO> series = new ArrayList<>();
        for (LocalDate bucket = period.from();
                !bucket.isAfter(period.to());
                bucket = next.apply(bucket)) {

            SalesCostProfitBucketProjection row = byBucket.get(bucket);
            series.add(new SalesCostProfitPointDTO(
                    bucket,
                    row == null ? BigDecimal.ZERO : row.getCoveredRevenue(),
                    row == null ? BigDecimal.ZERO : row.getCostOfGoodsSold(),
                    row == null ? BigDecimal.ZERO : row.getGrossProfit(),
                    row == null ? 0L : row.getCoveredLines(),
                    row == null ? 0L : row.getUncoveredLines(),
                    row == null ? BigDecimal.ZERO : row.getUncoveredRevenue()));
        }
        return series;
    }

    // ── Proveedores ──────────────────────────────────────────────

    /**
     * Los cinco proveedores a los que mas se les ha comprado EN EL PERIODO
     * seleccionado.
     *
     * Sigue el periodo de las graficas y no la historia entera a proposito: esta
     * tarjeta vive junto a la de gasto en compras, y un ranking historico sin
     * etiqueta al lado de una grafica mensual invita a leer las dos cifras como
     * si hablaran del mismo tiempo. El rango que se midio viaja en la respuesta
     * para que la interfaz lo rotule.
     *
     * El limite lo fija el servidor: `TOP_RANKING_LIMIT`, el mismo cinco que el
     * resto de rankings del panel.
     */
    @Override
    public TopSuppliersResponseDTO getTopSuppliers(
            SalesGranularity granularity, String period) {

        UUID workshopId = SecurityUtils.getWorkshopId();
        LocalDate businessDate = LocalDate.now(clock);

        LocalDate historyStart = null;
        if (granularity == SalesGranularity.YEAR) {
            LocalDateTime firstPurchase = acquisitionRepository
                    .findFirstPurchaseDate(workshopId);
            historyStart = firstPurchase == null ? null : firstPurchase.toLocalDate();
        }

        SalesPeriod resolved = SalesPeriod.resolve(
                granularity, period, businessDate, historyStart);

        if (resolved.isEmpty()) {
            return new TopSuppliersResponseDTO(
                    resolved.from(), resolved.to(), List.of());
        }

        List<SupplierPerformanceDTO> suppliers = acquisitionRepository
                .findTopSuppliers(
                        workshopId,
                        resolved.from().atStartOfDay(),
                        exclusiveEnd(resolved),
                        TOP_RANKING_LIMIT)
                .stream()
                .map(AdminDashboardServiceImpl::toSupplierPerformance)
                .toList();

        return new TopSuppliersResponseDTO(
                resolved.from(), resolved.to(), suppliers);
    }

    private static SupplierPerformanceDTO toSupplierPerformance(
            SupplierPerformanceProjection projection) {
        return new SupplierPerformanceDTO(
                projection.getSupplierId(),
                projection.getSupplierName(),
                projection.getAcquisitionCount(),
                projection.getUnitsReceived(),
                projection.getTotalSpend(),
                projection.getNationalSpend(),
                projection.getImportationSpend());
    }

    /**
     * El avance de un intervalo al siguiente segun la granularidad. Se comparte
     * entre las tres series densificadas: la clave que devuelven las consultas
     * ya viene normalizada al primer dia del intervalo, de modo que sumar un mes
     * o un ano cae exactamente sobre la siguiente clave.
     */
    private static UnaryOperator<LocalDate> nextBucket(SalesPeriod period) {
        return switch (period.granularity()) {
            case DAY -> date -> date.plusDays(1);
            case MONTH -> date -> date.plusMonths(1);
            case YEAR -> date -> date.plusYears(1);
        };
    }

    // ── Ocupacion del taller ─────────────────────────────────────

    @Override
    public VehicleOccupancyResponseDTO getVehicleOccupancy(int days) {
        if (days < 1 || days > MAX_OCCUPANCY_DAYS) {
            throw new ValidationException(
                    "El periodo debe estar entre 1 y " + MAX_OCCUPANCY_DAYS + " dias");
        }

        UUID workshopId = SecurityUtils.getWorkshopId();
        LocalDate businessDate = LocalDate.now(clock);
        LocalDate from = businessDate.minusDays(days - 1L);

        // El instante actual, no el final del dia: es lo que hace que el ultimo
        // punto de la serie coincida con el KPI de vehiculos dentro en vez de
        // contradecirlo. Ver `findOccupancyByDay`.
        LocalDateTime seriesEnd = LocalDateTime.now(clock);

        List<VehicleOccupancyPointDTO> points = vehicleMovementRepository
                .findOccupancyByDay(workshopId, from, businessDate, seriesEnd)
                .stream()
                .map(AdminDashboardServiceImpl::toOccupancyPoint)
                .toList();

        long peak = points.stream()
                .mapToLong(VehicleOccupancyPointDTO::vehicles)
                .max()
                .orElse(0L);

        return new VehicleOccupancyResponseDTO(
                from,
                businessDate,
                vehicleMovementRepository.countByWorkshopIdAndExitIsNull(workshopId),
                peak,
                points);
    }

    // ── Mapeo ────────────────────────────────────────────────────

    private static BigDecimal amountOf(SalesSummaryProjection projection) {
        if (projection == null || projection.getAmount() == null) return BigDecimal.ZERO;
        return projection.getAmount();
    }

    private static ProductPerformanceDTO toProductPerformance(
            ProductPerformanceProjection projection) {
        return new ProductPerformanceDTO(
                projection.getProductId(),
                projection.getProductName(),
                projection.getQuantity(),
                projection.getGeneratedAmount());
    }

    private static ProductSalesDTO toProductSales(ProductSalesProjection projection) {
        return new ProductSalesDTO(
                projection.getProductId(),
                projection.getProductName(),
                projection.getQuantity());
    }

    /**
     * El nombre se compone aqui y no en SQL: es presentacion, y hacerlo en la
     * sentencia dejaria a la interfaz sin las partes por separado.
     */
    private static EmployeePerformanceDTO toEmployeePerformance(
            EmployeePerformanceProjection projection) {
        String name = (projection.getFirstName() + " " + projection.getLastName()).trim();
        return new EmployeePerformanceDTO(
                projection.getEmployeeId(), name, projection.getTotal());
    }

    private static CustomerPerformanceDTO toCustomerPerformance(
            CustomerPerformanceProjection projection) {
        return new CustomerPerformanceDTO(
                projection.getCustomerId(),
                projection.getCustomerName(),
                projection.getCustomerNit(),
                projection.getPurchaseCount(),
                projection.getGeneratedAmount());
    }

    private static VehicleOccupancyPointDTO toOccupancyPoint(
            OccupancyByDayProjection projection) {
        return new VehicleOccupancyPointDTO(
                LocalDate.parse(projection.getBucketDate()), projection.getVehicles());
    }

}
