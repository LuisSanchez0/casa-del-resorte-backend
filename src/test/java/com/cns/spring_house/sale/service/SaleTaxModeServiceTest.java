package com.cns.spring_house.sale.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.cns.spring_house.cashclosure.service.CashClosureGuard;
import com.cns.spring_house.customer.entity.Customer;
import com.cns.spring_house.customer.repository.CustomerRepository;
import com.cns.spring_house.employee.entity.Employee;
import com.cns.spring_house.family.entity.Family;
import com.cns.spring_house.family.entity.FamilyCategory;
import com.cns.spring_house.product.entity.Product;
import com.cns.spring_house.product.repository.ProductRepository;
import com.cns.spring_house.sale.dto.CancelSaleRequestDTO;
import com.cns.spring_house.sale.dto.CompleteSaleRequestDTO;
import com.cns.spring_house.sale.dto.CreateSaleRequestDTO;
import com.cns.spring_house.sale.dto.SaveForLaterRequestDTO;
import com.cns.spring_house.sale.entity.InvoiceType;
import com.cns.spring_house.sale.entity.PaymentMethod;
import com.cns.spring_house.sale.entity.PaymentStatus;
import com.cns.spring_house.sale.entity.Sale;
import com.cns.spring_house.sale.entity.SaleDetail;
import com.cns.spring_house.sale.entity.SaleStatus;
import com.cns.spring_house.sale.entity.TaxMode;
import com.cns.spring_house.sale.handler.SaleDetailHandler;
import com.cns.spring_house.sale.handler.SaleDetailHandlerRegistry;
import com.cns.spring_house.sale.mapper.SaleMapper;
import com.cns.spring_house.sale.repository.SaleDetailRepository;
import com.cns.spring_house.sale.repository.SaleRepository;
import com.cns.spring_house.shared.config.time.TimeConfig;
import com.cns.spring_house.shared.exception.ValidationException;
import com.cns.spring_house.shared.security.CurrentUserService;
import com.cns.spring_house.workresult.service.WorkResultService;
import com.cns.spring_house.workshop.entity.Workshop;

/**
 * Las tres identidades fiscales de una venta —con NIT, Consumidor Final y sin
 * definir— en el alta, en "guardar para despues", en la finalizacion y en el
 * historial.
 *
 * Son reglas del servicio, no de la pantalla: aqui se comprueba que ningun
 * cliente —tampoco uno que se salte la caja— puede abrir una venta a
 * Consumidor Final, guardar sin nombre una venta sin NIT ni completar una sin
 * definir, y que una venta cerrada conserva el cliente con el que se cerro. Lo
 * que la base sostiene por su cuenta (y el rollback real) se comprueba en
 * {@code SaleTaxModeDatabaseTest}.
 */
@ExtendWith(MockitoExtension.class)
class SaleTaxModeServiceTest {

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

    @Mock
    private CashClosureGuard cashClosureGuard;

    @Mock
    private Clock clock;

    @Mock
    private SaleDetailHandler handler;

