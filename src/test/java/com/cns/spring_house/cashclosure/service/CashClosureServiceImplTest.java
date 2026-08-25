package com.cns.spring_house.cashclosure.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.cns.spring_house.cashclosure.dto.CashClosureBasePreviewResponseDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureCollectionsDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureDocumentDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureInvoicingDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureReceivablesDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureResponseDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureSummaryResponseDTO;
import com.cns.spring_house.cashclosure.dto.CreateCashClosureRequestDTO;
import com.cns.spring_house.cashclosure.entity.CashBalanceStatus;
import com.cns.spring_house.cashclosure.entity.CashClosure;
import com.cns.spring_house.cashclosure.mapper.CashClosureMapper;
import com.cns.spring_house.cashclosure.report.CashClosureReportService;
import com.cns.spring_house.cashclosure.repository.CashClosureRepository;
import com.cns.spring_house.cashclosure.repository.projection.CashClosureSummaryProjection;
import com.cns.spring_house.employee.entity.Employee;
import com.cns.spring_house.shared.config.time.TimeConfig;
import com.cns.spring_house.shared.exception.EntityNotFoundException;
import com.cns.spring_house.shared.exception.ValidationException;
import com.cns.spring_house.shared.security.CurrentUserService;
import com.cns.spring_house.workshop.entity.Workshop;
import com.cns.spring_house.workshop.repository.WorkshopRepository;

/**
 * Las decisiones del servicio de cierre: que se calcula, que se persiste y que
 * NO se persiste.
 *
 * El reloj es fijo porque la fecha operativa decide la ventana del dia y una
 * prueba ejecutada a las 23:59 seria intermitente. El mapper es el de verdad y
 * no un mock: la formula del arqueo vive en el, y sustituirlo por un doble
 * dejaria sin comprobar justo lo que importa.
 */
