package com.cns.spring_house.sale.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
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
import com.cns.spring_house.sale.dto.SaleDashboardResponseDTO;
import com.cns.spring_house.sale.entity.InvoiceType;
import com.cns.spring_house.sale.entity.PaymentStatus;
import com.cns.spring_house.sale.entity.SaleStatus;
import com.cns.spring_house.sale.repository.SaleRepository;
import com.cns.spring_house.sale.repository.projection.PaymentMethodStatProjection;
import com.cns.spring_house.sale.repository.projection.SalesByDayProjection;
import com.cns.spring_house.sale.repository.projection.SalesSummaryProjection;
import com.cns.spring_house.sale.repository.projection.TopProductProjection;
import com.cns.spring_house.shared.config.time.TimeConfig;
import com.cns.spring_house.shared.security.CurrentUserService;
import com.cns.spring_house.useraccount.entity.UserAccount;
import com.cns.spring_house.workshop.entity.Workshop;

/**
 * Semantica del dashboard de ventas: que se cuenta, con que limites de fecha
 * y de quien. Usa un reloj fijo porque el KPI de hoy depende del limite del
 * dia y una prueba ejecutada a las 23:59 seria intermitente.
 */
@ExtendWith(MockitoExtension.class)
class SaleDashboardServiceImplTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 15);
    private static final LocalDateTime DAY_START = TODAY.atStartOfDay();
    private static final LocalDateTime DAY_END = TODAY.plusDays(1).atStartOfDay();

    @Mock
    private SaleRepository repository;

    @Mock
    private CurrentUserService currentUserService;

    private SaleDashboardServiceImpl service;

    private final UUID workshopAId = UUID.randomUUID();
    private final UUID workshopBId = UUID.randomUUID();
    private final UUID userAccountId = UUID.randomUUID();
    private final UUID employeeId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        Clock fixedClock = Clock.fixed(
                TODAY.atTime(10, 30).atZone(ZoneId.systemDefault()).toInstant(),
                ZoneId.systemDefault());
        service = new SaleDashboardServiceImpl(repository, currentUserService, fixedClock);

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

    private static SalesSummaryProjection summary(long count, BigDecimal amount) {
        return new SalesSummaryProjection() {
            @Override
            public long getSalesCount() {
                return count;
            }

            @Override
            public BigDecimal getAmount() {
                return amount;
            }
        };
    }

    private static SalesByDayProjection day(String date, long count, BigDecimal amount) {
        return new SalesByDayProjection() {
            @Override
            public String getSaleDate() {
                return date;
            }

            @Override
            public long getSalesCount() {
                return count;
            }

            @Override
            public BigDecimal getAmount() {
                return amount;
            }
        };
    }

    private static TopProductProjection product(
            UUID id, String name, long quantity, BigDecimal amount) {
        return new TopProductProjection() {
            @Override
            public UUID getProductId() {
                return id;
            }

            @Override
            public String getProductName() {
                return name;
            }

            @Override
            public long getQuantity() {
                return quantity;
            }

            @Override
            public BigDecimal getAmount() {
                return amount;
            }
        };
    }

    private static PaymentMethodStatProjection paymentMethod(
            String method, long count, BigDecimal amount) {
        return new PaymentMethodStatProjection() {
            @Override
            public String getMethod() {
                return method;
            }

            @Override
            public long getSaleCount() {
                return count;
            }

            @Override
            public BigDecimal getAmount() {
                return amount;
            }
        };
    }

    /** Deja todas las consultas en cero/vacio para aislar lo que prueba cada test. */
    private void stubEmptyRepository() {
        lenient().when(repository.summarizeCompletedSales(any(), any(), any()))
                .thenReturn(summary(0L, BigDecimal.ZERO));
        lenient().when(repository.summarizeCompletedSalesForEmployee(any(), any(), any(), any()))
                .thenReturn(summary(0L, BigDecimal.ZERO));
        lenient().when(repository.findSalesByDay(any(), any(), any())).thenReturn(List.of());
        lenient().when(repository.findSalesByDayForEmployee(any(), any(), any(), any()))
                .thenReturn(List.of());
        lenient().when(repository.findTopProductsToday(any(), any(), any(), anyInt()))
                .thenReturn(List.of());
        lenient().when(repository.findTopProductsTodayForEmployee(
                any(), any(), any(), any(), anyInt())).thenReturn(List.of());
        lenient().when(repository.findPaymentMethodsBetween(any(), any(), any()))
                .thenReturn(List.of());
        lenient().when(repository.findPaymentMethodsBetweenForEmployee(any(), any(), any(), any()))
                .thenReturn(List.of());
    }

    // ── Aislamiento por taller ───────────────────────────────────

    @Test
    void everyQueryIsScopedToTheWorkshopOfTheToken() {
        authenticatedWithEmployee();
        stubEmptyRepository();

        service.getDashboard(30);

        verify(repository).countByWorkshopIdAndStatus(workshopAId, SaleStatus.COMPLETED);
        verify(repository).countByWorkshopIdAndStatus(workshopAId, SaleStatus.PENDING);
        verify(repository).countByWorkshopIdAndStatusAndInvoiceTypeAndPaymentStatus(
                workshopAId, SaleStatus.COMPLETED, InvoiceType.EXCHANGE_INVOICE,
                PaymentStatus.PENDING);

        verify(repository, never()).countByWorkshopIdAndStatus(workshopBId, SaleStatus.COMPLETED);
    }

    // ── Ambito personal ──────────────────────────────────────────

    @Test
    void thePersonalScopeUsesTheEmployeeOfTheAuthenticatedAccount() {
        authenticatedWithEmployee();
        stubEmptyRepository();

        service.getDashboard(30);

        verify(repository).countByWorkshopIdAndEmployeeIdAndStatus(
                workshopAId, employeeId, SaleStatus.COMPLETED);
        verify(repository).countByWorkshopIdAndEmployeeIdAndStatus(
                workshopAId, employeeId, SaleStatus.PENDING);
        verify(repository).summarizeCompletedSalesForEmployee(
                workshopAId, employeeId, DAY_START, DAY_END);
    }

    @Test
    void withoutAnEmployeeThePersonalScopeIsUnavailableAndNeverQueried() {
        authenticatedWithoutEmployee();
        stubEmptyRepository();

        SaleDashboardResponseDTO dashboard = service.getDashboard(30);

        assertThat(dashboard.personalScopeAvailable()).isFalse();
        assertThat(dashboard.mine().kpis().completedSalesTotal()).isZero();
        assertThat(dashboard.mine().topProductsToday()).isEmpty();
        assertThat(dashboard.mine().trend()).hasSize(30);

        verify(repository, never()).countByWorkshopIdAndEmployeeIdAndStatus(any(), any(), any());
        verify(repository, never()).findTopProductsTodayForEmployee(
                any(), any(), any(), any(), anyInt());
    }

    // ── Excluir CANCELLED/REVERSED implicitamente por status ─────

    @Test
    void onlyCompletedStatusIsUsedForSuccessfulSaleCounts() {
        authenticatedWithEmployee();
        stubEmptyRepository();

        service.getDashboard(30);

        verify(repository, never()).countByWorkshopIdAndStatus(any(), eq(SaleStatus.CANCELLED));
        verify(repository, never()).countByWorkshopIdAndStatus(any(), eq(SaleStatus.REVERSED));
    }

    // ── Ticket promedio ──────────────────────────────────────────

    @Test
    void averageTicketIsNullWhenThereAreNoSalesToday() {
        authenticatedWithEmployee();
        stubEmptyRepository();

        SaleDashboardResponseDTO dashboard = service.getDashboard(30);

        assertThat(dashboard.general().kpis().completedSalesToday()).isZero();
        assertThat(dashboard.general().kpis().averageTicketToday()).isNull();
    }

    @Test
    void averageTicketDividesAmountByCountWithTwoDecimals() {
        authenticatedWithEmployee();
        stubEmptyRepository();
        when(repository.summarizeCompletedSales(workshopAId, DAY_START, DAY_END))
                .thenReturn(summary(3L, new BigDecimal("100.00")));

        var kpis = service.getDashboard(30).general().kpis();

        assertThat(kpis.completedSalesToday()).isEqualTo(3L);
        assertThat(kpis.amountSoldToday()).isEqualByComparingTo("100.00");
        assertThat(kpis.averageTicketToday()).isEqualByComparingTo("33.33");
    }

    // ── Tendencia ────────────────────────────────────────────────

    @Test
    void trendDaysDefaultsToThirtyWhenNotOneOfTheAllowedValues() {
        authenticatedWithEmployee();
        stubEmptyRepository();

        SaleDashboardResponseDTO dashboard = service.getDashboard(45);

        assertThat(dashboard.trendDays()).isEqualTo(30);
        assertThat(dashboard.trendFrom()).isEqualTo(TODAY.minusDays(29));
    }

    @Test
    void trendDaysAcceptsSevenAndNinety() {
        authenticatedWithEmployee();
        stubEmptyRepository();

        assertThat(service.getDashboard(7).trendDays()).isEqualTo(7);
        assertThat(service.getDashboard(90).trendDays()).isEqualTo(90);
    }

    @Test
    void daysWithoutSalesAreFilledWithZeroInsteadOfBeingSkipped() {
        authenticatedWithEmployee();
        stubEmptyRepository();
        LocalDate trendFrom = TODAY.minusDays(6);
        when(repository.findSalesByDay(eq(workshopAId), any(), any())).thenReturn(List.of(
                day(TODAY.toString(), 4L, new BigDecimal("40.00")),
                day(trendFrom.toString(), 2L, new BigDecimal("20.00"))));

        List<com.cns.spring_house.sale.dto.SaleTrendPointDTO> trend =
                service.getDashboard(7).general().trend();

        assertThat(trend).hasSize(7);
        assertThat(trend.get(0).salesCount()).isEqualTo(2L);
        assertThat(trend.get(6).salesCount()).isEqualTo(4L);
        assertThat(trend.subList(1, 6))
                .allSatisfy(point -> assertThat(point.salesCount()).isZero());
    }

    // ── Productos y metodos de pago ──────────────────────────────

    @Test
    void topProductsUseTheDatabaseOrderAndLimit() {
        authenticatedWithEmployee();
        stubEmptyRepository();
        UUID productId = UUID.randomUUID();
        when(repository.findTopProductsToday(workshopAId, DAY_START, DAY_END, 10))
                .thenReturn(List.of(product(productId, "HOJA 900MM", 12L, new BigDecimal("500.00"))));

        var topProducts = service.getDashboard(30).general().topProductsToday();

        assertThat(topProducts).hasSize(1);
        assertThat(topProducts.get(0).productId()).isEqualTo(productId);
        assertThat(topProducts.get(0).productName()).isEqualTo("HOJA 900MM");
        assertThat(topProducts.get(0).quantity()).isEqualTo(12L);
    }

    @Test
    void paymentMethodsMapTheEnumFromTheDatabaseValue() {
        authenticatedWithEmployee();
        stubEmptyRepository();
        when(repository.findPaymentMethodsBetween(workshopAId, DAY_START, DAY_END))
                .thenReturn(List.of(
                        paymentMethod("CASH", 5L, new BigDecimal("250.00")),
                        paymentMethod("TRANSFER", 2L, new BigDecimal("80.00"))));

        var methods = service.getDashboard(30).general().paymentMethodsToday();

        assertThat(methods).extracting(m -> m.method().name())
                .containsExactly("CASH", "TRANSFER");
    }

    // ── Frontera de zona horaria de negocio ───────────────────────

    /**
     * Reproduce el escenario real que motiva {@link TimeConfig#BUSINESS_ZONE}:
     * una peticion servida de noche en Guatemala, cuyo instante UTC ya cae en
     * el dia calendario siguiente. La fecha de negocio del dashboard, y por lo
     * tanto todos los limites de "hoy" que de ella derivan, no deben
     * adelantarse solo porque UTC ya cambio de dia.
     */
    @Test
    void businessDateDoesNotRolloverAtMidnightUtcWhenItIsStillEveningInGuatemala() {
        // 19:30 del 15/08 en Guatemala (UTC-6) = 01:30 del 16/08 en UTC.
        Instant nightInGuatemala = Instant.parse("2026-08-16T01:30:00Z");
        Clock clockAtNight = Clock.fixed(nightInGuatemala, TimeConfig.BUSINESS_ZONE);
        SaleDashboardServiceImpl serviceAtNight =
                new SaleDashboardServiceImpl(repository, currentUserService, clockAtNight);
        authenticatedWithEmployee();
        stubEmptyRepository();

        SaleDashboardResponseDTO dashboard = serviceAtNight.getDashboard(30);

        assertThat(dashboard.businessDate()).isEqualTo(LocalDate.of(2026, 8, 15));
        verify(repository).summarizeCompletedSales(
                workshopAId,
                LocalDate.of(2026, 8, 15).atStartOfDay(),
                LocalDate.of(2026, 8, 16).atStartOfDay());
    }

    /**
     * Al otro lado del limite: pasada la medianoche local, "hoy" ya debe ser
     * el dia siguiente aunque falten pocos minutos de reloj.
     */
    @Test
    void businessDateAdvancesRightAfterLocalMidnightInGuatemala() {
        // 00:05 del 16/08 en Guatemala (UTC-6) = 06:05 del 16/08 en UTC.
        Instant justAfterMidnight = Instant.parse("2026-08-16T06:05:00Z");
        Clock clockAfterMidnight = Clock.fixed(justAfterMidnight, TimeConfig.BUSINESS_ZONE);
        SaleDashboardServiceImpl serviceAfterMidnight =
                new SaleDashboardServiceImpl(repository, currentUserService, clockAfterMidnight);
        authenticatedWithEmployee();
        stubEmptyRepository();

        SaleDashboardResponseDTO dashboard = serviceAfterMidnight.getDashboard(30);

        assertThat(dashboard.businessDate()).isEqualTo(LocalDate.of(2026, 8, 16));
    }

    /**
     * Con una zona incorrecta (UTC) el mismo instante nocturno en Guatemala
     * apareceria como el dia siguiente: prueba que el limite realmente
     * depende de la zona del reloj y no es una coincidencia del resto de la
     * prueba.
     */
    @Test
    void aClockInUtcWouldIncorrectlyAdvanceTheBusinessDateForTheSameInstant() {
        Instant nightInGuatemala = Instant.parse("2026-08-16T01:30:00Z");
        Clock utcClock = Clock.fixed(nightInGuatemala, ZoneId.of("UTC"));
        SaleDashboardServiceImpl serviceWithUtcClock =
                new SaleDashboardServiceImpl(repository, currentUserService, utcClock);
        authenticatedWithEmployee();
        stubEmptyRepository();

        SaleDashboardResponseDTO dashboard = serviceWithUtcClock.getDashboard(30);

        assertThat(dashboard.businessDate()).isEqualTo(LocalDate.of(2026, 8, 16));
    }

    /**
     * Todas las cifras de "hoy" -y no solo el conteo de completadas- comparten
     * el mismo limite de dia calculado una sola vez: la tendencia, el top de
     * productos y los metodos de pago se piden con exactamente el mismo
     * [dayStart, dayEnd) que el resumen de hoy, tanto en general como en mine.
     */
    @Test
    void allTodayMetricsShareTheExactSameDayBoundaryForBothScopes() {
        Instant nightInGuatemala = Instant.parse("2026-08-16T01:30:00Z");
        Clock clockAtNight = Clock.fixed(nightInGuatemala, TimeConfig.BUSINESS_ZONE);
        SaleDashboardServiceImpl serviceAtNight =
                new SaleDashboardServiceImpl(repository, currentUserService, clockAtNight);
        authenticatedWithEmployee();
        stubEmptyRepository();

        serviceAtNight.getDashboard(30);

        LocalDateTime expectedStart = LocalDate.of(2026, 8, 15).atStartOfDay();
        LocalDateTime expectedEnd = LocalDate.of(2026, 8, 16).atStartOfDay();

        verify(repository).summarizeCompletedSales(workshopAId, expectedStart, expectedEnd);
        verify(repository).summarizeCompletedSalesForEmployee(
                workshopAId, employeeId, expectedStart, expectedEnd);
        verify(repository).findTopProductsToday(
                workshopAId, expectedStart, expectedEnd, SaleDashboardServiceImpl.TOP_PRODUCTS_LIMIT);
        verify(repository).findTopProductsTodayForEmployee(
                workshopAId, employeeId, expectedStart, expectedEnd,
                SaleDashboardServiceImpl.TOP_PRODUCTS_LIMIT);
        verify(repository).findPaymentMethodsBetween(workshopAId, expectedStart, expectedEnd);
        verify(repository).findPaymentMethodsBetweenForEmployee(
                workshopAId, employeeId, expectedStart, expectedEnd);
    }

    // ── Sin datos ────────────────────────────────────────────────

    @Test
    void anEmptyWorkshopReturnsZerosAndEmptyCollections() {
        authenticatedWithEmployee();
        stubEmptyRepository();

        SaleDashboardResponseDTO dashboard = service.getDashboard(30);

        assertThat(dashboard.general().kpis().completedSalesTotal()).isZero();
        assertThat(dashboard.general().kpis().pendingSales()).isZero();
        assertThat(dashboard.general().kpis().pendingExchangeInvoices()).isZero();
        assertThat(dashboard.general().topProductsToday()).isEmpty();
        assertThat(dashboard.general().paymentMethodsToday()).isEmpty();
        assertThat(dashboard.general().trend())
                .hasSize(30)
                .allSatisfy(point -> assertThat(point.salesCount()).isZero());
    }

}
