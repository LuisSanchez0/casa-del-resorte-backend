package com.cns.spring_house.dailylog.service;

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

import com.cns.spring_house.cashclosure.repository.CashClosureRepository;
import com.cns.spring_house.dailylog.dto.SecretaryActivityPointDTO;
import com.cns.spring_house.dailylog.dto.SecretaryDashboardClosureDTO;
import com.cns.spring_house.dailylog.dto.SecretaryDashboardPeriodDTO;
import com.cns.spring_house.dailylog.dto.SecretaryDashboardResponseDTO;
import com.cns.spring_house.dailylog.dto.SecretaryDashboardTodayDTO;
import com.cns.spring_house.dailylog.repository.DailyLogRepository;
import com.cns.spring_house.dailylog.repository.projection.DailyLogsByDayProjection;
import com.cns.spring_house.employee.entity.Employee;
import com.cns.spring_house.sale.repository.SaleRepository;
import com.cns.spring_house.sale.repository.projection.SalesByDayProjection;
import com.cns.spring_house.shared.security.CurrentUserService;
import com.cns.spring_house.shared.security.SecurityUtils;

import lombok.RequiredArgsConstructor;

/**
 * El panel de inicio de la secretaria: cuanto se facturo hoy en el taller,
 * cuantas bitacoras levanto ella, si la caja del dia ya se cerro y como ha ido
 * su actividad a lo largo del periodo.
 *
 * Tres decisiones sostienen la clase entera:
 *
 * 1. TODO se cuenta en la base de datos. Cuatro consultas agregadas fijas,
 *    independientemente de que el periodo sea de 7 o de 90 dias; nunca se
 *    descarga la bitacora ni el historico de ventas para contarlo en Java.
 *
 * 2. La fecha de negocio sale del reloj inyectado, el MISMO que usa el cierre
 *    de caja. Se calcula una sola vez por peticion: si cada consulta llamara a
 *    now() por su cuenta, una peticion servida justo en el cambio de dia
 *    mezclaria dos fechas.
 *
 * 3. Ventas y bitacoras son dimensiones DISTINTAS y aqui no se fuerza que
 *    cuadren. Las ventas se fechan por `completed_at` —cuando se emitio el
 *    documento— reutilizando exactamente la misma consulta que alimenta el
 *    dashboard de ventas y el cierre de caja; las bitacoras, por
 *    `performed_date`. Nada de esto se corrige ni se "concilia" en el servidor:
 *    se devuelven las dos cifras y su diferencia con signo.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SecretaryDashboardServiceImpl implements SecretaryDashboardService {

    /** Mismos tamaños que el dashboard de ventas: 7, 30 y 90 dias. */
    static final int DEFAULT_PERIOD_DAYS = 30;
    static final Set<Integer> ALLOWED_PERIOD_DAYS = Set.of(7, 30, 90);

    private final DailyLogRepository dailyLogRepository;
    private final SaleRepository saleRepository;
    private final CashClosureRepository cashClosureRepository;
    private final CurrentUserService currentUserService;
    private final Clock clock;

    @Override
    public SecretaryDashboardResponseDTO getDashboard(Integer days) {
        UUID workshopId = SecurityUtils.getWorkshopId();
        int periodDays = normalizePeriodDays(days);

        LocalDate businessDate = LocalDate.now(clock);
        LocalDate from = businessDate.minusDays(periodDays - 1L);
        // Las ventas se agregan sobre `completed_at`, que es un instante: la
        // ventana es semiabierta y su limite superior es el arranque de mañana,
        // nunca un 23:59:59.999 artificial. Es el mismo criterio del cierre de
        // caja, para que "hoy" signifique lo mismo en las dos pantallas.
        LocalDateTime windowStart = from.atStartOfDay();
        LocalDateTime windowEnd = businessDate.plusDays(1).atStartOfDay();

        UUID employeeId = resolveCurrentEmployeeId();

        List<SecretaryActivityPointDTO> series = densify(
                saleRepository.findSalesByDay(workshopId, windowStart, windowEnd),
                employeeId == null
                        ? List.<DailyLogsByDayProjection>of()
                        : dailyLogRepository.findMyDailyLogsByDay(
                                workshopId, employeeId, from, businessDate),
                from,
                businessDate);

        return new SecretaryDashboardResponseDTO(
                businessDate,
                employeeId != null,
                today(workshopId, businessDate, series),
                period(series, from, businessDate, periodDays),
                series);
    }

    private int normalizePeriodDays(Integer requested) {
        return requested != null && ALLOWED_PERIOD_DAYS.contains(requested)
                ? requested
                : DEFAULT_PERIOD_DAYS;
    }

    /**
     * El empleado autenticado, o null si la cuenta no tiene uno asociado. No se
     * usa {@code getCurrentEmployee()} porque ese metodo lanza: aqui la
     * ausencia de empleado no es un error, solo significa que no hay bitacoras
     * atribuibles a quien mira la pantalla.
     */
    private UUID resolveCurrentEmployeeId() {
        Employee employee = currentUserService.getCurrentUserAccount().getEmployee();
        return employee == null ? null : employee.getId();
    }

    // ── Hoy ──────────────────────────────────────────────────────

    /**
     * Las cifras del dia. Ventas y bitacoras propias se LEEN del ultimo punto
     * de la serie en vez de volver a consultarlas: la serie termina siempre en
     * la fecha de negocio y su ultimo tramo es exactamente la ventana de hoy,
     * asi que repetir la consulta solo añadiria dos viajes y la posibilidad de
     * que el KPI y la grafica se contradigan.
     *
     * Lo unico que si necesita consulta propia es la bitacora del taller
     * entero, que no aparece en la serie porque la serie es personal.
     */
    private SecretaryDashboardTodayDTO today(
            UUID workshopId,
            LocalDate businessDate,
            List<SecretaryActivityPointDTO> series) {

        SecretaryActivityPointDTO todayPoint = series.get(series.size() - 1);
        long workshopDailyLogs = dailyLogRepository
                .countByWorkshopIdAndPerformedDate(workshopId, businessDate);

        return new SecretaryDashboardTodayDTO(
                todayPoint.salesCount(),
                workshopDailyLogs,
                todayPoint.myDailyLogsCount(),
                todayPoint.salesCount() - todayPoint.myDailyLogsCount(),
                closure(workshopId, businessDate));
    }

    /**
     * Que no exista fila de cierre para la fecha es el estado normal de la
     * mañana, no un fallo: se responde {@code completed = false} dentro de un
     * 200 y la pantalla ofrece cerrar, en vez de enseñar un error.
     */
    private SecretaryDashboardClosureDTO closure(UUID workshopId, LocalDate businessDate) {
        return cashClosureRepository
                .findStampByWorkshopIdAndBusinessDate(workshopId, businessDate)
                .map(stamp -> new SecretaryDashboardClosureDTO(
                        true, stamp.getId(), stamp.getClosedAt()))
                .orElseGet(SecretaryDashboardClosureDTO::open);
    }

    // ── Periodo ──────────────────────────────────────────────────

    /**
     * El resumen se agrega sobre la serie ya construida, que tiene como mucho
     * noventa elementos. Recorrerla en memoria no es "contar en el cliente":
     * los conteos ya los hizo la base de datos, aqui solo se suman noventa
     * numeros que de todas formas hay que devolver.
     */
    private SecretaryDashboardPeriodDTO period(
            List<SecretaryActivityPointDTO> series,
            LocalDate from,
            LocalDate to,
            int periodDays) {

        long totalSales = 0;
        long myDailyLogs = 0;
        int activeDays = 0;
        LocalDate bestDay = null;
        long bestDayCount = 0;

        for (SecretaryActivityPointDTO point : series) {
            totalSales += point.salesCount();
            myDailyLogs += point.myDailyLogsCount();

            if (point.myDailyLogsCount() > 0) {
                activeDays++;
            }
            // Estricto y no >=: ante un empate gana el dia mas antiguo, que es
            // el primero que se recorre. Con >= ganaria el mas reciente, y el
            // "mejor dia" cambiaria de fecha cada vez que se repitiera la marca
            // sin haberla superado.
            if (point.myDailyLogsCount() > bestDayCount) {
                bestDayCount = point.myDailyLogsCount();
                bestDay = point.date();
            }
        }

        BigDecimal average = BigDecimal.valueOf(myDailyLogs)
                .divide(BigDecimal.valueOf(periodDays), 2, RoundingMode.HALF_UP);

        return new SecretaryDashboardPeriodDTO(
                from, to, periodDays, totalSales, myDailyLogs,
                activeDays, average, bestDay, bestDayCount);
    }

    // ── Serie ────────────────────────────────────────────────────

    /**
     * Une las dos agregaciones —que solo traen dias CON actividad— en una serie
     * continua de un punto por dia. Un dia sin ventas ni bitacoras vale cero y
     * esta: omitirlo uniria dos fechas lejanas con una recta.
     *
     * La fecha de las ventas llega como texto `YYYY-MM-DD` porque la consulta
     * es nativa (ver {@code SalesByDayProjection}); la de las bitacoras llega
     * ya como {@code LocalDate}, porque su columna es DATE.
     */
    private List<SecretaryActivityPointDTO> densify(
            List<SalesByDayProjection> saleRows,
            List<DailyLogsByDayProjection> logRows,
            LocalDate from,
            LocalDate to) {

        Map<LocalDate, Long> salesByDate = new HashMap<>();
        for (SalesByDayProjection row : saleRows) {
            salesByDate.put(LocalDate.parse(row.getSaleDate()), row.getSalesCount());
        }

        Map<LocalDate, Long> logsByDate = new HashMap<>();
        for (DailyLogsByDayProjection row : logRows) {
            logsByDate.put(row.getLogDate(), row.getLogCount());
        }

        List<SecretaryActivityPointDTO> series = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            series.add(new SecretaryActivityPointDTO(
                    date,
                    salesByDate.getOrDefault(date, 0L),
                    logsByDate.getOrDefault(date, 0L)));
        }
        return series;
    }

}
