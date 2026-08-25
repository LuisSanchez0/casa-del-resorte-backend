package com.cns.spring_house.sale.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cns.spring_house.employee.entity.Employee;
import com.cns.spring_house.sale.dto.PaymentMethodStatDTO;
import com.cns.spring_house.sale.dto.SaleDashboardKpisDTO;
import com.cns.spring_house.sale.dto.SaleDashboardResponseDTO;
import com.cns.spring_house.sale.dto.SaleDashboardStatsDTO;
import com.cns.spring_house.sale.dto.SaleTrendPointDTO;
import com.cns.spring_house.sale.dto.TopProductDTO;
import com.cns.spring_house.sale.entity.InvoiceType;
import com.cns.spring_house.sale.entity.PaymentMethod;
import com.cns.spring_house.sale.entity.PaymentStatus;
import com.cns.spring_house.sale.entity.SaleStatus;
import com.cns.spring_house.sale.repository.SaleRepository;
import com.cns.spring_house.sale.repository.projection.PaymentMethodStatProjection;
import com.cns.spring_house.sale.repository.projection.SalesByDayProjection;
import com.cns.spring_house.sale.repository.projection.SalesSummaryProjection;
import com.cns.spring_house.sale.repository.projection.TopProductProjection;
import com.cns.spring_house.shared.security.CurrentUserService;
import com.cns.spring_house.shared.security.SecurityUtils;

import lombok.RequiredArgsConstructor;

/**
 * Agregaciones del dashboard de ventas.
 *
 * Todo se cuenta en la base de datos: nunca se descarga el historico de
 * ventas para sumarlo en Java. El taller sale siempre del token y el empleado
 * del ambito personal, de la cuenta autenticada; ninguno de los dos es un
 * parametro de entrada, asi que no hay forma de pedir las cifras de otro.
 *
 * `CANCELLED` y `REVERSED` quedan fuera de toda cifra de exito: una venta
 * revertida no debe seguir inflando ingresos ni el ranking de productos solo
 * porque en algun momento se completo.
 */
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Service
public class SaleDashboardServiceImpl implements SaleDashboardService {

    static final int DEFAULT_TREND_DAYS = 30;
    static final Set<Integer> ALLOWED_TREND_DAYS = Set.of(7, 30, 90);
    static final int TOP_PRODUCTS_LIMIT = 10;

    private final SaleRepository repository;
    private final CurrentUserService currentUserService;
    private final Clock clock;

    @Override
    public SaleDashboardResponseDTO getDashboard(Integer days) {
        UUID workshopId = SecurityUtils.getWorkshopId();
        int trendDays = normalizeTrendDays(days);

        // La fecha de negocio se calcula UNA vez y se reutiliza en todos los
        // limites: si cada consulta llamara a now() por su cuenta, una peticion
        // servida justo en el cambio de dia mezclaria dos fechas distintas.
        LocalDate businessDate = LocalDate.now(clock);
        LocalDateTime dayStart = businessDate.atStartOfDay();
        LocalDateTime dayEnd = businessDate.plusDays(1).atStartOfDay();
        LocalDate trendFrom = businessDate.minusDays(trendDays - 1L);
        LocalDateTime trendStart = trendFrom.atStartOfDay();

        UUID employeeId = resolveCurrentEmployeeId();

        SaleDashboardStatsDTO general = buildStats(
                workshopId, null, dayStart, dayEnd, trendFrom, businessDate, trendStart);

        SaleDashboardStatsDTO mine = employeeId == null
                ? emptyStats(trendFrom, businessDate)
                : buildStats(
                        workshopId, employeeId, dayStart, dayEnd,
                        trendFrom, businessDate, trendStart);

        return new SaleDashboardResponseDTO(
                general, mine, employeeId != null, businessDate, trendFrom, trendDays);
    }

    private int normalizeTrendDays(Integer requested) {
        return requested != null && ALLOWED_TREND_DAYS.contains(requested)
                ? requested
                : DEFAULT_TREND_DAYS;
    }

    /**
     * El empleado autenticado, o null si la cuenta no tiene uno asociado. No se
     * usa {@code getCurrentEmployee()} porque ese metodo lanza: aqui la
     * ausencia de empleado no es un error, solo significa que el ambito
     * personal no esta disponible.
     */
    private UUID resolveCurrentEmployeeId() {
        Employee employee = currentUserService.getCurrentUserAccount().getEmployee();
        return employee == null ? null : employee.getId();
    }

