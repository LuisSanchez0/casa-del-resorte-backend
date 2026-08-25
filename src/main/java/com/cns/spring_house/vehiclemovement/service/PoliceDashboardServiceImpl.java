package com.cns.spring_house.vehiclemovement.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cns.spring_house.employee.entity.Employee;
import com.cns.spring_house.shared.security.CurrentUserService;
import com.cns.spring_house.shared.security.SecurityUtils;
import com.cns.spring_house.vehiclemovement.dto.CurrentVehicleDTO;
import com.cns.spring_house.vehiclemovement.dto.DashboardKpisDTO;
import com.cns.spring_house.vehiclemovement.dto.EntriesByDayDTO;
import com.cns.spring_house.vehiclemovement.dto.PoliceDashboardResponseDTO;
import com.cns.spring_house.vehiclemovement.dto.PoliceDashboardStatsDTO;
import com.cns.spring_house.vehiclemovement.dto.TopPlateDTO;
import com.cns.spring_house.vehiclemovement.repository.VehicleMovementRepository;
import com.cns.spring_house.vehiclemovement.repository.projection.CurrentVehicleProjection;
import com.cns.spring_house.vehiclemovement.repository.projection.EntriesByDayProjection;
import com.cns.spring_house.vehiclemovement.repository.projection.TopPlateProjection;

import lombok.RequiredArgsConstructor;

/**
 * Agregaciones del dashboard policial.
 *
 * Todo se cuenta en la base de datos: no se carga el historico de movimientos
 * para contarlo en Java. El taller sale siempre del token y el empleado del
 * ambito personal, de la cuenta autenticada; ninguno de los dos es un parametro
 * de entrada, asi que no hay forma de pedir las cifras de otro.
 */
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Service
public class PoliceDashboardServiceImpl implements PoliceDashboardService {

    /** Ventana de la grafica de tendencia, incluyendo hoy. */
    static final int TREND_DAYS = 30;

    /** Filas del ranking de placas. */
    static final int TOP_PLATES_LIMIT = 10;

    private final VehicleMovementRepository repository;
    private final CurrentUserService currentUserService;
    private final Clock clock;

    @Override
    public PoliceDashboardResponseDTO getDashboard() {
        UUID workshopId = SecurityUtils.getWorkshopId();

        // La fecha de negocio se calcula UNA vez y se reutiliza en los tres
        // limites: si cada consulta llamara a now() por su cuenta, una peticion
        // servida justo en el cambio de dia mezclaria dos fechas distintas.
        LocalDate businessDate = LocalDate.now(clock);
        LocalDateTime dayStart = businessDate.atStartOfDay();
        LocalDateTime dayEnd = businessDate.plusDays(1).atStartOfDay();
        LocalDate trendFrom = businessDate.minusDays(TREND_DAYS - 1L);
        LocalDateTime trendStart = trendFrom.atStartOfDay();

        UUID employeeId = resolveCurrentEmployeeId();

        PoliceDashboardStatsDTO general = buildGeneralStats(
                workshopId, dayStart, dayEnd, trendFrom, businessDate, trendStart);

        PoliceDashboardStatsDTO mine = employeeId == null
                ? emptyStats(trendFrom, businessDate)
                : buildPersonalStats(
                        workshopId, employeeId, dayStart, dayEnd,
                        trendFrom, businessDate, trendStart);

        List<CurrentVehicleDTO> currentVehicles = repository
                .findCurrentVehicles(workshopId)
                .stream()
                .map(projection -> toCurrentVehicle(projection, employeeId))
                .toList();

        return new PoliceDashboardResponseDTO(
                general,
                mine,
                currentVehicles,
                employeeId != null,
                repository.countByWorkshopIdAndEmployeeIsNull(workshopId),
                businessDate,
                trendFrom,
                TREND_DAYS,
                TOP_PLATES_LIMIT);
    }

    /**
     * El empleado autenticado, o null si la cuenta no tiene uno asociado. No se
     * usa {@code getCurrentEmployee()} porque ese metodo lanza: aqui la ausencia
     * de empleado no es un error, solo significa que el ambito personal no esta
     * disponible y la interfaz debe decirlo en lugar de mostrar ceros como si
     * fueran cifras reales.
     */
    private UUID resolveCurrentEmployeeId() {
        Employee employee = currentUserService.getCurrentUserAccount().getEmployee();
        return employee == null ? null : employee.getId();
    }

