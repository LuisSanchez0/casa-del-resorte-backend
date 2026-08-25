package com.cns.spring_house.dashboard.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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

import com.cns.spring_house.dashboard.dto.AdminDashboardOverviewResponseDTO;
import com.cns.spring_house.dashboard.dto.SalesGranularity;
import com.cns.spring_house.dashboard.dto.SalesTrendPointDTO;
import com.cns.spring_house.dashboard.dto.SalesTrendResponseDTO;
import com.cns.spring_house.dashboard.dto.VehicleOccupancyResponseDTO;
import com.cns.spring_house.employee.repository.EmployeeRepository;
import com.cns.spring_house.inventory.repository.InventoryRepository;
import com.cns.spring_house.job.repository.JobLogRepository;
import com.cns.spring_house.sale.repository.SaleRepository;
import com.cns.spring_house.sale.repository.projection.SalesBucketProjection;
import com.cns.spring_house.sale.repository.projection.SalesSummaryProjection;
import com.cns.spring_house.shared.exception.ValidationException;
import com.cns.spring_house.transformation.repository.TransformationRepository;
import com.cns.spring_house.vehiclemovement.repository.VehicleMovementRepository;
import com.cns.spring_house.vehiclemovement.repository.projection.OccupancyByDayProjection;

/**
 * Semantica del panel administrativo: que periodo se consulta, como se rellenan
 * los huecos y de que taller salen las cifras.
 *
 * El SQL se prueba aparte contra PostgreSQL real
 * ({@link com.cns.spring_house.dashboard.repository.AdminDashboardQueriesTest});
 * lo que se comprueba aqui son las decisiones que toma el servicio ANTES y
 * DESPUES de consultar, que con una base de datos delante quedarian mezcladas
 * con el resultado de la consulta.
 *
 * El reloj se fija a una fecha concreta: los limites del periodo dependen de la
 * fecha de negocio, y una prueba ejecutada el ultimo dia del mes seria
 * intermitente.
 */