@ExtendWith(MockitoExtension.class)
class CashClosureServiceImplTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 20);

    @Mock
    private CashClosureRepository repository;

    @Mock
    private CashClosureCalculator calculator;

    @Mock
    private CashClosureReportService reportService;

    @Mock
    private CurrentUserService currentUserService;

    @Mock
    private WorkshopRepository workshopRepository;

    private CashClosureServiceImpl service;

    private final UUID workshopId = UUID.randomUUID();
    private final UUID userAccountId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        Clock fixedClock = Clock.fixed(
                TODAY.atTime(18, 45).atZone(TimeConfig.BUSINESS_ZONE).toInstant(),
                TimeConfig.BUSINESS_ZONE);

        service = new CashClosureServiceImpl(
                repository, calculator, new CashClosureMapper(), reportService,
                currentUserService, workshopRepository, fixedClock);

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

    // ── Datos ────────────────────────────────────────────────────

    private Workshop workshop() {
        Workshop workshop = Workshop.builder()
                .name("LA CASA DEL RESORTE")
                .address("ZONA 1")
                .build();
        workshop.setId(workshopId);
        return workshop;
    }

    private Employee employee() {
        return Employee.builder()
                .workshop(workshop())
                .firstName("ANA")
                .lastName("LOPEZ")
                .build();
    }

    /** El dia del ejemplo del enunciado: Q20,000 facturados y Q18,000 cobrados. */
    private DailyCashFigures figures() {
        return new DailyCashFigures(
                TODAY,
                new CashClosureInvoicingDTO(15, new BigDecimal("12000.00"),
                        3, new BigDecimal("8000.00"), new BigDecimal("20000.00")),
                new CashClosureCollectionsDTO(
                        10, new BigDecimal("7000.00"),
                        6, new BigDecimal("6000.00"),
                        4, new BigDecimal("5000.00"),
                        new BigDecimal("18000.00")),
                new CashClosureReceivablesDTO(2, new BigDecimal("3000.00"),
                        new BigDecimal("1000.00"), new BigDecimal("25000.00")));
    }

    private CreateCashClosureRequestDTO request() {
        return new CreateCashClosureRequestDTO(
                new BigDecimal("500.00"), new BigDecimal("7480.00"), "Sin novedad");
    }

    private void withFigures() {
        when(calculator.calculate(workshopId, TODAY)).thenReturn(figures());
    }

    private void withAuthenticatedEmployee() {
        when(currentUserService.getCurrentEmployee()).thenReturn(employee());
        when(currentUserService.getCurrentWorkshop()).thenReturn(workshop());
    }

    private void withLockableWorkshop() {
        when(workshopRepository.lockById(workshopId))
                .thenReturn(Optional.of(workshop()));
    }

    // ── GET preview ──────────────────────────────────────────────

    @Test
    void theBasePreviewUsesTheWorkshopFromTheTokenAndTheBusinessDateFromTheClock() {
        withFigures();
        when(repository.findByWorkshopIdAndBusinessDate(workshopId, TODAY))
                .thenReturn(Optional.empty());

        CashClosureBasePreviewResponseDTO preview = service.getPreview();

        assertThat(preview.businessDate()).isEqualTo(TODAY);
        assertThat(preview.invoicing().totalInvoiced()).isEqualByComparingTo("20000.00");
        assertThat(preview.collections().totalCollected()).isEqualByComparingTo("18000.00");
        assertThat(preview.receivables().pendingAmount()).isEqualByComparingTo("25000.00");
        assertThat(preview.alreadyClosed()).isFalse();
        assertThat(preview.closureId()).isNull();
        verify(calculator).calculate(workshopId, TODAY);
    }

    /** Consultar la previsualizacion no escribe nada, pase lo que pase. */
    @Test
    void theBasePreviewNeverPersistsAnything() {
        withFigures();
        when(repository.findByWorkshopIdAndBusinessDate(workshopId, TODAY))
                .thenReturn(Optional.empty());

        service.getPreview();

        verify(repository, never()).save(any());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void theBasePreviewReportsAnExistingClosureSoTheScreenCanOfferTheSnapshot() {
        UUID closureId = UUID.randomUUID();
        CashClosure existing = CashClosure.builder().businessDate(TODAY).build();
        existing.setId(closureId);

        withFigures();
        when(repository.findByWorkshopIdAndBusinessDate(workshopId, TODAY))
                .thenReturn(Optional.of(existing));

        CashClosureBasePreviewResponseDTO preview = service.getPreview();

        assertThat(preview.alreadyClosed()).isTrue();
        assertThat(preview.closureId()).isEqualTo(closureId);
    }

    // ── POST preview ─────────────────────────────────────────────

    /**
     * El caso del enunciado: fondo Q500, efectivo cobrado Q7,000, esperado
     * Q7,500, contado Q7,480, diferencia -Q20 y FALTANTE.
     */
    @Test
    void theFinalPreviewComputesExpectedCashAndDifferenceFromCashOnly() {
        withFigures();
        when(repository.existsByWorkshopIdAndBusinessDate(workshopId, TODAY))
                .thenReturn(false);

        CashClosureResponseDTO preview = service.preview(request());

        assertThat(preview.cashReconciliation().openingCash())
                .isEqualByComparingTo("500.00");
        assertThat(preview.cashReconciliation().expectedCash())
                .isEqualByComparingTo("7500.00");
        assertThat(preview.cashReconciliation().countedCash())
                .isEqualByComparingTo("7480.00");
        assertThat(preview.cashReconciliation().difference())
                .isEqualByComparingTo("-20.00");
        assertThat(preview.cashReconciliation().status())
                .isEqualTo(CashBalanceStatus.SHORT);
    }

    /** Es una previsualizacion: no tiene identidad, ni firma, ni fecha de cierre. */
    @Test
    void theFinalPreviewHasNoIdentityAndPersistsNothing() {
        withFigures();
        when(repository.existsByWorkshopIdAndBusinessDate(workshopId, TODAY))
                .thenReturn(false);

        CashClosureResponseDTO preview = service.preview(request());

        assertThat(preview.id()).isNull();
        assertThat(preview.closedAt()).isNull();
        assertThat(preview.closedBy()).isNull();
        assertThat(preview.alreadyClosed()).isFalse();

        verify(repository, never()).save(any());
        verify(repository, never()).saveAndFlush(any());
        verify(workshopRepository, never()).lockById(any());
    }

    @Test
    void generatingThePreviewPdfNeitherClosesTheRegisterNorPersistsTheClosure() {
        withFigures();
        when(repository.existsByWorkshopIdAndBusinessDate(workshopId, TODAY))
                .thenReturn(false);
        when(currentUserService.getCurrentEmployee()).thenReturn(employee());
        when(reportService.renderDaily(any(), any(), eq(true), any()))
                .thenReturn(new byte[] { 1, 2, 3 });

        CashClosureDocumentDTO document = service.previewDocument(request());

        assertThat(document.fileName())
                .isEqualTo("previsualizacion-cierre-caja-2026-08-20.pdf");
        assertThat(document.content()).isNotEmpty();
        verify(repository, never()).save(any());
        verify(repository, never()).saveAndFlush(any());
    }

    /** El PDF de borrador se pide SIEMPRE marcado como previsualizacion. */
    @Test
    void thePreviewPdfIsRenderedAsADraftAndSignedByWhoeverAskedForIt() {
        withFigures();
        when(repository.existsByWorkshopIdAndBusinessDate(workshopId, TODAY))
                .thenReturn(false);
        when(currentUserService.getCurrentEmployee()).thenReturn(employee());
        when(reportService.renderDaily(any(), any(), eq(true), any()))
                .thenReturn(new byte[] { 1 });

        service.previewDocument(request());

        verify(reportService).renderDaily(any(), any(), eq(true), eq("ANA LOPEZ"));
    }

    // ── Cierre ───────────────────────────────────────────────────

    @Test
    void closingRecalculatesTheDayAndPersistsTheSnapshot() {
        withAuthenticatedEmployee();
        withLockableWorkshop();
        withFigures();
        when(repository.existsByWorkshopIdAndBusinessDate(workshopId, TODAY))
                .thenReturn(false);
        when(repository.saveAndFlush(any(CashClosure.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.close(request());

        ArgumentCaptor<CashClosure> captor = ArgumentCaptor.forClass(CashClosure.class);
        verify(repository).saveAndFlush(captor.capture());
        CashClosure saved = captor.getValue();

        assertThat(saved.getBusinessDate()).isEqualTo(TODAY);
        assertThat(saved.getInvoiceCount()).isEqualTo(15);
        assertThat(saved.getInvoiceTotal()).isEqualByComparingTo("12000.00");
        assertThat(saved.getExchangeInvoiceTotal()).isEqualByComparingTo("8000.00");
        assertThat(saved.getTotalInvoiced()).isEqualByComparingTo("20000.00");
        assertThat(saved.getCashPayments()).isEqualByComparingTo("7000.00");
        assertThat(saved.getCardPayments()).isEqualByComparingTo("6000.00");
        assertThat(saved.getTransferPayments()).isEqualByComparingTo("5000.00");
        assertThat(saved.getTotalCollected()).isEqualByComparingTo("18000.00");
        assertThat(saved.getExchangeCollectedAmount()).isEqualByComparingTo("3000.00");
        assertThat(saved.getPreviousExchangeCollectedAmount())
                .isEqualByComparingTo("1000.00");
        assertThat(saved.getPendingExchangeAmount()).isEqualByComparingTo("25000.00");
        assertThat(saved.getOpeningCash()).isEqualByComparingTo("500.00");
        assertThat(saved.getExpectedCash()).isEqualByComparingTo("7500.00");
        assertThat(saved.getCountedCash()).isEqualByComparingTo("7480.00");
        assertThat(saved.getDifference()).isEqualByComparingTo("-20.00");
        assertThat(saved.getNotes()).isEqualTo("Sin novedad");
    }

    /**
     * El cerrojo se toma ANTES de comprobar si ya existe cierre. Al reves, dos
     * peticiones simultaneas comprobarian las dos con la caja abierta.
     */
    @Test
    void closingLocksTheWorkshopBeforeCheckingWhetherTheDayIsAlreadyClosed() {
        withAuthenticatedEmployee();
        withLockableWorkshop();
        withFigures();
        when(repository.existsByWorkshopIdAndBusinessDate(workshopId, TODAY))
                .thenReturn(false);
        when(repository.saveAndFlush(any(CashClosure.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.close(request());

        InOrder order = inOrder(workshopRepository, repository);
        order.verify(workshopRepository).lockById(workshopId);
        order.verify(repository).existsByWorkshopIdAndBusinessDate(workshopId, TODAY);
        order.verify(repository).saveAndFlush(any(CashClosure.class));
    }

    @Test
    void closingTwiceTheSameDayIsRejectedWithoutPersistingAnything() {
        withAuthenticatedEmployee();
        withLockableWorkshop();
        when(repository.existsByWorkshopIdAndBusinessDate(workshopId, TODAY))
                .thenReturn(true);

        assertThatThrownBy(() -> service.close(request()))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("ya fue cerrada");

        verify(repository, never()).saveAndFlush(any());
        verify(calculator, never()).calculate(any(), any());
    }

    /** Del cliente solo se aceptan fondo, contado y nota; el resto lo calcula el servidor. */
    @Test
    void closingIgnoresAnyFigureTheClientCouldTryToSuggest() {
        withAuthenticatedEmployee();
        withLockableWorkshop();
        withFigures();
        when(repository.existsByWorkshopIdAndBusinessDate(workshopId, TODAY))
                .thenReturn(false);
        when(repository.saveAndFlush(any(CashClosure.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CashClosureResponseDTO closed = service.close(new CreateCashClosureRequestDTO(
                new BigDecimal("500.00"), new BigDecimal("7500.00"), null));

        // Las cifras salen del calculador, no de la peticion.
        assertThat(closed.collections().totalCollected()).isEqualByComparingTo("18000.00");
        assertThat(closed.cashReconciliation().difference()).isEqualByComparingTo("0.00");
        assertThat(closed.cashReconciliation().status())
                .isEqualTo(CashBalanceStatus.BALANCED);
        assertThat(closed.notes()).isNull();
    }

    // ── Bloqueo de ventas ────────────────────────────────────────

    @Test
    void theGuardLetsSalesThroughWhileTheRegisterIsOpen() {
        withLockableWorkshop();
        when(repository.existsByWorkshopIdAndBusinessDate(workshopId, TODAY))
                .thenReturn(false);

        service.ensureCashRegisterIsOpen(workshopId);

        verify(workshopRepository).lockById(workshopId);
    }

    @Test
    void theGuardRejectsSalesOnceTheDayIsClosed() {
        withLockableWorkshop();
        when(repository.existsByWorkshopIdAndBusinessDate(workshopId, TODAY))
                .thenReturn(true);

        assertThatThrownBy(() -> service.ensureCashRegisterIsOpen(workshopId))
                .isInstanceOf(ValidationException.class)
                .hasMessage("La caja del dia ya fue cerrada. "
                        + "No se pueden registrar nuevas ventas ni cobros");
    }

    /** El bloqueo mira la fecha operativa del reloj, no una que envie nadie. */
    @Test
    void theGuardAsksForTheBusinessDateResolvedByTheClock() {
        withLockableWorkshop();
        when(repository.existsByWorkshopIdAndBusinessDate(workshopId, TODAY))
                .thenReturn(false);

        service.ensureCashRegisterIsOpen(workshopId);

        verify(repository).existsByWorkshopIdAndBusinessDate(workshopId, TODAY);
    }

    // ── Historico ────────────────────────────────────────────────

    @Test
    void aHistoricalClosureIsAlwaysLookedUpTogetherWithItsWorkshop() {
        UUID closureId = UUID.randomUUID();
        when(repository.findByIdAndWorkshopId(closureId, workshopId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(closureId))
                .isInstanceOf(EntityNotFoundException.class);

        verify(repository).findByIdAndWorkshopId(closureId, workshopId);
    }

    /** El historico se lee del snapshot: el calculador no participa. */
    @Test
    void readingAHistoricalClosureNeverRecalculatesItAgainstSales() {
        UUID closureId = UUID.randomUUID();
        CashClosure closure = snapshot(new BigDecimal("-20.00"));
        closure.setId(closureId);
        when(repository.findByIdAndWorkshopId(closureId, workshopId))
                .thenReturn(Optional.of(closure));

        CashClosureResponseDTO response = service.get(closureId);

        assertThat(response.invoicing().totalInvoiced()).isEqualByComparingTo("20000.00");
        assertThat(response.cashReconciliation().difference()).isEqualByComparingTo("-20.00");
        assertThat(response.alreadyClosed()).isTrue();
        verify(calculator, never()).calculate(any(), any());
    }

    private CashClosure snapshot(BigDecimal difference) {
        return CashClosure.builder()
                .workshop(workshop())
                .closedBy(employee())
                .businessDate(TODAY)
                .openingCash(new BigDecimal("500.00"))
                .invoiceCount(15).invoiceTotal(new BigDecimal("12000.00"))
                .exchangeInvoiceCount(3).exchangeInvoiceTotal(new BigDecimal("8000.00"))
                .totalInvoiced(new BigDecimal("20000.00"))
                .cashPaymentCount(10).cashPayments(new BigDecimal("7000.00"))
                .cardPaymentCount(6).cardPayments(new BigDecimal("6000.00"))
                .transferPaymentCount(4).transferPayments(new BigDecimal("5000.00"))
                .totalCollected(new BigDecimal("18000.00"))
                .exchangeCollectedCount(2)
                .exchangeCollectedAmount(new BigDecimal("3000.00"))
                .previousExchangeCollectedAmount(new BigDecimal("1000.00"))
                .pendingExchangeAmount(new BigDecimal("25000.00"))
                .expectedCash(new BigDecimal("7500.00"))
                .countedCash(new BigDecimal("7480.00"))
                .difference(difference)
                .build();
    }

    // ── Rango ────────────────────────────────────────────────────

    @Test
    void anInvertedOrOversizedRangeIsRejectedBeforeTouchingTheDatabase() {
        assertThatThrownBy(() -> service.getSummary(
                LocalDate.of(2026, 8, 31), LocalDate.of(2026, 8, 1)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("posterior");

        assertThatThrownBy(() -> service.getSummary(
                LocalDate.of(2020, 1, 1), LocalDate.of(2026, 8, 1)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("366");

        assertThatThrownBy(() -> service.getRange(null, LocalDate.of(2026, 8, 1)))
                .isInstanceOf(ValidationException.class);
    }

    /**
     * La cartera final del rango es el saldo del ULTIMO cierre, no la suma de
     * los saldos diarios: cada uno es una foto del total pendiente.
     */
    @Test
    void theRangeTakesTheEndingPortfolioFromTheMostRecentClosure() {
        LocalDate from = LocalDate.of(2026, 8, 1);
        LocalDate to = LocalDate.of(2026, 8, 3);

        when(repository.summarizeRange(workshopId, from, to)).thenReturn(summaryRow());
        when(repository.findDaySummaries(workshopId, from, to)).thenReturn(List.of());
        when(repository.findPendingExchangeAmounts(
                eq(workshopId), eq(from), eq(to), any(Pageable.class)))
                .thenReturn(List.of(new BigDecimal("25000.00")));

        CashClosureSummaryResponseDTO summary = service.getSummary(from, to);

        assertThat(summary.receivables().endingPendingAmount())
                .isEqualByComparingTo("25000.00");
        assertThat(summary.closureCount()).isEqualTo(3);
        assertThat(summary.balance().netCashDifference()).isEqualByComparingTo("-15.00");
        assertThat(summary.balance().balancedClosures()).isEqualTo(1);
        assertThat(summary.balance().shortClosures()).isEqualTo(1);
        assertThat(summary.balance().overClosures()).isEqualTo(1);
    }

    @Test
    void aRangeWithoutClosuresReportsZeroPendingInsteadOfFailing() {
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2026, 1, 5);

        when(repository.summarizeRange(workshopId, from, to)).thenReturn(emptySummaryRow());
        when(repository.findDaySummaries(workshopId, from, to)).thenReturn(List.of());
        when(repository.findPendingExchangeAmounts(
                eq(workshopId), eq(from), eq(to), any(Pageable.class)))
                .thenReturn(List.of());

        CashClosureSummaryResponseDTO summary = service.getSummary(from, to);

        assertThat(summary.closureCount()).isZero();
        assertThat(summary.receivables().endingPendingAmount()).isEqualByComparingTo("0.00");
        assertThat(summary.days()).isEmpty();
    }

    private CashClosureSummaryProjection summaryRow() {
        return projection(3, new BigDecimal("-15.00"), 1, 1, 1);
    }

    private CashClosureSummaryProjection emptySummaryRow() {
        return projection(0, BigDecimal.ZERO, 0, 0, 0);
    }

    private CashClosureSummaryProjection projection(
            long closures, BigDecimal net, long balanced, long shortOnes, long over) {
        return new CashClosureSummaryProjection() {
            @Override
            public long getClosureCount() {
                return closures;
            }

            @Override
            public long getInvoiceCount() {
                return 0;
            }

            @Override
            public BigDecimal getInvoiceTotal() {
                return BigDecimal.ZERO;
            }

            @Override
            public long getExchangeInvoiceCount() {
                return 0;
            }

            @Override
            public BigDecimal getExchangeInvoiceTotal() {
                return BigDecimal.ZERO;
            }

            @Override
            public BigDecimal getTotalInvoiced() {
                return BigDecimal.ZERO;
            }

            @Override
            public long getCashPaymentCount() {
                return 0;
            }

            @Override
            public BigDecimal getCashPayments() {
                return BigDecimal.ZERO;
            }

            @Override
            public long getCardPaymentCount() {
                return 0;
            }

            @Override
            public BigDecimal getCardPayments() {
                return BigDecimal.ZERO;
            }

            @Override
            public long getTransferPaymentCount() {
                return 0;
            }

            @Override
            public BigDecimal getTransferPayments() {
                return BigDecimal.ZERO;
            }

            @Override
            public BigDecimal getTotalCollected() {
                return BigDecimal.ZERO;
            }

            @Override
            public long getExchangeCollectedCount() {
                return 0;
            }

            @Override
            public BigDecimal getExchangeCollectedAmount() {
                return BigDecimal.ZERO;
            }

            @Override
            public long getBalancedClosures() {
                return balanced;
            }

            @Override
            public long getShortClosures() {
                return shortOnes;
            }

            @Override
            public long getOverClosures() {
                return over;
            }

            @Override
            public BigDecimal getNetCashDifference() {
                return net;
            }
        };
    }

    // ── Arqueo derivado ──────────────────────────────────────────

    @Test
    void theBalanceStatusIsDerivedFromTheSignOfTheDifference() {
        assertThat(CashBalanceStatus.of(new BigDecimal("0.00")))
                .isEqualTo(CashBalanceStatus.BALANCED);
        assertThat(CashBalanceStatus.of(new BigDecimal("-0.01")))
                .isEqualTo(CashBalanceStatus.SHORT);
        assertThat(CashBalanceStatus.of(new BigDecimal("5.00")))
                .isEqualTo(CashBalanceStatus.OVER);
    }

    @Test
    void aClosureFileIsNamedAfterItsBusinessDate() {
        UUID closureId = UUID.randomUUID();
        CashClosure closure = snapshot(BigDecimal.ZERO);
        closure.setId(closureId);
        closure.setCreatedAt(LocalDateTime.of(2026, 8, 20, 19, 0));
        when(repository.findByIdAndWorkshopId(closureId, workshopId))
                .thenReturn(Optional.of(closure));
        when(reportService.renderDaily(any(), any(), eq(false), any()))
                .thenReturn(new byte[] { 1 });

        CashClosureDocumentDTO document = service.document(closureId);

        assertThat(document.fileName()).isEqualTo("cierre-caja-2026-08-20.pdf");
        verify(reportService).renderDaily(any(), any(), eq(false), eq("ANA LOPEZ"));
    }

    @Test
    void theRangeReportIsNamedAfterBothEnds() {
        LocalDate from = LocalDate.of(2026, 8, 1);
        LocalDate to = LocalDate.of(2026, 8, 31);

        when(repository.summarizeRange(workshopId, from, to)).thenReturn(emptySummaryRow());
        when(repository.findDaySummaries(workshopId, from, to)).thenReturn(List.of());
        when(repository.findPendingExchangeAmounts(
                eq(workshopId), eq(from), eq(to), any(Pageable.class)))
                .thenReturn(List.of());
        when(currentUserService.getCurrentWorkshop()).thenReturn(workshop());
        when(reportService.renderSummary(any(), any())).thenReturn(new byte[] { 1 });

        CashClosureDocumentDTO document = service.summaryDocument(from, to);

        assertThat(document.fileName())
                .isEqualTo("resumen-caja-2026-08-01-a-2026-08-31.pdf");
    }

    /** Zona de negocio: a las 18:45 de Guatemala la fecha operativa sigue siendo hoy. */
    @Test
    void theBusinessDateFollowsTheWorkshopTimeZoneAndNotTheServerDefault() {
        withFigures();
        when(repository.findByWorkshopIdAndBusinessDate(workshopId, TODAY))
                .thenReturn(Optional.empty());
        assertThat(ZoneId.of("America/Guatemala")).isEqualTo(TimeConfig.BUSINESS_ZONE);
        assertThat(service.getPreview().businessDate()).isEqualTo(TODAY);
    }

}