    private SaleDashboardStatsDTO buildStats(
            UUID workshopId,
            UUID employeeId,
            LocalDateTime dayStart,
            LocalDateTime dayEnd,
            LocalDate trendFrom,
            LocalDate trendTo,
            LocalDateTime trendStart) {

        boolean personal = employeeId != null;

        long completedTotal = personal
                ? repository.countByWorkshopIdAndEmployeeIdAndStatus(
                        workshopId, employeeId, SaleStatus.COMPLETED)
                : repository.countByWorkshopIdAndStatus(workshopId, SaleStatus.COMPLETED);

        long pending = personal
                ? repository.countByWorkshopIdAndEmployeeIdAndStatus(
                        workshopId, employeeId, SaleStatus.PENDING)
                : repository.countByWorkshopIdAndStatus(workshopId, SaleStatus.PENDING);

        long pendingExchange = personal
                ? repository.countByWorkshopIdAndEmployeeIdAndStatusAndInvoiceTypeAndPaymentStatus(
                        workshopId, employeeId, SaleStatus.COMPLETED,
                        InvoiceType.EXCHANGE_INVOICE, PaymentStatus.PENDING)
                : repository.countByWorkshopIdAndStatusAndInvoiceTypeAndPaymentStatus(
                        workshopId, SaleStatus.COMPLETED,
                        InvoiceType.EXCHANGE_INVOICE, PaymentStatus.PENDING);

        SalesSummaryProjection today = personal
                ? repository.summarizeCompletedSalesForEmployee(
                        workshopId, employeeId, dayStart, dayEnd)
                : repository.summarizeCompletedSales(workshopId, dayStart, dayEnd);

        long completedToday = today == null ? 0L : today.getSalesCount();
        BigDecimal amountToday = today == null || today.getAmount() == null
                ? BigDecimal.ZERO
                : today.getAmount();
        BigDecimal averageTicket = completedToday == 0L
                ? null
                : amountToday.divide(BigDecimal.valueOf(completedToday), 2, RoundingMode.HALF_UP);

        SaleDashboardKpisDTO kpis = new SaleDashboardKpisDTO(
                completedTotal, completedToday, pending, pendingExchange,
                amountToday, averageTicket);

        List<SalesByDayProjection> trendRows = personal
                ? repository.findSalesByDayForEmployee(
                        workshopId, employeeId, trendStart, dayEnd)
                : repository.findSalesByDay(workshopId, trendStart, dayEnd);
        List<SaleTrendPointDTO> trend = densify(trendRows, trendFrom, trendTo);

        List<TopProductProjection> topProductRows = personal
                ? repository.findTopProductsTodayForEmployee(
                        workshopId, employeeId, dayStart, dayEnd, TOP_PRODUCTS_LIMIT)
                : repository.findTopProductsToday(
                        workshopId, dayStart, dayEnd, TOP_PRODUCTS_LIMIT);
        List<TopProductDTO> topProducts = topProductRows.stream()
                .map(SaleDashboardServiceImpl::toTopProduct)
                .toList();

        List<PaymentMethodStatProjection> paymentRows = personal
                ? repository.findPaymentMethodsBetweenForEmployee(
                        workshopId, employeeId, dayStart, dayEnd)
                : repository.findPaymentMethodsBetween(workshopId, dayStart, dayEnd);
        List<PaymentMethodStatDTO> paymentMethods = paymentRows.stream()
                .map(SaleDashboardServiceImpl::toPaymentMethodStat)
                .toList();

        return new SaleDashboardStatsDTO(kpis, trend, topProducts, paymentMethods);
    }

    /**
     * Cuenta autenticada sin empleado: ceros y series vacias, pero con la
     * tendencia igualmente densa para que la grafica conserve su eje.
     */
    private SaleDashboardStatsDTO emptyStats(LocalDate trendFrom, LocalDate trendTo) {
        return new SaleDashboardStatsDTO(
                new SaleDashboardKpisDTO(0L, 0L, 0L, 0L, BigDecimal.ZERO, null),
                densify(List.of(), trendFrom, trendTo),
                List.of(),
                List.of());
    }

    /**
     * Convierte las filas con actividad en una serie continua de un punto por
     * dia. Un dia sin ventas es informacion real, asi que vale cero y no se
     * omite: omitirlo uniria dos fechas lejanas con una recta y aparentaria
     * actividad intermedia.
     */
    private List<SaleTrendPointDTO> densify(
            List<SalesByDayProjection> rows, LocalDate from, LocalDate to) {

        Map<LocalDate, SalesByDayProjection> byDate = new HashMap<>();
        for (SalesByDayProjection row : rows) {
            byDate.put(LocalDate.parse(row.getSaleDate()), row);
        }

        List<SaleTrendPointDTO> series = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            SalesByDayProjection row = byDate.get(date);
            series.add(new SaleTrendPointDTO(
                    date,
                    row == null ? 0L : row.getSalesCount(),
                    row == null || row.getAmount() == null ? BigDecimal.ZERO : row.getAmount()));
        }
        return series;
    }

    private static TopProductDTO toTopProduct(TopProductProjection projection) {
        return new TopProductDTO(
                projection.getProductId(),
                projection.getProductName(),
                projection.getQuantity(),
                projection.getAmount() == null ? BigDecimal.ZERO : projection.getAmount());
    }

    private static PaymentMethodStatDTO toPaymentMethodStat(
            PaymentMethodStatProjection projection) {
        return new PaymentMethodStatDTO(
                PaymentMethod.valueOf(projection.getMethod()),
                projection.getSaleCount(),
                projection.getAmount() == null ? BigDecimal.ZERO : projection.getAmount());
    }

}
