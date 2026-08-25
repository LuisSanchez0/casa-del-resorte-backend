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

import com.cns.spring_house.dashboard.dto.AdminDashboardOverviewResponseDTO;
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