    @InjectMocks
    private SaleServiceImpl service;

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 24, 11, 0);

    private final UUID workshopId = UUID.randomUUID();
    private final Workshop workshop = Workshop.builder().id(workshopId).build();
    private final Employee employee = Employee.builder()
            .id(UUID.randomUUID())
            .workshop(workshop)
            .build();
    private final Customer registered = Customer.builder()
            .id(UUID.randomUUID())
            .nit("12345678")
            .name("JUAN PEREZ")
            .build();

    @BeforeEach
    void setUp() {
        lenient().when(clock.instant()).thenReturn(
                NOW.atZone(TimeConfig.BUSINESS_ZONE).toInstant());
        lenient().when(clock.getZone()).thenReturn(TimeConfig.BUSINESS_ZONE);

        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject(UUID.randomUUID().toString())
                .claim("workshop", workshopId.toString())
                .build();
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(jwt));

        lenient().when(currentUserService.getCurrentEmployee()).thenReturn(employee);
        lenient().when(currentUserService.getCurrentWorkshop()).thenReturn(workshop);
        lenient().when(customerRepository.findById(registered.getId()))
                .thenReturn(Optional.of(registered));
        lenient().when(repository.save(any(Sale.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    // ── Datos de partida ─────────────────────────────────────────

    /** Lo que el servicio intento persistir en su ultimo `save`. */
    private Sale savedSale() {
        ArgumentCaptor<Sale> captor = ArgumentCaptor.forClass(Sale.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    /** Venta abierta del taller autenticado, con una linea facturable. */
    private Sale openSale(TaxMode taxMode, Customer customer, String customerName) {
        Sale sale = Sale.builder()
                .id(UUID.randomUUID())
                .workshop(workshop)
                .employee(employee)
                .customer(customer)
                .taxMode(taxMode)
                .customerName(customerName)
                .build();
        Product product = Product.builder()
                .id(UUID.randomUUID())
                .name("PRODUCTO")
                .family(Family.builder().category(FamilyCategory.NORMAL).build())
                .build();
        sale.addDetail(SaleDetail.builder()
                .id(UUID.randomUUID())
                .product(product)
                .quantity(1)
                .price(new BigDecimal("100.00"))
                .subTotal(new BigDecimal("100.00"))
                .build());
        when(repository.findByIdAndWorkshopId(sale.getId(), workshopId))
                .thenReturn(Optional.of(sale));
        return sale;
    }

    private CompleteSaleRequestDTO invoiceCash(
            TaxMode taxMode, UUID customer, String customerName) {
        return new CompleteSaleRequestDTO(
                InvoiceType.INVOICE, PaymentMethod.CASH,
                taxMode, customer, customerName);
    }

    private SaveForLaterRequestDTO withName(String name) {
        return new SaveForLaterRequestDTO(name);
    }

    private static final String UNRESOLVED_COMPLETION =
            "Debe seleccionar NIT o Consumidor Final antes de finalizar la venta";

    // ── Venta normal con NIT ─────────────────────────────────────

    @Nested
    class NormalSaleWithNit {

        @Test
        void opensWithTheRegisteredCustomerAndNothingFrozenYet() {
            service.create(new CreateSaleRequestDTO(
                    registered.getId(), null, TaxMode.NIT, null));

            Sale sale = savedSale();
            assertThat(sale.getTaxMode()).isEqualTo(TaxMode.NIT);
            assertThat(sale.getCustomer()).isEqualTo(registered);
            assertThat(sale.getCustomerName()).isNull();
            assertThat(sale.getCustomerNit()).isNull();
            assertThat(sale.isSavedForLater()).isFalse();
            assertThat(sale.getStatus()).isEqualTo(SaleStatus.PENDING);
        }

        /** El contrato anterior (`customer` + `plate`) sigue siendo una venta con NIT. */
        @Test
        void theLegacyRequestStillOpensANitSale() {
            service.create(new CreateSaleRequestDTO(registered.getId(), "p123abc"));

            Sale sale = savedSale();
            assertThat(sale.getTaxMode()).isEqualTo(TaxMode.NIT);
            assertThat(sale.getCustomer()).isEqualTo(registered);
            assertThat(sale.getPlate()).isEqualTo("P123ABC");
        }

        @Test
        void requiresTheCustomer() {
            assertThatThrownBy(() -> service.create(new CreateSaleRequestDTO(
                    null, null, TaxMode.NIT, null)))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("El cliente es obligatorio");
            verify(repository, never()).save(any());
        }

        @Test
        void doesNotAcceptASecondNameNextToTheRegisteredOne() {
            assertThatThrownBy(() -> service.create(new CreateSaleRequestDTO(
                    registered.getId(), null, TaxMode.NIT, "Otro Nombre")))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Una venta con NIT usa el nombre del cliente registrado");
            verify(repository, never()).save(any());
        }

        @Test
        void isSavedForLaterAsItIs() {
            Sale sale = openSale(TaxMode.NIT, registered, null);

            service.saveForLater(sale.getId(), withName(null));

            assertThat(sale.isSavedForLater()).isTrue();
            assertThat(sale.getTaxMode()).isEqualTo(TaxMode.NIT);
            assertThat(sale.getCustomer()).isEqualTo(registered);
            assertThat(sale.getCustomerName()).isNull();
            assertThat(sale.getStatus()).isEqualTo(SaleStatus.PENDING);
        }

        @Test
        void savingForLaterDoesNotAcceptAnotherName() {
            Sale sale = openSale(TaxMode.NIT, registered, null);

            assertThatThrownBy(() -> service.saveForLater(sale.getId(), withName("Otro")))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Una venta con NIT usa el nombre del cliente registrado");
            assertThat(sale.isSavedForLater()).isFalse();
        }

        /** Un cliente viejo que solo manda el documento completa como siempre... */
        @Test
        void completesAsBeforeAndFreezesTheCustomerItWasIssuedTo() {
            Sale sale = openSale(TaxMode.NIT, registered, null);

            service.completeSale(sale.getId(),
                    new CompleteSaleRequestDTO(InvoiceType.INVOICE, PaymentMethod.CASH));

            assertThat(sale.getStatus()).isEqualTo(SaleStatus.COMPLETED);
            assertThat(sale.getTaxMode()).isEqualTo(TaxMode.NIT);
            assertThat(sale.getCustomer()).isEqualTo(registered);
            assertThat(sale.getCustomerName()).isEqualTo("JUAN PEREZ");
            assertThat(sale.getCustomerNit()).isEqualTo("12345678");
            assertThat(sale.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
            assertThat(sale.getPaymentMethod()).isEqualTo(PaymentMethod.CASH);
            assertThat(sale.getCompletedAt()).isEqualTo(NOW);
        }

        @Test
        void cancellingFreezesTheCustomerToo() {
            Sale sale = openSale(TaxMode.NIT, registered, null);
            when(registry.get(any())).thenReturn(handler);

            service.cancel(sale.getId(), new CancelSaleRequestDTO("Cliente desistio"));

            assertThat(sale.getStatus()).isEqualTo(SaleStatus.CANCELLED);
            assertThat(sale.getCustomerName()).isEqualTo("JUAN PEREZ");
            assertThat(sale.getCustomerNit()).isEqualTo("12345678");
        }
    }

    // ── Carrito sin NIT: borrador sin definir ────────────────────

    @Nested
    class NoNitDraft {

        /** CF es una identidad final: no se acepta en un carrito que recien se abre. */
        @Test
        void consumidorFinalIsNotAcceptedWhenOpening() {
            assertThatThrownBy(() -> service.create(new CreateSaleRequestDTO(
                    null, null, TaxMode.CF, "María López")))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Consumidor Final se elige al emitir la venta: "
                            + "abrala sin NIT y elija CF al finalizar");
            verify(repository, never()).save(any());
        }

        @Test
        void opensUnresolvedAndUnsavedEvenWithoutAName() {
            service.create(new CreateSaleRequestDTO(null, null, TaxMode.UNRESOLVED, "  "));

            Sale sale = savedSale();
            assertThat(sale.getTaxMode()).isEqualTo(TaxMode.UNRESOLVED);
            assertThat(sale.getCustomer()).isNull();
            assertThat(sale.getCustomerName()).isNull();
            assertThat(sale.getCustomerNit()).isNull();
            assertThat(sale.isSavedForLater()).isFalse();
        }

        @Test
        void keepsAnOptionalNameNormalizedLikeTheCustomerMaster() {
            service.create(new CreateSaleRequestDTO(
                    null, null, TaxMode.UNRESOLVED, "  María López "));

            Sale sale = savedSale();
            assertThat(sale.getTaxMode()).isEqualTo(TaxMode.UNRESOLVED);
            assertThat(sale.getCustomerName()).isEqualTo("MARÍA LÓPEZ");
        }

        @Test
        void rejectsARegisteredCustomer() {
            assertThatThrownBy(() -> service.create(new CreateSaleRequestDTO(
                    registered.getId(), null, TaxMode.UNRESOLVED, "Carlos")))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Solo una venta con NIT lleva un cliente registrado");
            verify(repository, never()).save(any());
        }

        /** Sin NIT no se reutiliza ni se crea ningun cliente: un "CF" nunca llega a customer.nit. */
        @Test
        void neverTouchesTheCustomerMaster() {
            service.create(new CreateSaleRequestDTO(null, null, TaxMode.UNRESOLVED, "María"));

            verify(customerRepository, never()).save(any());
            verify(customerRepository, never()).findById(any());
        }
    }

    // ── Guardar para despues ─────────────────────────────────────

    @Nested
    class SaveForLater {

        @Test
        void aNoNitSaleWithoutAReferenceNameIsNotSaved() {
            Sale sale = openSale(TaxMode.UNRESOLVED, null, null);

            assertThatThrownBy(() -> service.saveForLater(sale.getId(), withName("  ")))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("El nombre es obligatorio para guardar para despues una venta sin NIT");

            assertThat(sale.isSavedForLater()).isFalse();
            verify(repository, never()).save(any());
        }

        @Test
        void theReferenceNameCanArriveWhenSaving() {
            Sale sale = openSale(TaxMode.UNRESOLVED, null, null);

            service.saveForLater(sale.getId(), withName(" Carlos Gómez "));

            assertThat(sale.isSavedForLater()).isTrue();
            assertThat(sale.getTaxMode()).isEqualTo(TaxMode.UNRESOLVED);
            assertThat(sale.getCustomerName()).isEqualTo("CARLOS GÓMEZ");
        }

        @Test
        void aNameGivenWhenOpeningIsEnough() {
            Sale sale = openSale(TaxMode.UNRESOLVED, null, "CARLOS GÓMEZ");

            service.saveForLater(sale.getId(), withName(null));

            assertThat(sale.isSavedForLater()).isTrue();
            assertThat(sale.getCustomerName()).isEqualTo("CARLOS GÓMEZ");
        }

        /**
         * H. Un CF abierto no puede existir en la base; si aun asi llegara uno
         * en memoria, no se guarda como pendiente.
         */
        @Test
        void anExplicitConsumidorFinalSaleIsNeverSavedForLater() {
            Sale sale = openSale(TaxMode.CF, null, "MARÍA LÓPEZ");

            assertThatThrownBy(() -> service.saveForLater(sale.getId(), withName(null)))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("no se puede guardar para despues");
            assertThat(sale.isSavedForLater()).isFalse();
        }

        @Test
        void aClosedSaleCannotBeSavedForLater() {
            Sale sale = openSale(TaxMode.NIT, registered, null);
            sale.setStatus(SaleStatus.COMPLETED);

            assertThatThrownBy(() -> service.saveForLater(sale.getId(), withName(null)))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("La venta no se encuentra abierta");
        }
    }

    // ── Finalizar una venta sin definir ──────────────────────────

    @Nested
    class CompletingAnUnresolvedSale {

        /** Un cliente viejo que solo envia el documento no puede completarla. */
        @Test
        void cannotCompleteWithoutChoosing() {
            Sale sale = openSale(TaxMode.UNRESOLVED, null, "CARLOS GÓMEZ");

            assertThatThrownBy(() -> service.completeSale(sale.getId(),
                    new CompleteSaleRequestDTO(InvoiceType.INVOICE, PaymentMethod.CASH)))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage(UNRESOLVED_COMPLETION);

            assertThat(sale.getStatus()).isEqualTo(SaleStatus.PENDING);
            assertThat(sale.getTaxMode()).isEqualTo(TaxMode.UNRESOLVED);
            verify(repository, never()).save(any());
        }

        @Test
        void cannotCompleteAskingToStayUnresolved() {
            Sale sale = openSale(TaxMode.UNRESOLVED, null, "CARLOS GÓMEZ");

            assertThatThrownBy(() -> service.completeSale(sale.getId(),
                    invoiceCash(TaxMode.UNRESOLVED, null, null)))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage(UNRESOLVED_COMPLETION);
            assertThat(sale.getStatus()).isEqualTo(SaleStatus.PENDING);
        }

        /** Un nombre sin modo no convierte la venta en CF. */
        @Test
        void aNameWithoutAModeNeverBecomesConsumidorFinal() {
            Sale sale = openSale(TaxMode.UNRESOLVED, null, null);

            assertThatThrownBy(() -> service.completeSale(sale.getId(),
                    invoiceCash(null, null, "Carlos Gómez")))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Indique si la venta se factura con NIT o a Consumidor Final");

            assertThat(sale.getTaxMode()).isEqualTo(TaxMode.UNRESOLVED);
            assertThat(sale.getStatus()).isEqualTo(SaleStatus.PENDING);
        }

        @Test
        void choosingNitRequiresTheCustomer() {
            Sale sale = openSale(TaxMode.UNRESOLVED, null, "CARLOS GÓMEZ");

            assertThatThrownBy(() -> service.completeSale(sale.getId(),
                    invoiceCash(TaxMode.NIT, null, null)))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("El cliente es obligatorio");
            assertThat(sale.getStatus()).isEqualTo(SaleStatus.PENDING);
        }

        /** Pendiente -> NIT: se congela el cliente elegido, no el nombre de referencia. */
        @Test
        void completingWithNitFreezesTheChosenCustomer() {
            Sale sale = openSale(TaxMode.UNRESOLVED, null, "CARLOS");

            service.completeSale(sale.getId(),
                    invoiceCash(TaxMode.NIT, registered.getId(), null));

            assertThat(sale.getStatus()).isEqualTo(SaleStatus.COMPLETED);
            assertThat(sale.getTaxMode()).isEqualTo(TaxMode.NIT);
            assertThat(sale.getCustomer()).isEqualTo(registered);
            assertThat(sale.getCustomerName()).isEqualTo("JUAN PEREZ");
            assertThat(sale.getCustomerNit()).isEqualTo("12345678");
            verify(customerRepository, never()).save(any());
        }

        /** Pendiente -> CF: el paso a CF y el cierre ocurren en la misma llamada. */
        @Test
        void completingAsConsumidorFinalSetsCfAndCompletedTogether() {
            Sale sale = openSale(TaxMode.UNRESOLVED, null, "CARLOS GÓMEZ");

            service.completeSale(sale.getId(),
                    invoiceCash(TaxMode.CF, null, "Carlos Gómez"));

            assertThat(sale.getTaxMode()).isEqualTo(TaxMode.CF);
            assertThat(sale.getStatus()).isEqualTo(SaleStatus.COMPLETED);
            assertThat(sale.getCustomer()).isNull();
            assertThat(sale.getCustomerName()).isEqualTo("CARLOS GÓMEZ");
            // Nunca un "CF" literal como NIT.
            assertThat(sale.getCustomerNit()).isNull();
        }

        @Test
        void completingAsNamelessConsumidorFinal() {
            Sale sale = openSale(TaxMode.UNRESOLVED, null, null);

            service.completeSale(sale.getId(), invoiceCash(TaxMode.CF, null, " "));

            assertThat(sale.getTaxMode()).isEqualTo(TaxMode.CF);
            assertThat(sale.getStatus()).isEqualTo(SaleStatus.COMPLETED);
            assertThat(sale.getCustomerName()).isNull();
            assertThat(sale.getCustomerNit()).isNull();
        }

        /** NIT -> CF: el NIT anterior no puede colarse en el documento. */
        @Test
        void aNitSaleIssuedAsConsumidorFinalDropsTheCustomer() {
            Sale sale = openSale(TaxMode.NIT, registered, null);

            service.completeSale(sale.getId(), invoiceCash(TaxMode.CF, null, null));

            assertThat(sale.getTaxMode()).isEqualTo(TaxMode.CF);
            assertThat(sale.getCustomer()).isNull();
            assertThat(sale.getCustomerName()).isNull();
            assertThat(sale.getCustomerNit()).isNull();
            assertThat(registered.getNit()).isEqualTo("12345678");
            verify(customerRepository, never()).save(any());
        }

        /** No se inventa una regla fiscal: CF + cambiaria se permite como antes. */
        @Test
        void aConsumidorFinalExchangeInvoiceFollowsTheUsualRules() {
            Sale sale = openSale(TaxMode.UNRESOLVED, null, null);

            service.completeSale(sale.getId(), new CompleteSaleRequestDTO(
                    InvoiceType.EXCHANGE_INVOICE, null, TaxMode.CF, null, null));

            assertThat(sale.getTaxMode()).isEqualTo(TaxMode.CF);
            assertThat(sale.getInvoiceType()).isEqualTo(InvoiceType.EXCHANGE_INVOICE);
            assertThat(sale.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
            assertThat(sale.getPaymentMethod()).isNull();
        }
    }

    // ── Historial congelado ──────────────────────────────────────

    @Test
    void editingTheCustomerAfterwardsNeverRewritesAClosedSale() {
        Customer mutable = Customer.builder()
                .id(UUID.randomUUID())
                .nit("87654321")
                .name("CARLOS GOMEZ")
                .build();
        when(customerRepository.findById(mutable.getId())).thenReturn(Optional.of(mutable));
        Sale sale = openSale(TaxMode.UNRESOLVED, null, null);
        service.completeSale(sale.getId(), invoiceCash(TaxMode.NIT, mutable.getId(), null));

        mutable.setName("CARLOS GOMEZ LOPEZ");
        mutable.setNit("11112222");
        // Cualquier escritura posterior repite el congelado: no debe pisar nada.
        sale.freezeFiscalIdentity();

        assertThat(sale.getCustomerName()).isEqualTo("CARLOS GOMEZ");
        assertThat(sale.getCustomerNit()).isEqualTo("87654321");
    }

}