@ExtendWith(MockitoExtension.class)
class AdminDashboardServiceImplTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 15);

    @Mock
    private SaleRepository saleRepository;

    @Mock
    private VehicleMovementRepository vehicleMovementRepository;

    @Mock
    private JobLogRepository jobLogRepository;

    @Mock
    private TransformationRepository transformationRepository;

    @Mock
    private EmployeeRepository employeeRepository;

    @Mock
    private InventoryRepository inventoryRepository;

    private AdminDashboardServiceImpl service;

    private final UUID workshopId = UUID.randomUUID();
    /** Taller ajeno: no debe aparecer en ninguna consulta. */
    private final UUID otherWorkshopId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        Clock fixedClock = Clock.fixed(
                TODAY.atTime(18, 0).atZone(ZoneId.systemDefault()).toInstant(),
                ZoneId.systemDefault());
        service = new AdminDashboardServiceImpl(
                saleRepository, vehicleMovementRepository, jobLogRepository,
                transformationRepository, employeeRepository, inventoryRepository,
                fixedClock);

        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject(UUID.randomUUID().toString())
                .claim("workshop", workshopId.toString())
                .build();
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    // ── Resumen ──────────────────────────────────────────────────

    @Test
    void overviewReadsEveryAggregateWithTheWorkshopFromTheToken() {
        stubOverview();

        AdminDashboardOverviewResponseDTO overview = service.getOverview();

        assertThat(overview.businessDate()).isEqualTo(TODAY);
        assertThat(overview.totalGeneratedSales()).isEqualByComparingTo("1250.00");
        assertThat(overview.completedSalesCount()).isEqualTo(7L);
        assertThat(overview.currentVehicles()).isEqualTo(3L);
        assertThat(overview.workerCount()).isEqualTo(9L);
        assertThat(overview.firstSaleDate()).isEqualTo(LocalDate.of(2025, 2, 3));

        // El taller del token viaja a TODAS las consultas, y nunca el ajeno.
        verify(saleRepository).summarizeGeneratedSales(workshopId);
        verify(vehicleMovementRepository).countByWorkshopIdAndExitIsNull(workshopId);
        verify(employeeRepository).countByWorkshopId(workshopId);
        verify(saleRepository).findTopSellingProducts(eq(workshopId), anyInt());
        verify(inventoryRepository).findLeastSellingProducts(eq(workshopId), anyInt());
        verify(jobLogRepository).findTopServiceEmployees(eq(workshopId), anyInt());
        verify(transformationRepository).findTopTransformationEmployees(eq(workshopId), anyInt());
        verify(saleRepository).findTopCustomersByGeneratedSales(eq(workshopId), anyInt());

        verify(saleRepository, never()).summarizeGeneratedSales(otherWorkshopId);
        verify(employeeRepository, never()).countByWorkshopId(otherWorkshopId);
    }

    /** Un taller sin ventas no tiene primera fecha, y no se inventa una. */
    @Test
    void overviewLeavesTheFirstSaleDateNullWithoutSales() {
        stubOverview();
        when(saleRepository.findFirstCompletedSaleDate(workshopId)).thenReturn(null);

        assertThat(service.getOverview().firstSaleDate()).isNull();
    }

    /** Los rankings tienen los tamanos que la pantalla promete. */
    @Test
    void overviewAsksForTenTopProductsAndFiveOfEverythingElse() {
        stubOverview();

        service.getOverview();

        verify(saleRepository).findTopSellingProducts(workshopId, 10);
        verify(inventoryRepository).findLeastSellingProducts(workshopId, 5);
        verify(jobLogRepository).findTopServiceEmployees(workshopId, 5);
        verify(transformationRepository).findTopTransformationEmployees(workshopId, 5);
        verify(saleRepository).findTopCustomersByGeneratedSales(workshopId, 5);
    }

    // ── Serie de ingresos ────────────────────────────────────────

    /**
     * DAY sobre el mes en curso llega hasta HOY y no hasta el 31: los dias que
     * todavia no han ocurrido no son dias sin ventas.
     */
    @Test
    void dailyTrendStopsAtTheBusinessDateWithoutInventingFutureBuckets() {
        when(saleRepository.findGeneratedSalesBuckets(
                eq(workshopId), eq("day"), any(), any()))
                .thenReturn(List.of(bucket("2026-08-02", 2L, "300.00")));

        SalesTrendResponseDTO trend = service.getSalesTrend(SalesGranularity.DAY, null);

        assertThat(trend.from()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(trend.to()).isEqualTo(TODAY);
        assertThat(trend.points()).hasSize(15);
        assertThat(trend.points().get(14).bucket()).isEqualTo(TODAY);

        verify(saleRepository).findGeneratedSalesBuckets(
                workshopId, "day",
                LocalDate.of(2026, 8, 1).atStartOfDay(),
                LocalDate.of(2026, 8, 16).atStartOfDay());
    }

    /**
     * Un dia sin ventas vale cero y no se omite: omitirlo haria que la grafica
     * uniera dos fechas lejanas con una recta y aparentara ventas que nunca
     * existieron.
     */
    @Test
    void dailyTrendFillsEmptyBucketsWithZero() {
        when(saleRepository.findGeneratedSalesBuckets(
                eq(workshopId), eq("day"), any(), any()))
                .thenReturn(List.of(
                        bucket("2026-07-01", 1L, "100.00"),
                        bucket("2026-07-03", 3L, "250.00")));

        SalesTrendResponseDTO trend = service.getSalesTrend(
                SalesGranularity.DAY, "2026-07");

        assertThat(trend.points()).hasSize(31);
        assertThat(trend.points().subList(0, 3))
                .extracting(point -> point.bucket().toString(), SalesTrendPointDTO::generatedAmount)
                .containsExactly(
                        tuple("2026-07-01", new BigDecimal("100.00")),
                        tuple("2026-07-02", BigDecimal.ZERO),
                        tuple("2026-07-03", new BigDecimal("250.00")));
    }

    /** Un mes ya cerrado se devuelve entero, no recortado a hoy. */
    @Test
    void dailyTrendReturnsAWholePastMonth() {
        when(saleRepository.findGeneratedSalesBuckets(
                eq(workshopId), eq("day"), any(), any()))
                .thenReturn(List.of());

        SalesTrendResponseDTO trend = service.getSalesTrend(
                SalesGranularity.DAY, "2026-02");

        assertThat(trend.from()).isEqualTo(LocalDate.of(2026, 2, 1));
        assertThat(trend.to()).isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(trend.points()).hasSize(28);
    }

    /** Un mes que aun no ha empezado devuelve una serie vacia, no ceros. */
    @Test
    void dailyTrendReturnsNothingForAFuturePeriod() {
        SalesTrendResponseDTO trend = service.getSalesTrend(
                SalesGranularity.DAY, "2026-12");

        assertThat(trend.points()).isEmpty();
        assertThat(trend.totalGeneratedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        verify(saleRepository, never()).findGeneratedSalesBuckets(
                any(), any(), any(), any());
    }

    /** MONTH sobre el ano en curso avanza mes a mes y para en el mes actual. */
    @Test
    void monthlyTrendAdvancesByMonthAndStopsAtTheCurrentMonth() {
        when(saleRepository.findGeneratedSalesBuckets(
                eq(workshopId), eq("month"), any(), any()))
                .thenReturn(List.of(bucket("2026-03-01", 4L, "900.00")));

        SalesTrendResponseDTO trend = service.getSalesTrend(SalesGranularity.MONTH, null);

        assertThat(trend.points()).hasSize(8);
        assertThat(trend.points().get(0).bucket()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(trend.points().get(7).bucket()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(trend.points().get(2).generatedAmount()).isEqualByComparingTo("900.00");
        assertThat(trend.totalGeneratedAmount()).isEqualByComparingTo("900.00");

        verify(saleRepository).findGeneratedSalesBuckets(
                workshopId, "month",
                LocalDate.of(2026, 1, 1).atStartOfDay(),
                LocalDate.of(2026, 9, 1).atStartOfDay());
    }

    /**
     * YEAR no tiene selector: su rango ES la historia del taller, del primer
     * cobro al ano en curso.
     */
    @Test
    void yearlyTrendSpansTheAvailableHistory() {
        when(saleRepository.findFirstCompletedSaleDate(workshopId))
                .thenReturn(LocalDate.of(2024, 5, 9).atTime(9, 0));
        when(saleRepository.findGeneratedSalesBuckets(
                eq(workshopId), eq("year"), any(), any()))
                .thenReturn(List.of(bucket("2024-01-01", 10L, "5000.00")));

        SalesTrendResponseDTO trend = service.getSalesTrend(
                SalesGranularity.YEAR, "ignorado");

        assertThat(trend.points())
                .extracting(point -> point.bucket().getYear())
                .containsExactly(2024, 2025, 2026);
        assertThat(trend.points().get(1).generatedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    /** Sin historia, la serie anual es el ano en curso en cero. */
    @Test
    void yearlyTrendFallsBackToTheCurrentYearWithoutHistory() {
        when(saleRepository.findFirstCompletedSaleDate(workshopId)).thenReturn(null);
        when(saleRepository.findGeneratedSalesBuckets(
                eq(workshopId), eq("year"), any(), any()))
                .thenReturn(List.of());

        SalesTrendResponseDTO trend = service.getSalesTrend(SalesGranularity.YEAR, null);

        assertThat(trend.points()).hasSize(1);
        assertThat(trend.points().get(0).bucket()).isEqualTo(LocalDate.of(2026, 1, 1));
    }

    /**
     * DAY y MONTH no consultan la primera fecha de venta: su rango no depende
     * de la historia, asi que pedirla seria una consulta desperdiciada.
     */
    @Test
    void onlyTheYearlyTrendLooksUpTheFirstSaleDate() {
        when(saleRepository.findGeneratedSalesBuckets(
                eq(workshopId), eq("day"), any(), any()))
                .thenReturn(List.of());

        service.getSalesTrend(SalesGranularity.DAY, null);

        verify(saleRepository, never()).findFirstCompletedSaleDate(any());
    }

    @Test
    void trendRejectsAMalformedPeriod() {
        assertThatThrownBy(() -> service.getSalesTrend(SalesGranularity.DAY, "agosto"))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.getSalesTrend(SalesGranularity.MONTH, "1200"))
                .isInstanceOf(ValidationException.class);
    }

    /** El total lo suma el servidor sobre los puntos que devuelve, en BigDecimal. */
    @Test
    void trendTotalsTheReturnedPoints() {
        when(saleRepository.findGeneratedSalesBuckets(
                eq(workshopId), eq("day"), any(), any()))
                .thenReturn(List.of(
                        bucket("2026-07-01", 1L, "100.10"),
                        bucket("2026-07-02", 2L, "200.20")));

        SalesTrendResponseDTO trend = service.getSalesTrend(
                SalesGranularity.DAY, "2026-07");

        assertThat(trend.totalGeneratedAmount()).isEqualByComparingTo("300.30");
        assertThat(trend.salesCount()).isEqualTo(3L);
    }

    // ── Ocupacion ────────────────────────────────────────────────

    @Test
    void occupancyUsesTheRequestedWindowAndReportsCurrentAndPeak() {
        when(vehicleMovementRepository.findOccupancyByDay(
                eq(workshopId), any(), any(), any()))
                .thenReturn(List.of(
                        occupancy("2026-08-13", 2L),
                        occupancy("2026-08-14", 5L),
                        occupancy("2026-08-15", 3L)));
        when(vehicleMovementRepository.countByWorkshopIdAndExitIsNull(workshopId))
                .thenReturn(3L);

        VehicleOccupancyResponseDTO occupancy = service.getVehicleOccupancy(3);

        assertThat(occupancy.from()).isEqualTo(LocalDate.of(2026, 8, 13));
        assertThat(occupancy.to()).isEqualTo(TODAY);
        assertThat(occupancy.currentVehicles()).isEqualTo(3L);
        assertThat(occupancy.peakVehicles()).isEqualTo(5L);
        assertThat(occupancy.points()).hasSize(3);

        verify(vehicleMovementRepository).findOccupancyByDay(
                workshopId,
                LocalDate.of(2026, 8, 13),
                TODAY,
                LocalDateTime.of(2026, 8, 15, 18, 0));
    }

    @Test
    void occupancyRejectsAWindowOutsideTheAllowedRange() {
        assertThatThrownBy(() -> service.getVehicleOccupancy(0))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.getVehicleOccupancy(400))
                .isInstanceOf(ValidationException.class);
        verify(vehicleMovementRepository, never())
                .findOccupancyByDay(any(), any(), any(), any());
    }

    // ── Utilidades ───────────────────────────────────────────────

    private void stubOverview() {
        lenient().when(saleRepository.summarizeGeneratedSales(workshopId))
                .thenReturn(summary(7L, "1250.00"));
        lenient().when(saleRepository.findFirstCompletedSaleDate(workshopId))
                .thenReturn(LocalDate.of(2025, 2, 3).atTime(11, 0));
        lenient().when(vehicleMovementRepository.countByWorkshopIdAndExitIsNull(workshopId))
                .thenReturn(3L);
        lenient().when(employeeRepository.countByWorkshopId(workshopId)).thenReturn(9L);
        lenient().when(saleRepository.findTopSellingProducts(eq(workshopId), anyInt()))
                .thenReturn(List.of());
        lenient().when(inventoryRepository.findLeastSellingProducts(eq(workshopId), anyInt()))
                .thenReturn(List.of());
        lenient().when(jobLogRepository.findTopServiceEmployees(eq(workshopId), anyInt()))
                .thenReturn(List.of());
        lenient().when(transformationRepository
                .findTopTransformationEmployees(eq(workshopId), anyInt()))
                .thenReturn(List.of());
        lenient().when(saleRepository.findTopCustomersByGeneratedSales(eq(workshopId), anyInt()))
                .thenReturn(List.of());
    }

    private static SalesSummaryProjection summary(long count, String amount) {
        return new SalesSummaryProjection() {
            @Override
            public long getSalesCount() {
                return count;
            }

            @Override
            public BigDecimal getAmount() {
                return new BigDecimal(amount);
            }
        };
    }

    private static SalesBucketProjection bucket(String date, long count, String amount) {
        return new SalesBucketProjection() {
            @Override
            public String getBucketDate() {
                return date;
            }

            @Override
            public long getSalesCount() {
                return count;
            }

            @Override
            public BigDecimal getAmount() {
                return new BigDecimal(amount);
            }
        };
    }

    private static OccupancyByDayProjection occupancy(String date, long vehicles) {
        return new OccupancyByDayProjection() {
            @Override
            public String getBucketDate() {
                return date;
            }

            @Override
            public long getVehicles() {
                return vehicles;
            }
        };
    }

}
