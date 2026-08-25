package com.cns.spring_house.dailylog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
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

import com.cns.spring_house.cashclosure.repository.CashClosureRepository;
import com.cns.spring_house.cashclosure.repository.projection.CashClosureStampProjection;
import com.cns.spring_house.dailylog.dto.SecretaryActivityPointDTO;
import com.cns.spring_house.dailylog.dto.SecretaryDashboardResponseDTO;
import com.cns.spring_house.dailylog.repository.DailyLogRepository;
import com.cns.spring_house.dailylog.repository.projection.DailyLogsByDayProjection;
import com.cns.spring_house.employee.entity.Employee;
import com.cns.spring_house.sale.repository.SaleRepository;
import com.cns.spring_house.sale.repository.projection.SalesByDayProjection;
import com.cns.spring_house.shared.security.CurrentUserService;
import com.cns.spring_house.useraccount.entity.UserAccount;
import com.cns.spring_house.workshop.entity.Workshop;

/**
 * Semantica del dashboard de secretaria: que fecha manda, que se compara con
 * que y de quien son las cifras.
 *
 * El reloj es fijo porque todo el panel gira alrededor del limite del dia: una
 * prueba ejecutada a las 23:59 seria intermitente.
 */
@ExtendWith(MockitoExtension.class)
class SecretaryDashboardServiceImplTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 15);

    @Mock
    private DailyLogRepository dailyLogRepository;

    @Mock
    private SaleRepository saleRepository;

    @Mock
    private CashClosureRepository cashClosureRepository;

    @Mock
    private CurrentUserService currentUserService;

    private SecretaryDashboardServiceImpl service;

    private final UUID workshopId = UUID.randomUUID();
    private final UUID userAccountId = UUID.randomUUID();
    private final UUID employeeId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        Clock fixedClock = Clock.fixed(
                TODAY.atTime(10, 30).atZone(ZoneId.systemDefault()).toInstant(),
                ZoneId.systemDefault());
        service = new SecretaryDashboardServiceImpl(
                dailyLogRepository, saleRepository, cashClosureRepository,
                currentUserService, fixedClock);

        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject(userAccountId.toString())
                .claim("workshop", workshopId.toString())
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
                .workshop(Workshop.builder().id(workshopId).build())
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

    private static SalesByDayProjection salesOn(LocalDate date, long count) {
        return new SalesByDayProjection() {
            @Override
            public String getSaleDate() {
                return date.toString();
            }

            @Override
            public long getSalesCount() {
                return count;
            }

            @Override
            public BigDecimal getAmount() {
                return BigDecimal.valueOf(count * 100L);
            }
        };
    }

    private static DailyLogsByDayProjection logsOn(LocalDate date, long count) {
        return new DailyLogsByDayProjection() {
            @Override
            public LocalDate getLogDate() {
                return date;
            }

            @Override
            public long getLogCount() {
                return count;
            }
        };
    }

    private static CashClosureStampProjection stamp(UUID id, LocalDateTime closedAt) {
        return new CashClosureStampProjection() {
            @Override
            public UUID getId() {
                return id;
            }

            @Override
            public LocalDateTime getClosedAt() {
                return closedAt;
            }
        };
    }

    /** Cierre abierto y sin bitacoras de taller, salvo que la prueba diga otra cosa. */
    private void quietWorkshop() {
        lenient().when(dailyLogRepository.countByWorkshopIdAndPerformedDate(any(), any()))
                .thenReturn(0L);
        lenient().when(cashClosureRepository
                        .findStampByWorkshopIdAndBusinessDate(any(), any()))
                .thenReturn(Optional.empty());
    }

    private void sales(List<SalesByDayProjection> rows) {
        lenient().when(saleRepository.findSalesByDay(any(), any(), any())).thenReturn(rows);
    }

    private void myLogs(List<DailyLogsByDayProjection> rows) {
        lenient().when(dailyLogRepository.findMyDailyLogsByDay(any(), any(), any(), any()))
                .thenReturn(rows);
    }

    // ── Fecha de negocio ─────────────────────────────────────────

    /**
     * La fecha del panel la fija el reloj del servidor, no el cliente: no hay
     * ningun parametro que la pueda mover.
     */
    @Test
    void businessDateComesFromTheServerClock() {
        authenticatedWithEmployee();
        quietWorkshop();
        sales(List.of());
        myLogs(List.of());

        SecretaryDashboardResponseDTO dashboard = service.getDashboard(7);

        assertThat(dashboard.businessDate()).isEqualTo(TODAY);
        assertThat(dashboard.period().to()).isEqualTo(TODAY);
        assertThat(dashboard.series().get(dashboard.series().size() - 1).date()).isEqualTo(TODAY);
    }

    /**
     * Las ventas se agregan sobre `completed_at`, que es un instante: la ventana
     * es `[inicio del primer dia, arranque de mañana)`. Las bitacoras van sobre
     * `performed_date`, que es DATE, con los dos extremos incluidos.
     */
    @Test
    void salesUseAHalfOpenInstantWindowAndDailyLogsAnInclusiveDateRange() {
        authenticatedWithEmployee();
        quietWorkshop();
        sales(List.of());
        myLogs(List.of());

        service.getDashboard(7);

        LocalDate from = TODAY.minusDays(6);
        verify(saleRepository).findSalesByDay(
                workshopId, from.atStartOfDay(), TODAY.plusDays(1).atStartOfDay());
        verify(dailyLogRepository).findMyDailyLogsByDay(workshopId, employeeId, from, TODAY);
    }

    // ── Tamaño del periodo ───────────────────────────────────────

    @Test
    void periodDefaultsToThirtyDaysWhenNotRequested() {
        authenticatedWithEmployee();
        quietWorkshop();
        sales(List.of());
        myLogs(List.of());

        SecretaryDashboardResponseDTO dashboard = service.getDashboard(null);

        assertThat(dashboard.period().days()).isEqualTo(30);
        assertThat(dashboard.period().from()).isEqualTo(TODAY.minusDays(29));
        assertThat(dashboard.series()).hasSize(30);
    }

    @Test
    void sevenThirtyAndNinetyAreTheAcceptedPeriods() {
        authenticatedWithEmployee();
        quietWorkshop();
        sales(List.of());
        myLogs(List.of());

        assertThat(service.getDashboard(7).series()).hasSize(7);
        assertThat(service.getDashboard(30).series()).hasSize(30);
        assertThat(service.getDashboard(90).series()).hasSize(90);
    }

    /** Un `days` cualquiera en la URL no rompe la pantalla: cae al valor por defecto. */
    @Test
    void anUnsupportedPeriodFallsBackToTheDefault() {
        authenticatedWithEmployee();
        quietWorkshop();
        sales(List.of());
        myLogs(List.of());

        assertThat(service.getDashboard(365).period().days()).isEqualTo(30);
        assertThat(service.getDashboard(0).period().days()).isEqualTo(30);
        assertThat(service.getDashboard(-5).period().days()).isEqualTo(30);
    }

    // ── Serie densa ──────────────────────────────────────────────

    /**
     * Un dia sin actividad vale cero y ESTA. Omitirlo uniria dos fechas lejanas
     * con una recta y aparentaria actividad que nunca existio.
     */
    @Test
    void daysWithoutActivityAreZeroFilledInsteadOfSkipped() {
        authenticatedWithEmployee();
        quietWorkshop();
        sales(List.of(salesOn(TODAY.minusDays(2), 3), salesOn(TODAY, 5)));
        myLogs(List.of(logsOn(TODAY.minusDays(2), 1)));

        List<SecretaryActivityPointDTO> series = service.getDashboard(7).series();

        assertThat(series).hasSize(7);
        assertThat(series).extracting(SecretaryActivityPointDTO::date)
                .containsExactly(
                        TODAY.minusDays(6), TODAY.minusDays(5), TODAY.minusDays(4),
                        TODAY.minusDays(3), TODAY.minusDays(2), TODAY.minusDays(1), TODAY);
        assertThat(series).extracting(SecretaryActivityPointDTO::salesCount)
                .containsExactly(0L, 0L, 0L, 0L, 3L, 0L, 5L);
        assertThat(series).extracting(SecretaryActivityPointDTO::myDailyLogsCount)
                .containsExactly(0L, 0L, 0L, 0L, 1L, 0L, 0L);
    }

    // ── Hoy ──────────────────────────────────────────────────────

    /**
     * El KPI de hoy sale del ultimo punto de la serie y no de una consulta
     * aparte: si fueran dos lecturas distintas, la tarjeta y la grafica podrian
     * contradecirse.
     */
    @Test
    void todayMatchesTheLastPointOfTheSeries() {
        authenticatedWithEmployee();
        quietWorkshop();
        sales(List.of(salesOn(TODAY, 9)));
        myLogs(List.of(logsOn(TODAY, 4)));

        SecretaryDashboardResponseDTO dashboard = service.getDashboard(7);
        SecretaryActivityPointDTO last = dashboard.series().get(dashboard.series().size() - 1);

        assertThat(dashboard.today().salesCount()).isEqualTo(9);
        assertThat(dashboard.today().myDailyLogsCount()).isEqualTo(4);
        assertThat(last.salesCount()).isEqualTo(9);
        assertThat(last.myDailyLogsCount()).isEqualTo(4);
    }

    @Test
    void gapIsSalesMinusMyDailyLogs() {
        authenticatedWithEmployee();
        quietWorkshop();
        sales(List.of(salesOn(TODAY, 9)));
        myLogs(List.of(logsOn(TODAY, 4)));

        assertThat(service.getDashboard(7).today().gap()).isEqualTo(5);
    }

    /**
     * Poner al dia bitacoras de facturas de dias anteriores deja mas registros
     * que ventas emitidas hoy. Es un estado legitimo y la diferencia se devuelve
     * con signo, sin recortarla a cero.
     */
    @Test
    void gapIsNegativeWhenThereAreMoreDailyLogsThanSalesToday() {
        authenticatedWithEmployee();
        quietWorkshop();
        sales(List.of(salesOn(TODAY, 2)));
        myLogs(List.of(logsOn(TODAY, 6)));

        assertThat(service.getDashboard(7).today().gap()).isEqualTo(-4);
    }

    /**
     * La bitacora del taller entero se cuenta aparte de la propia: sin ella, la
     * diferencia entre ventas y bitacoras propias no se podria leer sin suponer
     * que la persona autenticada es la unica que registra.
     */
    @Test
    void workshopDailyLogsAreCountedSeparatelyFromTheOwnOnes() {
        authenticatedWithEmployee();
        when(dailyLogRepository.countByWorkshopIdAndPerformedDate(workshopId, TODAY))
                .thenReturn(7L);
        when(cashClosureRepository.findStampByWorkshopIdAndBusinessDate(any(), any()))
                .thenReturn(Optional.empty());
        sales(List.of(salesOn(TODAY, 9)));
        myLogs(List.of(logsOn(TODAY, 4)));

        SecretaryDashboardResponseDTO dashboard = service.getDashboard(7);

        assertThat(dashboard.today().workshopDailyLogsCount()).isEqualTo(7);
        assertThat(dashboard.today().myDailyLogsCount()).isEqualTo(4);
    }

    // ── Ambito personal ──────────────────────────────────────────

    @Test
    void personalCountsAreScopedToTheAuthenticatedEmployee() {
        authenticatedWithEmployee();
        quietWorkshop();
        sales(List.of());
        myLogs(List.of());

        service.getDashboard(30);

        verify(dailyLogRepository).findMyDailyLogsByDay(
                eq(workshopId), eq(employeeId), any(), any());
    }

    /**
     * Una cuenta sin empleado no tiene bitacoras atribuibles. No se consulta la
     * serie personal —no hay a quien filtrar—, se responde en cero y se avisa
     * de que el ambito no esta disponible, en vez de enseñar ceros como si
     * fueran una jornada sin trabajo.
     */
    @Test
    void anAccountWithoutEmployeeDisablesThePersonalScopeInsteadOfShowingZeros() {
        authenticatedWithoutEmployee();
        quietWorkshop();
        sales(List.of(salesOn(TODAY, 5)));

        SecretaryDashboardResponseDTO dashboard = service.getDashboard(7);

        assertThat(dashboard.personalScopeAvailable()).isFalse();
        assertThat(dashboard.today().myDailyLogsCount()).isZero();
        assertThat(dashboard.today().salesCount()).isEqualTo(5);
        assertThat(dashboard.period().myDailyLogs()).isZero();
        verify(dailyLogRepository, never()).findMyDailyLogsByDay(any(), any(), any(), any());
    }

    // ── Cierre de caja ───────────────────────────────────────────

    /** Que la caja siga abierta es el estado normal de la mañana, no un fallo. */
    @Test
    void anOpenCashRegisterIsReportedAsANormalStateWithoutIdentifiers() {
        authenticatedWithEmployee();
        quietWorkshop();
        sales(List.of());
        myLogs(List.of());

        SecretaryDashboardResponseDTO dashboard = service.getDashboard(7);

        assertThat(dashboard.today().closure().completed()).isFalse();
        assertThat(dashboard.today().closure().closureId()).isNull();
        assertThat(dashboard.today().closure().closedAt()).isNull();
    }

    @Test
    void aClosedCashRegisterReportsItsDocumentAndTheInstantItWasConfirmed() {
        authenticatedWithEmployee();
        UUID closureId = UUID.randomUUID();
        LocalDateTime closedAt = TODAY.atTime(19, 45);
        when(dailyLogRepository.countByWorkshopIdAndPerformedDate(any(), any())).thenReturn(0L);
        when(cashClosureRepository.findStampByWorkshopIdAndBusinessDate(workshopId, TODAY))
                .thenReturn(Optional.of(stamp(closureId, closedAt)));
        sales(List.of());
        myLogs(List.of());

        SecretaryDashboardResponseDTO dashboard = service.getDashboard(7);

        assertThat(dashboard.today().closure().completed()).isTrue();
        assertThat(dashboard.today().closure().closureId()).isEqualTo(closureId);
        assertThat(dashboard.today().closure().closedAt()).isEqualTo(closedAt);
    }

    // ── Resumen del periodo ──────────────────────────────────────

    @Test
    void periodTotalsAggregateTheWholeSeries() {
        authenticatedWithEmployee();
        quietWorkshop();
        sales(List.of(salesOn(TODAY.minusDays(2), 4), salesOn(TODAY, 6)));
        myLogs(List.of(
                logsOn(TODAY.minusDays(4), 2),
                logsOn(TODAY.minusDays(2), 5),
                logsOn(TODAY, 3)));

        SecretaryDashboardResponseDTO dashboard = service.getDashboard(7);

        assertThat(dashboard.period().totalSales()).isEqualTo(10);
        assertThat(dashboard.period().myDailyLogs()).isEqualTo(10);
        assertThat(dashboard.period().activeDays()).isEqualTo(3);
        assertThat(dashboard.period().bestDay()).isEqualTo(TODAY.minusDays(2));
        assertThat(dashboard.period().bestDayCount()).isEqualTo(5);
    }

    /**
     * El promedio divide entre los dias del PERIODO, no entre los dias con
     * actividad: la pregunta es "cuantas bitacoras por dia salen en estos siete
     * dias", y el denominador viaja en la respuesta para que no haya que
     * adivinarlo.
     */
    @Test
    void theAverageDividesByThePeriodLengthAndSaysSo() {
        authenticatedWithEmployee();
        quietWorkshop();
        sales(List.of());
        myLogs(List.of(logsOn(TODAY.minusDays(2), 5), logsOn(TODAY, 2)));

        SecretaryDashboardResponseDTO dashboard = service.getDashboard(7);

        assertThat(dashboard.period().days()).isEqualTo(7);
        assertThat(dashboard.period().averageMyDailyLogs())
                .isEqualByComparingTo(new BigDecimal("1.00"));
    }

    /** Sin una sola bitacora no se inventa un "mejor dia" entre empates a cero. */
    @Test
    void withoutActivityThereIsNoBestDayAndTheAverageIsZero() {
        authenticatedWithEmployee();
        quietWorkshop();
        sales(List.of(salesOn(TODAY, 3)));
        myLogs(List.of());

        SecretaryDashboardResponseDTO dashboard = service.getDashboard(7);

        assertThat(dashboard.period().bestDay()).isNull();
        assertThat(dashboard.period().bestDayCount()).isZero();
        assertThat(dashboard.period().activeDays()).isZero();
        assertThat(dashboard.period().averageMyDailyLogs())
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    /** Ante un empate gana el dia mas antiguo: la marca se logro entonces. */
    @Test
    void aTieOnTheBestDayKeepsTheEarliestDate() {
        authenticatedWithEmployee();
        quietWorkshop();
        sales(List.of());
        myLogs(List.of(logsOn(TODAY.minusDays(3), 4), logsOn(TODAY, 4)));

        assertThat(service.getDashboard(7).period().bestDay()).isEqualTo(TODAY.minusDays(3));
    }

    // ── Coste ────────────────────────────────────────────────────

    /**
     * El numero de consultas no depende del tamaño del periodo: noventa dias
     * cuestan exactamente lo mismo que siete.
     */
    @Test
    void theQueryCountDoesNotGrowWithThePeriod() {
        authenticatedWithEmployee();
        quietWorkshop();
        sales(List.of());
        myLogs(List.of());

        service.getDashboard(90);

        verify(saleRepository).findSalesByDay(any(), any(), any());
        verify(dailyLogRepository).findMyDailyLogsByDay(any(), any(), any(), any());
        verify(dailyLogRepository).countByWorkshopIdAndPerformedDate(any(), any());
        verify(cashClosureRepository).findStampByWorkshopIdAndBusinessDate(any(), any());
    }

}
