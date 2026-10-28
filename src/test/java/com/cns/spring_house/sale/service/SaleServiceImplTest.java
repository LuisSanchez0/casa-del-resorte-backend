package com.cns.spring_house.sale.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Clock;

import com.cns.spring_house.cashclosure.service.CashClosureGuard;
import com.cns.spring_house.customer.entity.Customer;
import com.cns.spring_house.employee.entity.Employee;
import com.cns.spring_house.customer.repository.CustomerRepository;
import com.cns.spring_house.family.entity.Family;
import com.cns.spring_house.family.entity.FamilyCategory;
import com.cns.spring_house.inventory.entity.InventoryItem;
import com.cns.spring_house.job.entity.Job;
import com.cns.spring_house.job.entity.JobLog;
import com.cns.spring_house.product.entity.Product;
import com.cns.spring_house.product.repository.ProductRepository;
import com.cns.spring_house.sale.dto.AddSaleDetailRequestDTO;
import com.cns.spring_house.sale.dto.CancelSaleRequestDTO;
import com.cns.spring_house.sale.dto.CompleteSaleRequestDTO;
import com.cns.spring_house.sale.dto.CreateSaleRequestDTO;
import com.cns.spring_house.sale.dto.RegisterPaymentRequestDTO;
import com.cns.spring_house.sale.dto.ReverseSaleRequestDTO;
import com.cns.spring_house.sale.dto.SearchSaleRequestDTO;
import com.cns.spring_house.sale.entity.InvoiceType;
import com.cns.spring_house.sale.entity.PaymentMethod;
import com.cns.spring_house.sale.entity.PaymentStatus;
import com.cns.spring_house.sale.entity.Sale;
import com.cns.spring_house.sale.entity.TaxMode;
import com.cns.spring_house.sale.entity.SaleActionType;
import com.cns.spring_house.sale.entity.SaleDetail;
import com.cns.spring_house.sale.entity.SaleStatus;
import com.cns.spring_house.sale.handler.SaleDetailHandler;
import com.cns.spring_house.sale.handler.SaleDetailHandlerRegistry;
import com.cns.spring_house.sale.mapper.SaleMapper;
import com.cns.spring_house.sale.repository.SaleDetailRepository;
import com.cns.spring_house.sale.repository.SaleRepository;
import com.cns.spring_house.shared.config.time.TimeConfig;
import com.cns.spring_house.shared.exception.ValidationException;
import com.cns.spring_house.workresult.entity.WorkResult;
import com.cns.spring_house.workresult.entity.WorkResultStatus;
import com.cns.spring_house.workresult.service.WorkResultService;
import com.cns.spring_house.shared.security.CurrentUserService;
import com.cns.spring_house.workshop.entity.Workshop;

@ExtendWith(MockitoExtension.class)
class SaleServiceImplTest {

    @Mock
    private SaleRepository repository;

    @Mock
    private SaleMapper mapper;

    @Mock
    private SaleDetailHandlerRegistry registry;

    @Mock
    private CurrentUserService currentUserService;

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private SaleDetailRepository detailRepository;

    @Mock
    private WorkResultService workResultService;

    /**
     * Sin stubs: el mock no lanza, que es justo el escenario "la caja del dia
     * sigue abierta". Que crear, emitir o cobrar se rechacen con la caja cerrada
     * se comprueba contra la base de datos real, donde el cierre existe de
     * verdad y el cerrojo tambien.
     */
    @Mock
    private CashClosureGuard cashClosureGuard;

    /**
     * Reloj de la venta. Se estira como mock —y no como {@code Clock.fixed}—
     * porque {@code @InjectMocks} solo inyecta dobles; lo que importa es que
     * `completedAt` y `paidAt` dejen de depender del reloj de la maquina, asi
     * que aqui se fija a un instante conocido.
     */
    @Mock
    private Clock clock;

    @Mock
    private SaleDetailHandler handler;

    @InjectMocks
    private SaleServiceImpl service;

    /** Fecha de emision de una cambiaria ya facturada, anterior a su cobro. */
    private static final java.time.LocalDateTime ISSUED_AT =
            java.time.LocalDateTime.of(2026, 8, 1, 10, 30);

    private final UUID workshopId = UUID.randomUUID();
    /** UserAccount.id: lo que el JWT lleva como subject. */
    private final UUID userAccountId = UUID.randomUUID();
    /** Employee.id: distinto del anterior, como en producción. */
    private final UUID employeeId = UUID.randomUUID();
    private final Workshop workshop = Workshop.builder()
            .id(workshopId)
            .build();
    private final Employee currentEmployee = Employee.builder()
            .id(employeeId)
            .workshop(workshop)
            .build();

    /**
     * Cliente con NIT de las ventas abiertas de estas pruebas: una venta abierta
     * sin cliente estaria sin definir y no se podria emitir sin elegir NIT o CF,
     * que no es lo que se mide aqui.
     */
    private final Customer registeredCustomer = Customer.builder()
            .id(UUID.randomUUID())
            .nit("12345678")
            .name("CLIENTE CON NIT")
            .build();

    /** Posterior a {@link #ISSUED_AT}: cobrar nunca puede quedar antes de emitir. */
    private static final java.time.LocalDateTime NOW =
            java.time.LocalDateTime.of(2026, 8, 20, 18, 45);

    @BeforeEach
    void setUpClock() {
        // lenient: la mayoria de pruebas de esta clase no llega a sellar fechas,
        // y una comprobacion estricta las marcaria como stubbing innecesario.
        lenient().when(clock.instant()).thenReturn(
                NOW.atZone(TimeConfig.BUSINESS_ZONE).toInstant());
        lenient().when(clock.getZone()).thenReturn(TimeConfig.BUSINESS_ZONE);
    }

