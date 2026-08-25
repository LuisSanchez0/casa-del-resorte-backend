package com.cns.spring_house.vehiclemovement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.cns.spring_house.employee.entity.Employee;
import com.cns.spring_house.shared.security.CurrentUserService;
import com.cns.spring_house.useraccount.entity.UserAccount;
import com.cns.spring_house.vehiclemovement.dto.CurrentVehicleDTO;
import com.cns.spring_house.vehiclemovement.dto.EntriesByDayDTO;
import com.cns.spring_house.vehiclemovement.dto.PoliceDashboardResponseDTO;
import com.cns.spring_house.vehiclemovement.repository.VehicleMovementRepository;
import com.cns.spring_house.vehiclemovement.repository.projection.CurrentVehicleProjection;
import com.cns.spring_house.vehiclemovement.repository.projection.EntriesByDayProjection;
import com.cns.spring_house.vehiclemovement.repository.projection.TopPlateProjection;
import com.cns.spring_house.workshop.entity.Workshop;

/**
 * Semantica del dashboard: que se cuenta, con que limites de fecha y de quien.
 *
 * El reloj se fija a una fecha concreta en lugar de usar el del sistema: el KPI
 * de hoy depende del limite del dia y una prueba ejecutada a las 23:59 seria
 * intermitente.
 */
@ExtendWith(MockitoExtension.class)
class PoliceDashboardServiceImplTest {

