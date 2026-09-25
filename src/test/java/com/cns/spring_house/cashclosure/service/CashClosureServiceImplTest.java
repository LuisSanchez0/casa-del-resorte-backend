package com.cns.spring_house.cashclosure.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.cns.spring_house.cashclosure.dto.CashClosureCollectionsDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureExchangeDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureInvoicingDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureResponseDTO;
import com.cns.spring_house.cashclosure.dto.ClosureExchangeInvoiceDTO;
import com.cns.spring_house.cashclosure.dto.ClosurePaymentMethodDTO;
import com.cns.spring_house.cashclosure.dto.ClosureSoldItemDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureRefundsDTO;
import com.cns.spring_house.cashclosure.dto.ClosureRefundMethodDTO;
import com.cns.spring_house.cashclosure.entity.CashClosure;
import com.cns.spring_house.cashclosure.entity.ExchangeInvoiceScope;
import com.cns.spring_house.cashclosure.mapper.CashClosureMapper;
import com.cns.spring_house.cashclosure.report.CashClosureReportService;
import com.cns.spring_house.cashclosure.repository.CashClosureRepository;
import com.cns.spring_house.employee.entity.Employee;
import com.cns.spring_house.family.entity.FamilyCategory;
import com.cns.spring_house.sale.entity.PaymentMethod;
import com.cns.spring_house.shared.config.time.TimeConfig;
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
 * no un mock: el volcado de las cifras al snapshot vive en el, y sustituirlo por
 * un doble dejaria sin comprobar justo lo que importa.
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
                repository, calculator, new CashClosureProductGrouper(),
                new CashClosureMapper(), reportService,
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

    private final UUID productId = UUID.randomUUID();
    private final UUID familyId = UUID.randomUUID();
    private final UUID brandId = UUID.randomUUID();
    private final UUID modelId = UUID.randomUUID();
    private final UUID pendingExchangeId = UUID.randomUUID();

    /**
     * El ejemplo del enunciado: Q5,000 en facturas normales, Q2,000 emitidos a
     * credito y sin cobrar, y Q1,000 de una cambiaria antigua cobrada hoy. El
     * total cobrado es Q6,000.
     */
    private PeriodCashFigures figures() {
        return new PeriodCashFigures(
                TODAY,
                List.of(new ClosureSoldItemDTO(
                        productId, "RESORTE DELANTERO", FamilyCategory.NORMAL,
                        4L, new BigDecimal("7000.00"))),
                // Las dimensiones con las que el snapshot agrupara despues.
                Map.of(productId, new ClosureProductDimensions(
                        familyId, "HOJA DE RESORTE",
                        brandId, "Toyota",
                        modelId, "Hilux")),
                new BigDecimal("7000.00"),
                new CashClosureInvoicingDTO(
                        1L, new BigDecimal("5000.00"),
                        1L, new BigDecimal("2000.00"),
                        new BigDecimal("7000.00")),
                new CashClosureExchangeDTO(
                        1L, new BigDecimal("2000.00"), new BigDecimal("2000.00"),
                        1L, new BigDecimal("1000.00"), new BigDecimal("1000.00"),
                        List.of(new ClosureExchangeInvoiceDTO(
                                pendingExchangeId, "AAAAAAAA", "TRANSPORTES",
                                TODAY.atTime(11, 0), new BigDecimal("2000.00"),
                                false, null, null)),
                        List.of(new ClosureExchangeInvoiceDTO(
                                UUID.randomUUID(), "BBBBBBBB", "FERRETERIA",
                                TODAY.minusDays(1).atTime(11, 0),
                                new BigDecimal("1000.00"), true,
                                TODAY.atTime(15, 0), PaymentMethod.CASH))),
                new CashClosureCollectionsDTO(
                        List.of(
                                new ClosurePaymentMethodDTO(
                                        PaymentMethod.CASH, 2L, new BigDecimal("6000.00")),
                                new ClosurePaymentMethodDTO(
                                        PaymentMethod.CARD, 0L, new BigDecimal("0.00")),
                                new ClosurePaymentMethodDTO(
                                        PaymentMethod.TRANSFER, 0L, new BigDecimal("0.00"))),
                        new BigDecimal("6000.00")),
                new CashClosureRefundsDTO(
                        List.of(
                                new ClosureRefundMethodDTO(
                                        PaymentMethod.CASH, 0L, new BigDecimal("0.00")),
                                new ClosureRefundMethodDTO(
                                        PaymentMethod.CARD, 0L, new BigDecimal("0.00")),
                                new ClosureRefundMethodDTO(
                                        PaymentMethod.TRANSFER, 0L, new BigDecimal("0.00"))),
                        0L, new BigDecimal("0.00"), List.of()));
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

    // ── Previsualizacion ─────────────────────────────────────────

    @Test
    void thePreviewUsesTheWorkshopFromTheTokenAndTheDateFromTheClock() {
        withFigures();
        when(repository.findByWorkshopIdAndBusinessDate(workshopId, TODAY))
                .thenReturn(Optional.empty());

        CashClosureResponseDTO preview = service.getPreview();

        assertThat(preview.businessDate()).isEqualTo(TODAY);
        assertThat(preview.alreadyClosed()).isFalse();
        assertThat(preview.id()).isNull();
        assertThat(preview.collections().totalCollected()).isEqualByComparingTo("6000.00");
        verify(calculator).calculate(workshopId, TODAY);
    }

    /** Consultar la previsualizacion no cierra la caja ni escribe una fila. */
    @Test
    void thePreviewNeverPersistsAnything() {
        withFigures();
        when(repository.findByWorkshopIdAndBusinessDate(workshopId, TODAY))
                .thenReturn(Optional.empty());

        service.getPreview();

        verify(repository, never()).save(any());
        verify(repository, never()).saveAndFlush(any());
        verifyNoInteractions(workshopRepository);
    }

    @Test
    void thePreviewReportsAnExistingClosureSoTheScreenCanOfferTheSnapshot() {
        withFigures();
        CashClosure existing = CashClosure.builder()
                .workshop(workshop())
                .closedBy(employee())
                .businessDate(TODAY)
                .build();
        existing.setId(UUID.randomUUID());
        when(repository.findByWorkshopIdAndBusinessDate(workshopId, TODAY))
                .thenReturn(Optional.of(existing));

        CashClosureResponseDTO preview = service.getPreview();

        assertThat(preview.alreadyClosed()).isTrue();
        assertThat(preview.id()).isEqualTo(existing.getId());
    }

    // ── Cierre ───────────────────────────────────────────────────

    /**
     * El cierre vuelca TODO el detalle en el snapshot: articulos, documentos
     * cambiarios en sus dos papeles y formas de pago. Sin ese detalle, el
     * historico no podria reconstruir el dia sin volver a consultar `sale`.
     */
    @Test
    void closingPersistsTheWholeSnapshot() {
        withAuthenticatedEmployee();
        withLockableWorkshop();
        withFigures();
        when(repository.existsByWorkshopIdAndBusinessDate(workshopId, TODAY))
                .thenReturn(false);
        when(repository.saveAndFlush(any(CashClosure.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.close();

        ArgumentCaptor<CashClosure> captor = ArgumentCaptor.forClass(CashClosure.class);
        verify(repository).saveAndFlush(captor.capture());
        CashClosure saved = captor.getValue();

        assertThat(saved.getBusinessDate()).isEqualTo(TODAY);
        assertThat(saved.getTotalInvoiced()).isEqualByComparingTo("7000.00");
        assertThat(saved.getTotalCollected()).isEqualByComparingTo("6000.00");
        assertThat(saved.getItemsBilledAmount()).isEqualByComparingTo("7000.00");
        // Lo emitido a credito y aun sin cobrar queda registrado como tal.
        assertThat(saved.getIssuedExchangePendingAmount()).isEqualByComparingTo("2000.00");

        assertThat(saved.getItems()).hasSize(1);
        assertThat(saved.getItems().get(0).getProductId()).isEqualTo(productId);
        assertThat(saved.getItems().get(0).getQuantity()).isEqualTo(4L);

        // Un documento por papel: emitido y cobrado son conjuntos distintos.
        assertThat(saved.getExchangeInvoices()).hasSize(2);
        assertThat(saved.getExchangeInvoices())
                .extracting(invoice -> invoice.getScope())
                .containsExactlyInAnyOrder(
                        ExchangeInvoiceScope.ISSUED, ExchangeInvoiceScope.COLLECTED);

        assertThat(saved.getPaymentMethods()).hasSize(PaymentMethod.values().length);
    }

    /**
     * El estado de cada cambiaria se CONGELA. La emitida hoy queda registrada
     * como pendiente: cobrarla manana no puede reescribir el cierre de hoy.
     */
    @Test
    void closingFreezesTheStatusOfEachExchangeInvoice() {
        withAuthenticatedEmployee();
        withLockableWorkshop();
        withFigures();
        when(repository.existsByWorkshopIdAndBusinessDate(workshopId, TODAY))
                .thenReturn(false);
        when(repository.saveAndFlush(any(CashClosure.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.close();

        ArgumentCaptor<CashClosure> captor = ArgumentCaptor.forClass(CashClosure.class);
        verify(repository).saveAndFlush(captor.capture());

        assertThat(captor.getValue().getExchangeInvoices())
                .filteredOn(invoice -> invoice.getScope() == ExchangeInvoiceScope.ISSUED)
                .singleElement()
                .satisfies(invoice -> {
                    assertThat(invoice.isCollected()).isFalse();
                    assertThat(invoice.getPaidAt()).isNull();
                    assertThat(invoice.getPaymentMethod()).isNull();
                    assertThat(invoice.getSaleId()).isEqualTo(pendingExchangeId);
                });
    }

    /**
     * El cerrojo del taller se toma ANTES de comprobar si el dia ya esta
     * cerrado. Al reves, dos cierres simultaneos leerian los dos "no cerrado".
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

        service.close();

        InOrder order = inOrder(workshopRepository, repository);
        order.verify(workshopRepository).lockById(workshopId);
        order.verify(repository).existsByWorkshopIdAndBusinessDate(workshopId, TODAY);
    }

    @Test
    void closingTwiceTheSameDayIsRejectedWithoutPersistingAnything() {
        withAuthenticatedEmployee();
        withLockableWorkshop();
        when(repository.existsByWorkshopIdAndBusinessDate(workshopId, TODAY))
                .thenReturn(true);

        assertThatThrownBy(() -> service.close())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("ya fue cerrada");

        verify(repository, never()).saveAndFlush(any());
        verifyNoInteractions(calculator);
    }

    // ── Bloqueo de ventas y cobros ───────────────────────────────

    @Test
    void theGuardLetsActivityThroughWhileTheRegisterIsOpen() {
        withLockableWorkshop();
        when(repository.existsByWorkshopIdAndBusinessDate(workshopId, TODAY))
                .thenReturn(false);

        service.ensureCashRegisterIsOpen(workshopId);

        verify(workshopRepository).lockById(workshopId);
    }

    /**
     * Cubre ventas Y cobros: una vez cerrada la caja del dia, ni se emite un
     * documento nuevo ni se cobra uno antiguo con fecha de hoy.
     */
    @Test
    void theGuardRejectsActivityOnceTheDayIsClosed() {
        withLockableWorkshop();
        when(repository.existsByWorkshopIdAndBusinessDate(workshopId, TODAY))
                .thenReturn(true);

        assertThatThrownBy(() -> service.ensureCashRegisterIsOpen(workshopId))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("ventas ni cobros");
    }

    // ── Lecturas historicas ──────────────────────────────────────

    /** Un cierre se busca SIEMPRE con su taller: el UUID solo no basta. */
    @Test
    void aHistoricalClosureIsAlwaysLookedUpTogetherWithItsWorkshop() {
        UUID id = UUID.randomUUID();
        CashClosure closure = snapshot(id);
        when(repository.findByIdAndWorkshopId(id, workshopId))
                .thenReturn(Optional.of(closure));

        service.get(id);

        verify(repository).findByIdAndWorkshopId(id, workshopId);
        verify(repository, never()).findById(any());
    }

    /**
     * Leer un cierre confirmado NUNCA lo recalcula. Es lo que hace inmutable el
     * historico: el calculador no llega a intervenir.
     */
    @Test
    void readingAHistoricalClosureNeverRecalculatesItAgainstSales() {
        UUID id = UUID.randomUUID();
        when(repository.findByIdAndWorkshopId(id, workshopId))
                .thenReturn(Optional.of(snapshot(id)));

        CashClosureResponseDTO response = service.get(id);

        assertThat(response.collections().totalCollected()).isEqualByComparingTo("6000.00");
        verifyNoInteractions(calculator);
    }

    /** Un cierre confirmado siempre se declara como tal. */
    @Test
    void aSnapshotAlwaysReportsItselfAsClosed() {
        UUID id = UUID.randomUUID();
        when(repository.findByIdAndWorkshopId(id, workshopId))
                .thenReturn(Optional.of(snapshot(id)));

        assertThat(service.get(id).alreadyClosed()).isTrue();
    }

    // ── Cierre mensual ───────────────────────────────────────────

    @Test
    void anInvalidMonthIsRejectedBeforeTouchingTheDatabase() {
        assertThatThrownBy(() -> service.getMonthly(2026, 0))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.getMonthly(2026, 13))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.getMonthly(1990, 5))
                .isInstanceOf(ValidationException.class);

        verifyNoInteractions(repository);
    }

    /** Un snapshot minimo con las cifras del ejemplo ya volcadas. */
    private CashClosure snapshot(UUID id) {
        CashClosure closure = CashClosure.builder()
                .workshop(workshop())
                .closedBy(employee())
                .businessDate(TODAY)
                .build();
        closure.setId(id);
        closure.setCreatedAt(LocalDateTime.of(2026, 8, 20, 19, 0));
        new CashClosureMapper().applyFigures(closure, figures());
        return closure;
    }

}