    private PoliceDashboardStatsDTO buildGeneralStats(
            UUID workshopId,
            LocalDateTime dayStart,
            LocalDateTime dayEnd,
            LocalDate trendFrom,
            LocalDate trendTo,
            LocalDateTime trendStart) {

        DashboardKpisDTO kpis = new DashboardKpisDTO(
                repository.countByWorkshopId(workshopId),
                repository.countByWorkshopIdAndEntryGreaterThanEqualAndEntryLessThan(
                        workshopId, dayStart, dayEnd),
                repository.countByWorkshopIdAndExitIsNull(workshopId),
                repository.countDistinctPlatesByWorkshop(workshopId));

        List<EntriesByDayDTO> entriesByDay = densify(
                repository.findEntriesByDay(workshopId, trendStart, dayEnd),
                trendFrom,
                trendTo);

        List<TopPlateDTO> topPlates = repository
                .findTopPlates(workshopId, TOP_PLATES_LIMIT)
                .stream()
                .map(PoliceDashboardServiceImpl::toTopPlate)
                .toList();

        return new PoliceDashboardStatsDTO(kpis, entriesByDay, topPlates);
    }

    private PoliceDashboardStatsDTO buildPersonalStats(
            UUID workshopId,
            UUID employeeId,
            LocalDateTime dayStart,
            LocalDateTime dayEnd,
            LocalDate trendFrom,
            LocalDate trendTo,
            LocalDateTime trendStart) {

        DashboardKpisDTO kpis = new DashboardKpisDTO(
                repository.countByWorkshopIdAndEmployeeId(workshopId, employeeId),
                repository.countByWorkshopIdAndEmployeeIdAndEntryGreaterThanEqualAndEntryLessThan(
                        workshopId, employeeId, dayStart, dayEnd),
                repository.countByWorkshopIdAndEmployeeIdAndExitIsNull(workshopId, employeeId),
                repository.countDistinctPlatesByWorkshopAndEmployee(workshopId, employeeId));

        List<EntriesByDayDTO> entriesByDay = densify(
                repository.findEntriesByDayForEmployee(
                        workshopId, employeeId, trendStart, dayEnd),
                trendFrom,
                trendTo);

        List<TopPlateDTO> topPlates = repository
                .findTopPlatesForEmployee(workshopId, employeeId, TOP_PLATES_LIMIT)
                .stream()
                .map(PoliceDashboardServiceImpl::toTopPlate)
                .toList();

        return new PoliceDashboardStatsDTO(kpis, entriesByDay, topPlates);
    }

    /**
     * Cuenta autenticada sin empleado: ceros y series vacias de datos, pero con
     * la tendencia igualmente densa para que la grafica conserve su eje.
     */
    private PoliceDashboardStatsDTO emptyStats(LocalDate trendFrom, LocalDate trendTo) {
        return new PoliceDashboardStatsDTO(
                new DashboardKpisDTO(0L, 0L, 0L, 0L),
                densify(List.of(), trendFrom, trendTo),
                List.of());
    }

    /**
     * Convierte las filas con actividad en una serie continua de un punto por
     * dia. Un dia sin entradas es informacion real —ese dia no entro nadie—, asi
     * que vale cero y no se omite: si se omitiera, la grafica uniria dos fechas
     * lejanas con una recta y aparentaria actividad intermedia.
     */
    private List<EntriesByDayDTO> densify(
            List<EntriesByDayProjection> rows, LocalDate from, LocalDate to) {

        Map<LocalDate, Long> byDate = new HashMap<>();
        for (EntriesByDayProjection row : rows) {
            byDate.put(LocalDate.parse(row.getEntryDate()), row.getEntries());
        }

        List<EntriesByDayDTO> series = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            series.add(new EntriesByDayDTO(date, byDate.getOrDefault(date, 0L)));
        }
        return series;
    }

    private static TopPlateDTO toTopPlate(TopPlateProjection projection) {
        return new TopPlateDTO(projection.getPlate(), projection.getEntries());
    }

    /**
     * El nombre del empleado solo se arma cuando la fila TIENE actor. Un
     * movimiento historico sin `employee_id` viaja con `registeredBy = null` y la
     * interfaz lo rotula como no registrado: no se le asigna un responsable.
     */
    private CurrentVehicleDTO toCurrentVehicle(
            CurrentVehicleProjection projection, UUID currentEmployeeId) {

        UUID employeeId = projection.getEmployeeId();
        String registeredBy = employeeId == null
                ? null
                : (projection.getFirstName() + " " + projection.getLastName()).trim();

        return new CurrentVehicleDTO(
                projection.getId(),
                projection.getPlate(),
                projection.getEntry(),
                registeredBy,
                employeeId != null && employeeId.equals(currentEmployeeId),
                projection.getPhotos(),
                projection.getNotes());
    }

}