    @BeforeEach
    void setUpAuthentication() {
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

    private Product product(FamilyCategory category) {
        return Product.builder()
                .id(UUID.randomUUID())
                .name("PRODUCTO")
                .family(Family.builder().category(category).build())
                .build();
    }

    private WorkResult pendingFromJobLog(Product jobProduct) {
        return WorkResult.builder()
                .id(UUID.randomUUID())
                .workshop(workshop)
                .jobLog(JobLog.builder()
                        .id(UUID.randomUUID())
                        .job(Job.builder()
                                .id(UUID.randomUUID())
                                .workshop(workshop)
                                .product(jobProduct)
                                .build())
                        .build())
                .build();
    }

    /** Factura normal cobrada en efectivo: el caso por defecto de los tests. */
    private CompleteSaleRequestDTO invoiceCash() {
        return new CompleteSaleRequestDTO(
                InvoiceType.INVOICE, PaymentMethod.CASH);
    }

    /** Venta con NIT corriente: la identidad fiscal no es lo que se mide aqui. */
    private Sale saleWithDetail(SaleDetail detail) {
        Sale sale = Sale.builder()
                .customer(registeredCustomer)
                .id(UUID.randomUUID())
                .workshop(workshop)
                .build();
        detail.setSubTotal(BigDecimal.ZERO);
        sale.addDetail(detail);
        return sale;
    }

    @Test
    void addDetailLinksTheValidatedWorkResultToTheDetail() {
        Product jobProduct = product(FamilyCategory.JOB);
        WorkResult workResult = pendingFromJobLog(jobProduct);
        Sale sale = Sale.builder()
                .customer(registeredCustomer)
                .id(UUID.randomUUID())
                .workshop(workshop)
                .build();
        SaleDetail detail = SaleDetail.builder()
                .product(jobProduct)
                .subTotal(BigDecimal.TEN)
                .build();
        AddSaleDetailRequestDTO requestDTO = new AddSaleDetailRequestDTO(
                jobProduct.getId(), null, workResult.getId(), 1, null, null);

        when(repository.findByIdAndWorkshopId(sale.getId(), workshopId))
                .thenReturn(Optional.of(sale));
        when(productRepository.findById(jobProduct.getId()))
                .thenReturn(Optional.of(jobProduct));
        when(registry.get(FamilyCategory.JOB)).thenReturn(handler);
        doAnswer(invocation -> {
            sale.addDetail(detail);
            return detail;
        }).when(handler).addDetail(sale, requestDTO);
        when(workResultService.resolvePending(
                workResult.getId(), jobProduct.getId(), null))
                .thenReturn(workResult);
        when(repository.save(sale)).thenReturn(sale);

        service.addDetail(sale.getId(), requestDTO);

        assertThat(detail.getWorkResult()).isEqualTo(workResult);
    }

    /**
     * La unidad fisica de una pieza especial la decide Control: la caja no
     * puede senalarla, ni directamente ni como salida de un trabajo. El rechazo
     * ocurre antes de tocar el inventario o el trabajo.
     */
    @Test
    void addDetailRejectsChoosingThePhysicalUnitOfASpecialPiece() {
        Product specialProduct = product(FamilyCategory.SPECIAL);
        UUID itemId = UUID.randomUUID();
        Sale sale = Sale.builder()
                .customer(registeredCustomer)
                .id(UUID.randomUUID())
                .workshop(workshop)
                .build();

        when(repository.findByIdAndWorkshopId(sale.getId(), workshopId))
                .thenReturn(Optional.of(sale));
        when(productRepository.findById(specialProduct.getId()))
                .thenReturn(Optional.of(specialProduct));

        assertThatThrownBy(() -> service.addDetail(sale.getId(), new AddSaleDetailRequestDTO(
                specialProduct.getId(), itemId, null, 1, null, null)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Control entrega la unidad");
        assertThatThrownBy(() -> service.addDetail(sale.getId(), new AddSaleDetailRequestDTO(
                specialProduct.getId(), itemId, UUID.randomUUID(), 1, null, null)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Control entrega la unidad");

        verify(registry, never()).get(any());
        verify(workResultService, never()).resolvePending(any(), any(), any());
        verify(repository, never()).save(any());
    }

    @Test
    void addDetailWithoutWorkResultDoesNotTouchPendingRecords() {
        Product normalProduct = product(FamilyCategory.NORMAL);
        Sale sale = Sale.builder()
                .customer(registeredCustomer)
                .id(UUID.randomUUID())
                .workshop(workshop)
                .build();
        SaleDetail detail = SaleDetail.builder()
                .product(normalProduct)
                .subTotal(BigDecimal.TEN)
                .build();
        AddSaleDetailRequestDTO requestDTO = new AddSaleDetailRequestDTO(
                normalProduct.getId(), null, null, 2, null, null);

        when(repository.findByIdAndWorkshopId(sale.getId(), workshopId))
                .thenReturn(Optional.of(sale));
        when(productRepository.findById(normalProduct.getId()))
                .thenReturn(Optional.of(normalProduct));
        when(registry.get(FamilyCategory.NORMAL)).thenReturn(handler);
        doAnswer(invocation -> {
            sale.addDetail(detail);
            return detail;
        }).when(handler).addDetail(sale, requestDTO);
        when(repository.save(sale)).thenReturn(sale);

        service.addDetail(sale.getId(), requestDTO);

        assertThat(detail.getWorkResult()).isNull();
        verifyNoInteractions(workResultService);
    }

    @Test
    void completeSaleMarksOnlyTheSelectedWorkResult() {
        Product jobProduct = product(FamilyCategory.JOB);
        WorkResult selected = pendingFromJobLog(jobProduct);
        WorkResult otherPending = pendingFromJobLog(jobProduct);
        SaleDetail detail = SaleDetail.builder()
                .product(jobProduct)
                .workResult(selected)
                .build();
        Sale sale = saleWithDetail(detail);

        when(repository.findByIdAndWorkshopId(sale.getId(), workshopId))
                .thenReturn(Optional.of(sale));
        when(repository.save(sale)).thenReturn(sale);

        service.completeSale(sale.getId(), invoiceCash());

        verify(workResultService).markAsSold(
                selected.getId(), jobProduct.getId(), null);
        verify(workResultService, never()).markAsSold(
                org.mockito.ArgumentMatchers.eq(otherPending.getId()),
                any(), any());
        assertThat(otherPending.getStatus())
                .isEqualTo(WorkResultStatus.PENDING);
    }

    @Test
    void completeSaleMarksItemSourcedWorkResultWithItsItem() {
        Product specialProduct = product(FamilyCategory.SPECIAL);
        InventoryItem item = InventoryItem.builder()
                .id(UUID.randomUUID())
                .build();
        WorkResult workResult = WorkResult.builder()
                .id(UUID.randomUUID())
                .workshop(workshop)
                .item(item)
                .build();
        SaleDetail detail = SaleDetail.builder()
                .product(specialProduct)
                .item(item)
                .workResult(workResult)
                .build();
        Sale sale = saleWithDetail(detail);

        when(repository.findByIdAndWorkshopId(sale.getId(), workshopId))
                .thenReturn(Optional.of(sale));
        when(repository.save(sale)).thenReturn(sale);

        service.completeSale(sale.getId(), invoiceCash());

        verify(workResultService).markAsSold(
                workResult.getId(), specialProduct.getId(), item.getId());
    }

    @Test
    void completeSaleWithoutWorkResultKeepsWorkingForNormalSales() {
        SaleDetail detail = SaleDetail.builder()
                .product(product(FamilyCategory.NORMAL))
                .build();
        Sale sale = saleWithDetail(detail);

        when(repository.findByIdAndWorkshopId(sale.getId(), workshopId))
                .thenReturn(Optional.of(sale));
        when(repository.save(sale)).thenReturn(sale);

        service.completeSale(sale.getId(), invoiceCash());

        verifyNoInteractions(workResultService);
    }

    @Test
    void completeSaleFailsWhenTheWorkResultWasAlreadySoldByAnotherSale() {
        Product jobProduct = product(FamilyCategory.JOB);
        WorkResult workResult = pendingFromJobLog(jobProduct);
        SaleDetail detail = SaleDetail.builder()
                .product(jobProduct)
                .workResult(workResult)
                .build();
        Sale sale = saleWithDetail(detail);

        when(repository.findByIdAndWorkshopId(sale.getId(), workshopId))
                .thenReturn(Optional.of(sale));
        doThrow(new ValidationException(
                "El trabajo pendiente ya fue vendido o no esta disponible"))
                .when(workResultService).markAsSold(
                        workResult.getId(), jobProduct.getId(), null);

        assertThatThrownBy(() -> service.completeSale(sale.getId(), invoiceCash()))
                .isInstanceOf(ValidationException.class);

        verify(repository, never()).save(any());
        assertThat(workResult.getStatus())
                .isEqualTo(WorkResultStatus.PENDING);
    }

    @Test
    void completeSaleRejectsClosedSaleWithoutMarkingWorkResults() {
        Sale sale = Sale.builder()
                .taxMode(TaxMode.CF)
                .id(UUID.randomUUID())
                .status(SaleStatus.COMPLETED)
                .build();
        when(repository.findByIdAndWorkshopId(sale.getId(), workshopId))
                .thenReturn(Optional.of(sale));

        assertThatThrownBy(() -> service.completeSale(sale.getId(), invoiceCash()))
                .isInstanceOf(ValidationException.class);

        verifyNoInteractions(workResultService);
        verify(repository, never()).save(any());
    }

    @Test
    void createResolvesTheEmployeeThroughTheAuthenticatedUserAccount() {
        assertThat(userAccountId).isNotEqualTo(employeeId);
        UUID customerId = UUID.randomUUID();
        when(currentUserService.getCurrentEmployee()).thenReturn(currentEmployee);
        when(currentUserService.getCurrentWorkshop()).thenReturn(workshop);
        when(customerRepository.findById(customerId))
                .thenReturn(Optional.of(Customer.builder().id(customerId).build()));
        when(repository.save(any(Sale.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.create(new CreateSaleRequestDTO(customerId, null));

        ArgumentCaptor<Sale> saved = ArgumentCaptor.forClass(Sale.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getEmployee().getId()).isEqualTo(employeeId);
        // El id de la cuenta nunca debe acabar usándose como id de empleado.
        assertThat(saved.getValue().getEmployee().getId()).isNotEqualTo(userAccountId);
    }

    @Test
    void createTakesTheWorkshopFromTheAuthenticatedEmployee() {
        UUID customerId = UUID.randomUUID();
        when(currentUserService.getCurrentEmployee()).thenReturn(currentEmployee);
        when(currentUserService.getCurrentWorkshop()).thenReturn(workshop);
        when(customerRepository.findById(customerId))
                .thenReturn(Optional.of(Customer.builder().id(customerId).build()));
        when(repository.save(any(Sale.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.create(new CreateSaleRequestDTO(customerId, null));

        ArgumentCaptor<Sale> saved = ArgumentCaptor.forClass(Sale.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getWorkshop().getId()).isEqualTo(workshopId);
    }

    @Test
    void createFailsInAControlledWayWhenTheAccountHasNoEmployee() {
        when(currentUserService.getCurrentEmployee())
                .thenThrow(new ValidationException(
                        "El usuario autenticado no tiene un empleado asociado"));

        assertThatThrownBy(() -> service.create(
                new CreateSaleRequestDTO(UUID.randomUUID(), null)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("no tiene un empleado asociado");

        verify(repository, never()).save(any());
    }

    @Test
    void createFailsInAControlledWayWhenTheEmployeeHasNoWorkshop() {
        when(currentUserService.getCurrentEmployee()).thenReturn(currentEmployee);
        when(currentUserService.getCurrentWorkshop())
                .thenThrow(new ValidationException(
                        "El empleado autenticado no tiene un taller asociado"));

        assertThatThrownBy(() -> service.create(
                new CreateSaleRequestDTO(UUID.randomUUID(), null)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("no tiene un taller asociado");

        verify(repository, never()).save(any());
    }

    // ------------------------------------------------------------- placa

    private void stubCreate() {
        when(currentUserService.getCurrentEmployee()).thenReturn(currentEmployee);
        when(currentUserService.getCurrentWorkshop()).thenReturn(workshop);
        when(customerRepository.findById(any()))
                .thenReturn(Optional.of(Customer.builder()
                        .id(UUID.randomUUID())
                        .build()));
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
    }

    private Sale captureCreatedSale() {
        ArgumentCaptor<Sale> captor = ArgumentCaptor.forClass(Sale.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void aSaleCanBeCreatedWithoutPlate() {
        stubCreate();

        service.create(new CreateSaleRequestDTO(UUID.randomUUID(), null));

        assertThat(captureCreatedSale().getPlate()).isNull();
    }

    @Test
    void thePlateIsNormalizedBeforeBeingStored() {
        stubCreate();

        service.create(new CreateSaleRequestDTO(
                UUID.randomUUID(), "  p123abc  "));

        assertThat(captureCreatedSale().getPlate()).isEqualTo("P123ABC");
    }

    @Test
    void aBlankPlateIsStoredAsNullInsteadOfEmptyText() {
        stubCreate();

        service.create(new CreateSaleRequestDTO(UUID.randomUUID(), "   "));

        assertThat(captureCreatedSale().getPlate()).isNull();
    }

    /** La placa es informativa: no exige un VehicleMovement abierto. */
    @Test
    void aPlateWithoutVehicleMovementIsAccepted() {
        stubCreate();

        service.create(new CreateSaleRequestDTO(
                UUID.randomUUID(), "X999ZZZ"));

        assertThat(captureCreatedSale().getPlate()).isEqualTo("X999ZZZ");
        verifyNoInteractions(workResultService);
    }

    @Test
    void aNewSaleStartsOpenAndUnpaid() {
        stubCreate();

        service.create(new CreateSaleRequestDTO(UUID.randomUUID(), null));

        Sale sale = captureCreatedSale();
        assertThat(sale.getStatus()).isEqualTo(SaleStatus.PENDING);
        assertThat(sale.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(sale.getPaymentMethod()).isNull();
        assertThat(sale.getPaidAt()).isNull();
        assertThat(sale.getInvoiceType()).isNull();
    }

    // --------------------------------------------------- factura normal

    /**
     * Venta abierta con una linea: es el unico estado desde el que se puede
     * emitir un documento, porque una venta vacia no es facturable.
     */
    private Sale openSale() {
        Sale sale = openSaleThatIsNotSaved();
        when(repository.save(sale)).thenReturn(sale);
        return sale;
    }

    /** Igual, pero sin stub de guardado: para los casos que deben rechazarse. */
    private Sale openSaleThatIsNotSaved() {
        Sale sale = saleWithDetail(SaleDetail.builder()
                .product(product(FamilyCategory.NORMAL))
                .build());
        when(repository.findByIdAndWorkshopId(sale.getId(), workshopId))
                .thenReturn(Optional.of(sale));
        return sale;
    }

    @Test
    void aNormalInvoiceIsCompletedAndPaidInTheSameAct() {
        Sale sale = openSale();

        service.completeSale(sale.getId(), new CompleteSaleRequestDTO(
                InvoiceType.INVOICE, PaymentMethod.CARD));

        assertThat(sale.getStatus()).isEqualTo(SaleStatus.COMPLETED);
        assertThat(sale.getInvoiceType()).isEqualTo(InvoiceType.INVOICE);
        assertThat(sale.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(sale.getPaymentMethod()).isEqualTo(PaymentMethod.CARD);
        assertThat(sale.getCompletedAt()).isNotNull();
        assertThat(sale.getPaidAt()).isNotNull();
    }

    @Test
    void aNormalInvoiceAcceptsCashAndTransferToo() {
        Sale cashSale = openSale();
        service.completeSale(cashSale.getId(), new CompleteSaleRequestDTO(
                InvoiceType.INVOICE, PaymentMethod.CASH));
        assertThat(cashSale.getPaymentMethod()).isEqualTo(PaymentMethod.CASH);

        Sale transferSale = openSale();
        service.completeSale(transferSale.getId(), new CompleteSaleRequestDTO(
                InvoiceType.INVOICE, PaymentMethod.TRANSFER));
        assertThat(transferSale.getPaymentMethod())
                .isEqualTo(PaymentMethod.TRANSFER);
    }

    @Test
    void aNormalInvoiceRequiresThePaymentMethod() {
        Sale sale = openSaleThatIsNotSaved();

        assertThatThrownBy(() -> service.completeSale(
                sale.getId(),
                new CompleteSaleRequestDTO(InvoiceType.INVOICE, null)))
                .isInstanceOf(ValidationException.class)
                .hasMessage("El metodo de pago es obligatorio para una factura normal");

        verify(repository, never()).save(any());
    }

    // ------------------------------------------------ factura cambiaria

    @Test
    void anExchangeInvoiceIsIssuedWithoutBeingPaid() {
        Sale sale = openSale();

        service.completeSale(sale.getId(), new CompleteSaleRequestDTO(
                InvoiceType.EXCHANGE_INVOICE, null));

        // Emitida: ya no es editable, pero sigue debiendo dinero.
        assertThat(sale.getStatus()).isEqualTo(SaleStatus.COMPLETED);
        assertThat(sale.getInvoiceType()).isEqualTo(InvoiceType.EXCHANGE_INVOICE);
        assertThat(sale.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(sale.getPaymentMethod()).isNull();
        assertThat(sale.getPaidAt()).isNull();
        assertThat(sale.getCompletedAt()).isNotNull();
    }

    @Test
    void anExchangeInvoiceRejectsAPaymentMethodAtIssueTime() {
        Sale sale = openSaleThatIsNotSaved();

        assertThatThrownBy(() -> service.completeSale(
                sale.getId(),
                new CompleteSaleRequestDTO(
                        InvoiceType.EXCHANGE_INVOICE, PaymentMethod.CASH)))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Una factura cambiaria se emite sin metodo de pago");
    }

    @Test
    void anIssuedInvoiceIsNoLongerEditable() {
        Sale sale = Sale.builder()
                .taxMode(TaxMode.CF)
                .id(UUID.randomUUID())
                .workshop(workshop)
                .status(SaleStatus.COMPLETED)
                .paymentStatus(PaymentStatus.PENDING)
                .build();
        when(repository.findByIdAndWorkshopId(sale.getId(), workshopId))
                .thenReturn(Optional.of(sale));

        assertThatThrownBy(() -> service.addDetail(
                sale.getId(),
                new AddSaleDetailRequestDTO(
                        UUID.randomUUID(), null, null, 1, null, null)))
                .isInstanceOf(ValidationException.class)
                .hasMessage("La venta no se encuentra abierta");

        verifyNoInteractions(registry);
    }

    // ------------------------------------------------------------ cobro

    private Sale issuedExchangeInvoice() {
        Sale sale = Sale.builder()
                .taxMode(TaxMode.CF)
                .id(UUID.randomUUID())
                .workshop(workshop)
                .status(SaleStatus.COMPLETED)
                .invoiceType(InvoiceType.EXCHANGE_INVOICE)
                .paymentStatus(PaymentStatus.PENDING)
                .completedAt(ISSUED_AT)
                .build();
        when(repository.findByIdAndWorkshopId(sale.getId(), workshopId))
                .thenReturn(Optional.of(sale));
        return sale;
    }

    @Test
    void registeringThePaymentClosesTheDebtWithoutTouchingStockOrWorkResults() {
        Sale sale = issuedExchangeInvoice();
        when(repository.save(sale)).thenReturn(sale);

        service.registerPayment(sale.getId(),
                new RegisterPaymentRequestDTO(PaymentMethod.TRANSFER));

        assertThat(sale.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(sale.getPaymentMethod()).isEqualTo(PaymentMethod.TRANSFER);
        assertThat(sale.getPaidAt()).isNotNull();
        assertThat(sale.getStatus()).isEqualTo(SaleStatus.COMPLETED);
        // El stock y los WorkResult se consumieron al emitir, no al cobrar.
        verifyNoInteractions(workResultService, registry);
    }

    @Test
    void aSaleCannotBePaidTwice() {
        Sale sale = Sale.builder()
                .taxMode(TaxMode.CF)
                .id(UUID.randomUUID())
                .workshop(workshop)
                .status(SaleStatus.COMPLETED)
                .invoiceType(InvoiceType.EXCHANGE_INVOICE)
                .paymentStatus(PaymentStatus.PAID)
                .build();
        when(repository.findByIdAndWorkshopId(sale.getId(), workshopId))
                .thenReturn(Optional.of(sale));

        assertThatThrownBy(() -> service.registerPayment(
                sale.getId(),
                new RegisterPaymentRequestDTO(PaymentMethod.CASH)))
                .isInstanceOf(ValidationException.class)
                .hasMessage("La venta ya fue pagada");

        verify(repository, never()).save(any());
    }

    @Test
    void anOpenSaleCannotRegisterAPayment() {
        Sale sale = Sale.builder()
                .customer(registeredCustomer)
                .id(UUID.randomUUID())
                .workshop(workshop)
                .build();
        when(repository.findByIdAndWorkshopId(sale.getId(), workshopId))
                .thenReturn(Optional.of(sale));

        assertThatThrownBy(() -> service.registerPayment(
                sale.getId(),
                new RegisterPaymentRequestDTO(PaymentMethod.CASH)))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Solo se puede registrar el pago de una venta finalizada");
    }

    /** El cobro posterior es exclusivo del credito: la factura normal ya se cobro. */
    @Test
    void aNormalInvoiceCannotGoThroughTheLaterPaymentFlow() {
        Sale sale = Sale.builder()
                .taxMode(TaxMode.CF)
                .id(UUID.randomUUID())
                .workshop(workshop)
                .status(SaleStatus.COMPLETED)
                .invoiceType(InvoiceType.INVOICE)
                .paymentStatus(PaymentStatus.PENDING)
                .build();
        when(repository.findByIdAndWorkshopId(sale.getId(), workshopId))
                .thenReturn(Optional.of(sale));

        assertThatThrownBy(() -> service.registerPayment(
                sale.getId(),
                new RegisterPaymentRequestDTO(PaymentMethod.TRANSFER)))
                .isInstanceOf(ValidationException.class)
                .hasMessage(
                        "Solo las facturas cambiarias pueden registrar un pago posterior");

        verify(repository, never()).save(any());
        assertThat(sale.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(sale.getPaymentMethod()).isNull();
    }

    /**
     * Emitir y cobrar son eventos distintos: la fecha de emision es la del
     * documento y no puede moverse porque se cobre semanas despues.
     */
    @Test
    void registeringThePaymentNeverMovesTheIssueDate() {
        Sale sale = issuedExchangeInvoice();
        when(repository.save(sale)).thenReturn(sale);

        service.registerPayment(sale.getId(),
                new RegisterPaymentRequestDTO(PaymentMethod.CASH));

        assertThat(sale.getCompletedAt()).isEqualTo(ISSUED_AT);
        assertThat(sale.getPaidAt()).isAfter(ISSUED_AT);
        assertThat(sale.getInvoiceType()).isEqualTo(InvoiceType.EXCHANGE_INVOICE);
        assertThat(sale.getStatus()).isEqualTo(SaleStatus.COMPLETED);
    }

    // ------------------------------------------------------ venta vacia

    /**
     * Emitir un documento sin lineas dejaba una venta COMPLETED de total cero,
     * ya no editable, que solo se podia descartar a mano en la base.
     */
    @Test
    void anEmptySaleCannotBeCompleted() {
        Sale sale = Sale.builder()
                .customer(registeredCustomer)
                .id(UUID.randomUUID())
                .workshop(workshop)
                .build();
        when(repository.findByIdAndWorkshopId(sale.getId(), workshopId))
                .thenReturn(Optional.of(sale));

        assertThatThrownBy(() -> service.completeSale(sale.getId(), invoiceCash()))
                .isInstanceOf(ValidationException.class)
                .hasMessage("No se puede completar una venta sin detalles");

        // Ni estado, ni fechas, ni cobro: la venta sigue abierta y editable.
        assertThat(sale.getStatus()).isEqualTo(SaleStatus.PENDING);
        assertThat(sale.getInvoiceType()).isNull();
        assertThat(sale.getCompletedAt()).isNull();
        assertThat(sale.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(sale.getPaymentMethod()).isNull();
        assertThat(sale.getPaidAt()).isNull();
        verify(repository, never()).save(any());
        verifyNoInteractions(workResultService);
    }

    @Test
    void anEmptyExchangeInvoiceIsRejectedToo() {
        Sale sale = Sale.builder()
                .customer(registeredCustomer)
                .id(UUID.randomUUID())
                .workshop(workshop)
                .build();
        when(repository.findByIdAndWorkshopId(sale.getId(), workshopId))
                .thenReturn(Optional.of(sale));

        assertThatThrownBy(() -> service.completeSale(
                sale.getId(),
                new CompleteSaleRequestDTO(InvoiceType.EXCHANGE_INVOICE, null)))
                .isInstanceOf(ValidationException.class)
                .hasMessage("No se puede completar una venta sin detalles");

        assertThat(sale.getStatus()).isEqualTo(SaleStatus.PENDING);
        verify(repository, never()).save(any());
    }

    // ------------------------------------------------- cartera cambiaria

    /** Devuelve el Pageable con el que el servicio consulto la cartera. */
    private org.springframework.data.domain.Pageable capturePortfolioQuery(
            PaymentStatus paymentStatus, int page, int size) {
        when(repository.findExchangeInvoiceSummaries(
                org.mockito.ArgumentMatchers.eq(workshopId),
                org.mockito.ArgumentMatchers.eq(paymentStatus),
                any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(org.springframework.data.domain.Page.empty());

        service.getExchangeInvoices(paymentStatus, page, size);

        ArgumentCaptor<org.springframework.data.domain.Pageable> pageable =
                ArgumentCaptor.forClass(
                        org.springframework.data.domain.Pageable.class);
        verify(repository).findExchangeInvoiceSummaries(
                org.mockito.ArgumentMatchers.eq(workshopId),
                org.mockito.ArgumentMatchers.eq(paymentStatus),
                pageable.capture());
        return pageable.getValue();
    }

    /**
     * El taller lo pone el token, no la peticion, y el filtro de cobro viaja tal
     * cual: nulo significa toda la cartera.
     */
    @Test
    void theExchangeInvoicePortfolioIsScopedToTheTokenWorkshopAndToCredit() {
        assertThat(capturePortfolioQuery(null, 0, 20)).isNotNull();

        // La consulta ya limita a EXCHANGE_INVOICE por definicion; lo que el
        // servicio no puede delegar es el taller.
        verify(repository).findExchangeInvoiceSummaries(
                org.mockito.ArgumentMatchers.eq(workshopId),
                org.mockito.ArgumentMatchers.isNull(),
                any(org.springframework.data.domain.Pageable.class));
    }

    @Test
    void theExchangeInvoicePortfolioCanBeNarrowedToWhatIsStillOwed() {
        capturePortfolioQuery(PaymentStatus.PENDING, 0, 20);

        verify(repository).findExchangeInvoiceSummaries(
                org.mockito.ArgumentMatchers.eq(workshopId),
                org.mockito.ArgumentMatchers.eq(PaymentStatus.PENDING),
                any(org.springframework.data.domain.Pageable.class));
    }

    @Test
    void theExchangeInvoicePortfolioCanBeNarrowedToWhatWasAlreadyCollected() {
        capturePortfolioQuery(PaymentStatus.PAID, 0, 20);

        verify(repository).findExchangeInvoiceSummaries(
                org.mockito.ArgumentMatchers.eq(workshopId),
                org.mockito.ArgumentMatchers.eq(PaymentStatus.PAID),
                any(org.springframework.data.domain.Pageable.class));
    }

    /**
     * El orden no lo decide el cliente y lleva desempate: sin un segundo criterio
     * la paginacion puede repetir una fila y perder otra entre paginas.
     */
    @Test
    void theCarteraIsAlwaysOrderedNewestFirstWithAStableTieBreak() {
        org.springframework.data.domain.Pageable pageable =
                capturePortfolioQuery(null, 2, 15);

        assertThat(pageable.getPageNumber()).isEqualTo(2);
        assertThat(pageable.getPageSize()).isEqualTo(15);
        assertThat(pageable.getSort()).isEqualTo(
                org.springframework.data.domain.Sort.by(
                        org.springframework.data.domain.Sort.Order.desc("completedAt"),
                        org.springframework.data.domain.Sort.Order.desc("id")));
    }

    @Test
    void anAbsurdPageRequestIsRejectedBeforeQuerying() {
        assertThatThrownBy(() -> service.getExchangeInvoices(null, 0, 101))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("entre 1 y 100");
        assertThatThrownBy(() -> service.getExchangeInvoices(null, 0, 0))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.getExchangeInvoices(null, -1, 20))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("no puede ser negativo");

        verifyNoInteractions(repository);
    }

    // ------------------------------------------- buscador de facturas

    /**
     * Captura los argumentos con los que el servicio consulta las sugerencias.
     */
    private org.springframework.data.domain.Pageable captureSuggestionQuery(
            String query, Integer limit, ArgumentCaptor<String> pattern) {
        when(repository.findCompletedSaleSummaries(
                org.mockito.ArgumentMatchers.eq(workshopId),
                org.mockito.ArgumentMatchers.nullable(String.class),
                any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(java.util.List.of());

        service.searchCompletedSales(query, limit);

        ArgumentCaptor<org.springframework.data.domain.Pageable> pageable =
                ArgumentCaptor.forClass(
                        org.springframework.data.domain.Pageable.class);
        verify(repository).findCompletedSaleSummaries(
                org.mockito.ArgumentMatchers.eq(workshopId),
                pattern.capture(),
                pageable.capture());
        return pageable.getValue();
    }

    /**
     * El comodin lo pone el servicio y el texto viaja en minusculas: la consulta
     * compara contra `lower(...)`, asi que buscar "550E84" y "550e84" tiene que
     * encontrar lo mismo.
     */
    @Test
    void theSuggestionSearchNormalisesTheFragmentAndAddsTheWildcards() {
        ArgumentCaptor<String> pattern = ArgumentCaptor.forClass(String.class);

        captureSuggestionQuery("  550E84  ", null, pattern);

        assertThat(pattern.getValue()).isEqualTo("%550e84%");
    }

    /**
     * Los comodines de LIKE que escriba una persona viajan LITERALES. Sin esto,
     * buscar "%" devolvia todas las facturas completadas del taller.
     */
    @Test
    void theSuggestionSearchNeutralisesTheLikeWildcards() {
        ArgumentCaptor<String> pattern = ArgumentCaptor.forClass(String.class);

        captureSuggestionQuery("50%_\\", null, pattern);

        assertThat(pattern.getValue()).isEqualTo("%50\\%\\_\\\\%");
    }

    /** Sin texto se ofrecen las mas recientes, no un filtro vacio que no case con nada. */
    @Test
    void anEmptyFragmentAsksForTheMostRecentInvoices() {
        ArgumentCaptor<String> pattern = ArgumentCaptor.forClass(String.class);

        captureSuggestionQuery("   ", null, pattern);

        assertThat(pattern.getValue()).isNull();
    }

    /** El taller sale del token: un query param no puede sugerir facturas ajenas. */
    @Test
    void theSuggestionSearchIsScopedToTheTokenWorkshop() {
        ArgumentCaptor<String> pattern = ArgumentCaptor.forClass(String.class);

        captureSuggestionQuery("acme", null, pattern);

        verify(repository).findCompletedSaleSummaries(
                org.mockito.ArgumentMatchers.eq(workshopId),
                org.mockito.ArgumentMatchers.eq("%acme%"),
                any(org.springframework.data.domain.Pageable.class));
    }

    /** Una lista desplegable siempre viene acotada y ordenada de forma determinista. */
    @Test
    void suggestionsAreBoundedAndOrderedFromTheMostRecentlyIssued() {
        ArgumentCaptor<String> pattern = ArgumentCaptor.forClass(String.class);

        org.springframework.data.domain.Pageable pageable =
                captureSuggestionQuery("acme", null, pattern);

        assertThat(pageable.getPageNumber()).isZero();
        assertThat(pageable.getPageSize()).isEqualTo(8);
        assertThat(pageable.getSort()).isEqualTo(
                org.springframework.data.domain.Sort.by(
                        org.springframework.data.domain.Sort.Order.desc("completedAt"),
                        org.springframework.data.domain.Sort.Order.desc("id")));
    }

    @Test
    void anAbsurdSuggestionLimitIsRejectedBeforeQuerying() {
        assertThatThrownBy(() -> service.searchCompletedSales("acme", 11))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("entre 1 y 10");
        assertThatThrownBy(() -> service.searchCompletedSales("acme", 0))
                .isInstanceOf(ValidationException.class);

        verifyNoInteractions(repository);
    }

    // ------------------------------------------------------ cancelacion

    @Test
    void cancellingAnOpenSaleKeepsItAsHistory() {
        Product normalProduct = product(FamilyCategory.NORMAL);
        SaleDetail detail = SaleDetail.builder()
                .product(normalProduct)
                .quantity(2)
                .build();
        Sale sale = saleWithDetail(detail);
        when(repository.findByIdAndWorkshopId(sale.getId(), workshopId))
                .thenReturn(Optional.of(sale));
        when(repository.save(sale)).thenReturn(sale);
        when(registry.get(FamilyCategory.NORMAL)).thenReturn(handler);
        when(currentUserService.getCurrentEmployee()).thenReturn(currentEmployee);

        service.cancel(sale.getId(), new CancelSaleRequestDTO("Cliente desistio"));

        assertThat(sale.getStatus()).isEqualTo(SaleStatus.CANCELLED);
        // Devuelve lo reservado por el mismo camino que quitar una linea...
        verify(handler).restore(detail);
        // ...pero la linea sigue en la venta y la venta no se borra.
        assertThat(sale.getDetails()).containsExactly(detail);
        verify(repository, never()).delete(any(Sale.class));
        // El motivo, el autor y el tipo de accion quedan persistidos.
        assertThat(sale.getAction()).isNotNull();
        assertThat(sale.getAction().getActionType())
                .isEqualTo(SaleActionType.CANCEL_PENDING);
        assertThat(sale.getAction().getReason()).isEqualTo("Cliente desistio");
        assertThat(sale.getAction().getPerformedBy()).isEqualTo(currentEmployee);
    }

    /**
     * La reserva de un trabajo ES el detalle que lo apunta, asi que cancelar
     * tiene que soltar el puntero o el trabajo queda inutilizable para siempre.
     */
    @Test
    void cancellingReleasesTheWorkResultWithoutSellingIt() {
        Product jobProduct = product(FamilyCategory.JOB);
        WorkResult workResult = pendingFromJobLog(jobProduct);
        SaleDetail detail = SaleDetail.builder()
                .product(jobProduct)
                .workResult(workResult)
                .build();
        Sale sale = saleWithDetail(detail);
        when(repository.findByIdAndWorkshopId(sale.getId(), workshopId))
                .thenReturn(Optional.of(sale));
        when(repository.save(sale)).thenReturn(sale);
        when(registry.get(FamilyCategory.JOB)).thenReturn(handler);
        when(currentUserService.getCurrentEmployee()).thenReturn(currentEmployee);

        service.cancel(sale.getId(), new CancelSaleRequestDTO("Motivo de prueba"));

        assertThat(detail.getWorkResult()).isNull();
        assertThat(workResult.getStatus()).isEqualTo(WorkResultStatus.PENDING);
        // Cancelar no es vender: el trabajo no puede acabar SOLD.
        verifyNoInteractions(workResultService);
    }

    @Test
    void anIssuedSaleCannotBeCancelled() {
        Sale sale = Sale.builder()
                .taxMode(TaxMode.CF)
                .id(UUID.randomUUID())
                .workshop(workshop)
                .status(SaleStatus.COMPLETED)
                .invoiceType(InvoiceType.EXCHANGE_INVOICE)
                .build();
        when(repository.findByIdAndWorkshopId(sale.getId(), workshopId))
                .thenReturn(Optional.of(sale));

        assertThatThrownBy(() -> service.cancel(
                sale.getId(), new CancelSaleRequestDTO("Motivo")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Solo se puede cancelar una venta abierta");

        assertThat(sale.getStatus()).isEqualTo(SaleStatus.COMPLETED);
        verify(repository, never()).save(any());
        verifyNoInteractions(registry, workResultService);
    }

    @Test
    void aCancelledSaleIsNotCancelledAgain() {
        Sale sale = Sale.builder()
                .taxMode(TaxMode.CF)
                .id(UUID.randomUUID())
                .workshop(workshop)
                .status(SaleStatus.CANCELLED)
                .build();
        when(repository.findByIdAndWorkshopId(sale.getId(), workshopId))
                .thenReturn(Optional.of(sale));

        assertThatThrownBy(() -> service.cancel(
                sale.getId(), new CancelSaleRequestDTO("Motivo")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("La venta ya se encuentra cancelada");

        verify(repository, never()).save(any());
        verifyNoInteractions(registry);
    }

    @Test
    void cancellingASaleOfAnotherWorkshopIsNotReachable() {
        UUID foreignSaleId = UUID.randomUUID();
        when(repository.findByIdAndWorkshopId(foreignSaleId, workshopId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancel(
                foreignSaleId, new CancelSaleRequestDTO("Motivo")))
                .isInstanceOf(com.cns.spring_house.shared.exception.EntityNotFoundException.class)
                .hasMessage("No existe la venta especificada");

        verifyNoInteractions(registry, workResultService);
    }

    /**
     * El trabajo se vendio al emitir el documento aunque el cobro siga pendiente:
     * la deuda vive en paymentStatus, no en WorkResultStatus.
     */
    @Test
    void anExchangeInvoiceAlsoMarksItsWorkResultsAsSold() {
        Product jobProduct = product(FamilyCategory.JOB);
        WorkResult workResult = pendingFromJobLog(jobProduct);
        SaleDetail detail = SaleDetail.builder()
                .product(jobProduct)
                .workResult(workResult)
                .build();
        Sale sale = saleWithDetail(detail);
        when(repository.findByIdAndWorkshopId(sale.getId(), workshopId))
                .thenReturn(Optional.of(sale));
        when(repository.save(sale)).thenReturn(sale);

        service.completeSale(sale.getId(), new CompleteSaleRequestDTO(
                InvoiceType.EXCHANGE_INVOICE, null));

        verify(workResultService).markAsSold(
                workResult.getId(), jobProduct.getId(), null);
        assertThat(sale.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    // ------------------------------------------------------- reversion

    private Sale completedSaleWithDetail(SaleDetail detail) {
        Sale sale = Sale.builder()
                .taxMode(TaxMode.CF)
                .id(UUID.randomUUID())
                .workshop(workshop)
                .status(SaleStatus.COMPLETED)
                .invoiceType(InvoiceType.INVOICE)
                .paymentStatus(PaymentStatus.PAID)
                .build();
        detail.setSubTotal(BigDecimal.TEN);
        sale.addDetail(detail);
        when(repository.findByIdAndWorkshopId(sale.getId(), workshopId))
                .thenReturn(Optional.of(sale));
        when(repository.save(sale)).thenReturn(sale);
        return sale;
    }

    @Test
    void reversingACompletedSaleRestoresStockAndKeepsHistory() {
        Product normalProduct = product(FamilyCategory.NORMAL);
        SaleDetail detail = SaleDetail.builder()
                .product(normalProduct)
                .quantity(3)
                .build();
        Sale sale = completedSaleWithDetail(detail);
        when(registry.get(FamilyCategory.NORMAL)).thenReturn(handler);
        when(currentUserService.getCurrentEmployee()).thenReturn(currentEmployee);

        service.reverse(sale.getId(), new ReverseSaleRequestDTO("Se cargo mal el producto"));

        assertThat(sale.getStatus()).isEqualTo(SaleStatus.REVERSED);
        verify(handler).restore(detail);
        assertThat(sale.getDetails()).containsExactly(detail);
        assertThat(sale.getAction()).isNotNull();
        assertThat(sale.getAction().getActionType())
                .isEqualTo(SaleActionType.REVERSE_COMPLETED);
        assertThat(sale.getAction().getReason())
                .isEqualTo("Se cargo mal el producto");
        assertThat(sale.getAction().getPerformedBy()).isEqualTo(currentEmployee);
        // No es un reembolso fiscal: no toca la dimension de cobro.
        assertThat(sale.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
    }

    @Test
    void reversingASaleWithASoldWorkResultReturnsItToPending() {
        Product jobProduct = product(FamilyCategory.JOB);
        WorkResult workResult = pendingFromJobLog(jobProduct);
        workResult.setStatus(WorkResultStatus.SOLD);
        SaleDetail detail = SaleDetail.builder()
                .product(jobProduct)
                .workResult(workResult)
                .build();
        Sale sale = completedSaleWithDetail(detail);
        when(registry.get(FamilyCategory.JOB)).thenReturn(handler);
        when(currentUserService.getCurrentEmployee()).thenReturn(currentEmployee);

        service.reverse(sale.getId(), new ReverseSaleRequestDTO("Trabajo mal facturado"));

        verify(workResultService).restoreToPending(workResult.getId());
        assertThat(detail.getWorkResult()).isNull();
    }

    @Test
    void aPendingSaleCannotBeReversed() {
        Sale sale = Sale.builder()
                .customer(registeredCustomer)
                .id(UUID.randomUUID())
                .workshop(workshop)
                .status(SaleStatus.PENDING)
                .build();
        when(repository.findByIdAndWorkshopId(sale.getId(), workshopId))
                .thenReturn(Optional.of(sale));

        assertThatThrownBy(() -> service.reverse(
                sale.getId(), new ReverseSaleRequestDTO("Motivo")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Una venta abierta se anula, no se revierte");

        verify(repository, never()).save(any());
        verifyNoInteractions(registry, workResultService);
    }

    @Test
    void aCancelledSaleCannotBeReversed() {
        Sale sale = Sale.builder()
                .taxMode(TaxMode.CF)
                .id(UUID.randomUUID())
                .workshop(workshop)
                .status(SaleStatus.CANCELLED)
                .build();
        when(repository.findByIdAndWorkshopId(sale.getId(), workshopId))
                .thenReturn(Optional.of(sale));

        assertThatThrownBy(() -> service.reverse(
                sale.getId(), new ReverseSaleRequestDTO("Motivo")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Una venta anulada no se puede revertir");

        verify(repository, never()).save(any());
    }

    @Test
    void aSaleCannotBeReversedTwice() {
        Sale sale = Sale.builder()
                .taxMode(TaxMode.CF)
                .id(UUID.randomUUID())
                .workshop(workshop)
                .status(SaleStatus.REVERSED)
                .build();
        when(repository.findByIdAndWorkshopId(sale.getId(), workshopId))
                .thenReturn(Optional.of(sale));

        assertThatThrownBy(() -> service.reverse(
                sale.getId(), new ReverseSaleRequestDTO("Motivo")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("La venta ya fue revertida");

        verify(repository, never()).save(any());
    }

    // ------------------------------------------- doble reserva WorkResult

    @Test
    void aWorkResultAlreadyTakenByAnotherSaleIsRejected() {
        Product jobProduct = product(FamilyCategory.JOB);
        UUID workResultId = UUID.randomUUID();
        Sale sale = Sale.builder()
                .customer(registeredCustomer)
                .id(UUID.randomUUID())
                .workshop(workshop)
                .build();
        SaleDetail detail = SaleDetail.builder()
                .product(jobProduct)
                .subTotal(BigDecimal.TEN)
                .build();
        AddSaleDetailRequestDTO requestDTO = new AddSaleDetailRequestDTO(
                jobProduct.getId(), null, workResultId, 1, null, null);
        when(repository.findByIdAndWorkshopId(sale.getId(), workshopId))
                .thenReturn(Optional.of(sale));
        when(productRepository.findById(jobProduct.getId()))
                .thenReturn(Optional.of(jobProduct));
        when(registry.get(FamilyCategory.JOB)).thenReturn(handler);
        doAnswer(invocation -> {
            sale.addDetail(detail);
            return detail;
        }).when(handler).addDetail(sale, requestDTO);
        when(detailRepository.existsByWorkResultId(workResultId))
                .thenReturn(true);

        assertThatThrownBy(() -> service.addDetail(sale.getId(), requestDTO))
                .isInstanceOf(ValidationException.class)
                .hasMessage("El trabajo pendiente ya fue agregado a otra venta");

        // Se rechaza antes de resolverlo: no llega a reservarse dos veces.
        verify(workResultService, never()).resolvePending(any(), any(), any());
    }

    // --------------------------------------------- aislamiento por taller

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void getAllIgnoresTheWorkshopSentByTheClient() {
        UUID foreignWorkshopId = UUID.randomUUID();
        when(repository.findAll(any(org.springframework.data.jpa.domain.Specification.class)))
                .thenReturn(java.util.List.of());

        // El orden sigue al record: customer, customerId, plate, min/maxTotal,
        // min/maxDate, workshop, employee, status, invoiceType, paymentStatus.
        service.getAll(new SearchSaleRequestDTO(
                null, null, null, null, null, null, null,
                foreignWorkshopId, null, null, null, null));

        ArgumentCaptor<org.springframework.data.jpa.domain.Specification> captor =
                ArgumentCaptor.forClass(
                        org.springframework.data.jpa.domain.Specification.class);
        verify(repository).findAll(captor.capture());

        jakarta.persistence.criteria.Root<Sale> root =
                org.mockito.Mockito.mock(jakarta.persistence.criteria.Root.class);
        jakarta.persistence.criteria.Path workshopPath =
                org.mockito.Mockito.mock(jakarta.persistence.criteria.Path.class);
        jakarta.persistence.criteria.Path idPath =
                org.mockito.Mockito.mock(jakarta.persistence.criteria.Path.class);
        jakarta.persistence.criteria.CriteriaBuilder builder =
                org.mockito.Mockito.mock(
                        jakarta.persistence.criteria.CriteriaBuilder.class);
        org.mockito.Mockito.doReturn(workshopPath).when(root).get("workshop");
        org.mockito.Mockito.doReturn(idPath).when(workshopPath).get("id");

        captor.getValue().toPredicate(
                root,
                org.mockito.Mockito.mock(
                        jakarta.persistence.criteria.CriteriaQuery.class),
                builder);

        verify(builder).equal(idPath, workshopId);
        verify(builder, never()).equal(idPath, foreignWorkshopId);
    }

    @Test
    void aSaleOfAnotherWorkshopIsNotReachable() {
        UUID foreignSaleId = UUID.randomUUID();
        when(repository.findByIdAndWorkshopId(foreignSaleId, workshopId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(foreignSaleId))
                .isInstanceOf(com.cns.spring_house.shared.exception.EntityNotFoundException.class)
                .hasMessage("No existe la venta especificada");

        verify(repository, never()).findById(any());
    }

    @Test
    void addDetailOnAForeignSaleIsRejectedBeforeTouchingInventory() {
        UUID foreignSaleId = UUID.randomUUID();
        when(repository.findByIdAndWorkshopId(foreignSaleId, workshopId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addDetail(
                foreignSaleId,
                new AddSaleDetailRequestDTO(
                        UUID.randomUUID(), null, null, 1, null, null)))
                .isInstanceOf(com.cns.spring_house.shared.exception.EntityNotFoundException.class);

        verifyNoInteractions(registry, productRepository, workResultService);
    }

    @Test
    void completeAndRegisterPaymentOnAForeignSaleAreRejected() {
        UUID foreignSaleId = UUID.randomUUID();
        when(repository.findByIdAndWorkshopId(foreignSaleId, workshopId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.completeSale(foreignSaleId, invoiceCash()))
                .isInstanceOf(com.cns.spring_house.shared.exception.EntityNotFoundException.class);
        assertThatThrownBy(() -> service.registerPayment(
                foreignSaleId, new RegisterPaymentRequestDTO(PaymentMethod.CASH)))
                .isInstanceOf(com.cns.spring_house.shared.exception.EntityNotFoundException.class);

        verify(repository, never()).save(any());
        verifyNoInteractions(workResultService);
    }

    @Test
    void removeDetailOfAForeignSaleIsNotReachable() {
        UUID foreignDetailId = UUID.randomUUID();
        when(detailRepository.findByIdAndSaleWorkshopId(foreignDetailId, workshopId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.removeDetail(foreignDetailId))
                .isInstanceOf(com.cns.spring_house.shared.exception.EntityNotFoundException.class)
                .hasMessage("No existe el detalle especificado");

        verifyNoInteractions(registry);
    }

    @Test
    void deleteOfAForeignSaleIsNotReachable() {
        UUID foreignSaleId = UUID.randomUUID();
        when(repository.findByIdAndWorkshopId(foreignSaleId, workshopId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(foreignSaleId))
                .isInstanceOf(com.cns.spring_house.shared.exception.EntityNotFoundException.class);

        verify(repository, never()).delete(any(Sale.class));
    }

    // ------------------------------------------------ eliminar venta abierta

    @Test
    void deletingAnOpenSaleReturnsEverythingItHadReserved() {
        Product normalProduct = product(FamilyCategory.NORMAL);
        SaleDetail detail = SaleDetail.builder()
                .product(normalProduct)
                .quantity(2)
                .build();
        Sale sale = saleWithDetail(detail);
        when(repository.findByIdAndWorkshopId(sale.getId(), workshopId))
                .thenReturn(Optional.of(sale));
        when(registry.get(FamilyCategory.NORMAL)).thenReturn(handler);

        service.delete(sale.getId());

        // El borrado directo dejaba el stock descontado para siempre.
        verify(handler).removeDetail(sale, detail);
        verify(repository).delete(sale);
    }

    @Test
    void aCompletedSaleCannotBeDeleted() {
        Sale sale = Sale.builder()
                .taxMode(TaxMode.CF)
                .id(UUID.randomUUID())
                .workshop(workshop)
                .status(SaleStatus.COMPLETED)
                .build();
        when(repository.findByIdAndWorkshopId(sale.getId(), workshopId))
                .thenReturn(Optional.of(sale));

        assertThatThrownBy(() -> service.delete(sale.getId()))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Solo se puede eliminar una venta abierta");

        verify(repository, never()).delete(any(Sale.class));
    }

    // -------------------------------------------------- descripcion fiscal

    @Test
    void theInvoiceUsesTheProductNameWhenThereIsNoAlternativeDescription() {
        Product realProduct = product(FamilyCategory.NORMAL);
        SaleDetail detail = SaleDetail.builder()
                .product(realProduct)
                .build();

        assertThat(detail.resolveInvoiceDescription()).isEqualTo("PRODUCTO");
    }

    @Test
    void theInvoiceUsesTheAlternativeDescriptionWithoutChangingTheProduct() {
        Product realProduct = product(FamilyCategory.NORMAL);
        SaleDetail detail = SaleDetail.builder()
                .product(realProduct)
                .invoiceDescription("Maleta de resorte")
                .build();

        assertThat(detail.resolveInvoiceDescription())
                .isEqualTo("Maleta de resorte");
        // El catalogo sigue viendo el nombre tecnico real.
        assertThat(realProduct.getName()).isEqualTo("PRODUCTO");
        assertThat(detail.getProduct().getName()).isEqualTo("PRODUCTO");
    }

    @Test
    void aBlankAlternativeDescriptionFallsBackToTheProductName() {
        Product realProduct = product(FamilyCategory.NORMAL);
        SaleDetail detail = SaleDetail.builder()
                .product(realProduct)
                .invoiceDescription("   ")
                .build();

        assertThat(detail.resolveInvoiceDescription()).isEqualTo("PRODUCTO");
    }

    @Test
    void aBlankAlternativeDescriptionIsNormalizedToNullInTheRequest() {
        AddSaleDetailRequestDTO requestDTO = new AddSaleDetailRequestDTO(
                UUID.randomUUID(), null, null, 1, null, "   ");

        assertThat(requestDTO.invoiceDescription()).isNull();
    }

}