    /** Fecha de negocio fijada para todas las pruebas. */
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 15);
    private static final LocalDateTime DAY_START = TODAY.atStartOfDay();
    private static final LocalDateTime DAY_END = TODAY.plusDays(1).atStartOfDay();
    private static final LocalDate TREND_FROM = TODAY.minusDays(29);

    @Mock
    private VehicleMovementRepository repository;

    @Mock
    private CurrentUserService currentUserService;

    private PoliceDashboardServiceImpl service;

    private final UUID workshopAId = UUID.randomUUID();
    /** Taller ajeno: nunca debe aparecer en ninguna consulta. */
    private final UUID workshopBId = UUID.randomUUID();
    private final UUID userAccountId = UUID.randomUUID();
    private final UUID employeeId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        Clock fixedClock = Clock.fixed(
                TODAY.atTime(10, 30).atZone(ZoneId.systemDefault()).toInstant(),
                ZoneId.systemDefault());
        service = new PoliceDashboardServiceImpl(repository, currentUserService, fixedClock);

        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject(userAccountId.toString())
                .claim("workshop", workshopAId.toString())
                .build();
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    // ── Utilidades ───────────────────────────────────────────────

    private void authenticatedWithEmployee() {
        Employee employee = Employee.builder()
                .id(employeeId)
                .workshop(Workshop.builder().id(workshopAId).build())
                .firstName("ANA")
                .lastName("LOPEZ")
                .build();
        when(currentUserService.getCurrentUserAccount())
                .thenReturn(UserAccount.builder().id(userAccountId).employee(employee).build());
    }

    private void authenticatedWithoutEmployee() {
        when(currentUserService.getCurrentUserAccount())
                .thenReturn(UserAccount.builder().id(userAccountId).build());
    }

    private static EntriesByDayProjection day(String date, long entries) {
        return new EntriesByDayProjection() {
            @Override
            public String getEntryDate() {
                return date;
            }

            @Override
            public long getEntries() {
                return entries;
            }
        };
    }

    private static TopPlateProjection plate(String plate, long entries) {
        return new TopPlateProjection() {
            @Override
            public String getPlate() {
                return plate;
            }

            @Override
            public long getEntries() {
                return entries;
            }
        };
    }

    private static CurrentVehicleProjection currentVehicle(
            UUID id, String plate, UUID employeeId, String firstName, String lastName) {
        return new CurrentVehicleProjection() {
            @Override
            public UUID getId() {
                return id;
            }

            @Override
            public String getPlate() {
                return plate;
            }

            @Override
            public LocalDateTime getEntry() {
                return TODAY.atTime(9, 42);
            }

            @Override
            public UUID getEmployeeId() {
                return employeeId;
            }

            @Override
            public String getFirstName() {
                return firstName;
            }

            @Override
            public String getLastName() {
                return lastName;
            }

            @Override
            public int getPhotos() {
                return 2;
            }

            @Override
            public String getNotes() {
                return "NOTAS";
            }
        };
    }

    /** Deja todas las consultas en cero para aislar lo que prueba cada test. */
    private void stubEmptyRepository() {
        lenient().when(repository.findEntriesByDay(any(), any(), any())).thenReturn(List.of());
        lenient().when(repository.findEntriesByDayForEmployee(any(), any(), any(), any()))
                .thenReturn(List.of());
        lenient().when(repository.findTopPlates(any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of());
        lenient().when(repository.findTopPlatesForEmployee(
                any(), any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of());
        lenient().when(repository.findCurrentVehicles(any())).thenReturn(List.of());
    }

    // ── Aislamiento por taller ───────────────────────────────────

    /**
     * El aislamiento se comprueba sobre el argumento real de cada consulta: con
     * un solo taller cualquier implementacion pasaria aunque no acotara nada.
     */
    @Test
    void everyQueryIsScopedToTheWorkshopOfTheToken() {
        authenticatedWithEmployee();
        stubEmptyRepository();

        service.getDashboard();

        verify(repository).countByWorkshopId(workshopAId);
        verify(repository).countByWorkshopIdAndExitIsNull(workshopAId);
        verify(repository).countDistinctPlatesByWorkshop(workshopAId);
        verify(repository).findCurrentVehicles(workshopAId);

        // El taller ajeno no se consulta por ninguna via.
        verify(repository, never()).countByWorkshopId(workshopBId);
        verify(repository, never()).countByWorkshopIdAndExitIsNull(workshopBId);
        verify(repository, never()).findCurrentVehicles(workshopBId);
    }

    /**
     * El ambito personal se resuelve desde la cuenta autenticada. No existe
     * ningun parametro de entrada por el que un cliente pueda pedir las cifras
     * de otro empleado: `getDashboard()` no recibe nada.
     */
    @Test
    void thePersonalScopeUsesTheEmployeeOfTheAuthenticatedAccount() {
        authenticatedWithEmployee();
        stubEmptyRepository();

        service.getDashboard();

        verify(repository).countByWorkshopIdAndEmployeeId(workshopAId, employeeId);
        verify(repository).countByWorkshopIdAndEmployeeIdAndExitIsNull(workshopAId, employeeId);
        verify(repository).countDistinctPlatesByWorkshopAndEmployee(workshopAId, employeeId);
    }

    // ── Semantica de las cifras ──────────────────────────────────

    /**
     * Las tres magnitudes son distintas y no deben confundirse: una placa que
     * entro ocho veces y sigue dentro suma 8 entradas, 1 placa unica y 1 vehiculo
     * actual.
     */
    @Test
    void repeatedVisitsCountAsEntriesButThePlateIsUniqueOnlyOnce() {
        authenticatedWithEmployee();
        stubEmptyRepository();
        when(repository.countByWorkshopId(workshopAId)).thenReturn(8L);
        when(repository.countDistinctPlatesByWorkshop(workshopAId)).thenReturn(1L);
        when(repository.countByWorkshopIdAndExitIsNull(workshopAId)).thenReturn(1L);

        var kpis = service.getDashboard().general().kpis();

        assertThat(kpis.historicalEntries()).isEqualTo(8L);
        assertThat(kpis.uniqueHistoricalPlates()).isEqualTo(1L);
        assertThat(kpis.currentVehiclesCount()).isEqualTo(1L);
    }

    /**
     * El KPI de hoy usa el intervalo semiabierto `[inicio de hoy, inicio de
     * manana)` derivado del reloj inyectado. Con un intervalo cerrado, una
     * entrada registrada a las 00:00:00 de manana se contaria como de hoy.
     */
    @Test
    void todayUsesTheBusinessDateOfTheInjectedClock() {
        authenticatedWithEmployee();
        stubEmptyRepository();

        PoliceDashboardResponseDTO dashboard = service.getDashboard();

        verify(repository).countByWorkshopIdAndEntryGreaterThanEqualAndEntryLessThan(
                workshopAId, DAY_START, DAY_END);
        assertThat(dashboard.businessDate()).isEqualTo(TODAY);
    }

    @Test
    void theTrendCoversThirtyDaysEndingToday() {
        authenticatedWithEmployee();
        stubEmptyRepository();

        PoliceDashboardResponseDTO dashboard = service.getDashboard();

        assertThat(dashboard.trendDays()).isEqualTo(30);
        assertThat(dashboard.trendFrom()).isEqualTo(TREND_FROM);
        verify(repository).findEntriesByDay(workshopAId, TREND_FROM.atStartOfDay(), DAY_END);

        List<EntriesByDayDTO> series = dashboard.general().entriesByDay();
        assertThat(series).hasSize(30);
        assertThat(series.get(0).date()).isEqualTo(TREND_FROM);
        assertThat(series.get(29).date()).isEqualTo(TODAY);
    }

    /**
     * Un dia sin entradas vale cero y NO se omite: omitirlo haria que la grafica
     * uniera dos fechas lejanas con una recta y aparentara actividad intermedia.
     */
    @Test
    void daysWithoutEntriesAreFilledWithZeroInsteadOfBeingSkipped() {
        authenticatedWithEmployee();
        stubEmptyRepository();
        when(repository.findEntriesByDay(eq(workshopAId), any(), any()))
                .thenReturn(List.of(day(TODAY.toString(), 4L), day(TREND_FROM.toString(), 2L)));

        List<EntriesByDayDTO> series = service.getDashboard().general().entriesByDay();

        assertThat(series).hasSize(30);
        assertThat(series.get(0).entries()).isEqualTo(2L);
        assertThat(series.get(29).entries()).isEqualTo(4L);
        // Todo lo intermedio es cero explicito, no un hueco.
        assertThat(series.subList(1, 29))
                .allSatisfy(point -> assertThat(point.entries()).isZero());
    }

    /** El limite del ranking se pide al SQL, no se recorta despues en Java. */
    @Test
    void topPlatesAsksTheDatabaseForTheLimitAndKeepsItsOrder() {
        authenticatedWithEmployee();
        stubEmptyRepository();
        when(repository.findTopPlates(workshopAId, 10)).thenReturn(List.of(
                plate("P123ABC", 9L),
                plate("C456DEF", 4L),
                plate("M789GHI", 4L)));

        var topPlates = service.getDashboard().general().topPlates();

        verify(repository).findTopPlates(workshopAId, 10);
        assertThat(topPlates).extracting(t -> t.plate())
                .containsExactly("P123ABC", "C456DEF", "M789GHI");
        assertThat(topPlates).extracting(t -> t.entries())
                .containsExactly(9L, 4L, 4L);
    }

    // ── Atribucion del actor ─────────────────────────────────────

    /**
     * Cuenta sin empleado asociado: el ambito personal se marca no disponible y
     * llega en ceros. La interfaz debe desactivarlo, no presentar los ceros como
     * si fueran las estadisticas reales de alguien.
     */
    @Test
    void withoutAnEmployeeThePersonalScopeIsUnavailableAndNeverQueried() {
        authenticatedWithoutEmployee();
        stubEmptyRepository();

        PoliceDashboardResponseDTO dashboard = service.getDashboard();

        assertThat(dashboard.personalScopeAvailable()).isFalse();
        assertThat(dashboard.mine().kpis().historicalEntries()).isZero();
        assertThat(dashboard.mine().topPlates()).isEmpty();
        // La tendencia conserva su eje aunque no haya ambito personal.
        assertThat(dashboard.mine().entriesByDay()).hasSize(30);

        verify(repository, never()).countByWorkshopIdAndEmployeeId(any(), any());
        verify(repository, never()).findTopPlatesForEmployee(
                any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    /**
     * Un movimiento sin actor (anterior a V23) no se atribuye a nadie: viaja con
     * `registeredBy` nulo en lugar de heredar el nombre del usuario que consulta.
     */
    @Test
    void aMovementWithoutActorIsNotAttributedToTheViewer() {
        authenticatedWithEmployee();
        stubEmptyRepository();
        UUID withActor = UUID.randomUUID();
        UUID withoutActor = UUID.randomUUID();
        when(repository.findCurrentVehicles(workshopAId)).thenReturn(List.of(
                currentVehicle(withActor, "P123ABC", employeeId, "ANA", "LOPEZ"),
                currentVehicle(withoutActor, "C456DEF", null, null, null)));

        List<CurrentVehicleDTO> vehicles = service.getDashboard().currentVehicles();

        assertThat(vehicles).hasSize(2);
        assertThat(vehicles.get(0).registeredBy()).isEqualTo("ANA LOPEZ");
        assertThat(vehicles.get(0).registeredByCurrentUser()).isTrue();
        assertThat(vehicles.get(1).registeredBy()).isNull();
        assertThat(vehicles.get(1).registeredByCurrentUser()).isFalse();
    }

    /** Las filas historicas sin actor se cuentan y se declaran, no se reparten. */
    @Test
    void unattributedHistoricalEntriesAreReported() {
        authenticatedWithEmployee();
        stubEmptyRepository();
        when(repository.countByWorkshopIdAndEmployeeIsNull(workshopAId)).thenReturn(12L);

        assertThat(service.getDashboard().unattributedHistoricalEntries()).isEqualTo(12L);
    }

    // ── Sin datos ────────────────────────────────────────────────

    /** Taller recien creado: ceros y arreglos vacios validos, nunca null. */
    @Test
    void anEmptyWorkshopReturnsZerosAndEmptyCollections() {
        authenticatedWithEmployee();
        stubEmptyRepository();

        PoliceDashboardResponseDTO dashboard = service.getDashboard();

        assertThat(dashboard.general().kpis().historicalEntries()).isZero();
        assertThat(dashboard.general().kpis().entriesToday()).isZero();
        assertThat(dashboard.general().kpis().currentVehiclesCount()).isZero();
        assertThat(dashboard.general().kpis().uniqueHistoricalPlates()).isZero();
        assertThat(dashboard.general().topPlates()).isEmpty();
        assertThat(dashboard.currentVehicles()).isEmpty();
        assertThat(dashboard.general().entriesByDay())
                .hasSize(30)
                .allSatisfy(point -> assertThat(point.entries()).isZero());
    }

}
