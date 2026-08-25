package com.cns.spring_house.dailylog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
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

import com.cns.spring_house.dailylog.dto.CreateDailyLogRequestDTO;
import com.cns.spring_house.dailylog.dto.DailyLogProductRequestDTO;
import com.cns.spring_house.dailylog.dto.DailyLogServiceRequestDTO;
import com.cns.spring_house.dailylog.entity.DailyLog;
import com.cns.spring_house.dailylog.entity.DailyLogProduct;
import com.cns.spring_house.dailylog.mapper.DailyLogMapper;
import com.cns.spring_house.dailylog.repository.DailyLogRepository;
import com.cns.spring_house.employee.entity.Employee;
import com.cns.spring_house.job.entity.Job;
import com.cns.spring_house.job.repository.JobRepository;
import com.cns.spring_house.product.entity.Product;
import com.cns.spring_house.product.repository.ProductRepository;
import com.cns.spring_house.sale.entity.InvoiceType;
import com.cns.spring_house.sale.entity.PaymentStatus;
import com.cns.spring_house.sale.entity.Sale;
import com.cns.spring_house.sale.entity.SaleStatus;
import com.cns.spring_house.sale.repository.SaleRepository;
import com.cns.spring_house.shared.exception.EntityAlreadyExistsException;
import com.cns.spring_house.shared.exception.EntityNotFoundException;
import com.cns.spring_house.shared.exception.ValidationException;
import com.cns.spring_house.shared.security.CurrentUserService;
import com.cns.spring_house.workshop.entity.Workshop;

/**
 * El Daily Log valida contra el catalogo y guarda texto. Estas pruebas fijan las
 * dos mitades de esa asimetria: que un nombre inventado no entra, y que el que
 * entra deja de estar relacionado con nada en cuanto se guarda.
 */
@ExtendWith(MockitoExtension.class)
class DailyLogServiceImplTest {

    @Mock
    private DailyLogRepository repository;

    @Mock
    private DailyLogMapper mapper;

    @Mock
    private CurrentUserService currentUserService;

    @Mock
    private SaleRepository saleRepository;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private JobRepository jobRepository;

    @InjectMocks
    private DailyLogServiceImpl service;

    private final UUID workshopId = UUID.randomUUID();
    /** UserAccount.id: lo que JwtServiceImpl firma como subject. */
    private final UUID userAccountId = UUID.randomUUID();
    /** Employee.id: siempre distinto del id de la cuenta. */
    private final UUID employeeId = UUID.randomUUID();
    private final UUID saleId = UUID.randomUUID();

    private final Workshop workshop = Workshop.builder()
            .id(workshopId)
            .build();
    private final Employee employee = Employee.builder()
            .id(employeeId)
            .workshop(workshop)
            .build();

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

    // ── Utilidades de armado ─────────────────────────────────────

    private Sale sale(SaleStatus status) {
        return Sale.builder()
                .id(saleId)
                .workshop(workshop)
                .status(status)
                .build();
    }

    private void givenCompletedSale() {
        givenSale(sale(SaleStatus.COMPLETED));
    }

    private void givenSale(Sale sale) {
        when(saleRepository.findByIdAndWorkshopId(saleId, workshopId))
                .thenReturn(Optional.of(sale));
    }

    private void givenSaveEchoesBack() {
        when(repository.save(any(DailyLog.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private void givenProductNamed(String canonicalName) {
        when(productRepository.findByNameIgnoringCaseAndSpaces(any()))
                .thenReturn(Optional.of(Product.builder()
                        .id(UUID.randomUUID())
                        .name(canonicalName)
                        .build()));
    }

    private void givenServiceNamed(String canonicalName) {
        when(jobRepository.findByNameIgnoringCaseAndSpaces(eq(workshopId), any()))
                .thenReturn(Optional.of(Job.builder()
                        .id(UUID.randomUUID())
                        .product(Product.builder()
                                .id(UUID.randomUUID())
                                .name(canonicalName)
                                .build())
                        .build()));
    }

    private DailyLogProductRequestDTO product(
            String name, String unitPrice, int quantity, String subtotal) {
        return new DailyLogProductRequestDTO(
                name, new BigDecimal(unitPrice), quantity, new BigDecimal(subtotal));
    }

    private DailyLogServiceRequestDTO serviceLine(
            String name, String unitPrice, int quantity, String subtotal) {
        return new DailyLogServiceRequestDTO(
                name, new BigDecimal(unitPrice), quantity, new BigDecimal(subtotal));
    }

    private CreateDailyLogRequestDTO request(
            List<DailyLogProductRequestDTO> products,
            List<DailyLogServiceRequestDTO> services) {
        return new CreateDailyLogRequestDTO(saleId, products, services, null);
    }

    private DailyLog captureSaved() {
        ArgumentCaptor<DailyLog> saved = ArgumentCaptor.forClass(DailyLog.class);
        verify(repository).save(saved.capture());
        return saved.getValue();
    }

    // ── Identidad ────────────────────────────────────────────────

    @Test
    void theAuthenticatedAccountAndEmployeeAreDifferentEntities() {
        assertThat(userAccountId).isNotEqualTo(employeeId);
    }

    @Test
    void createResolvesTheEmployeeThroughTheAuthenticatedUserAccount() {
        when(currentUserService.getCurrentEmployee()).thenReturn(employee);
        givenCompletedSale();
        givenProductNamed("HOJA DE RESORTE FREIGHTLINER M2");
        givenSaveEchoesBack();

        service.create(request(
                List.of(product("HOJA DE RESORTE FREIGHTLINER M2", "450.00", 2, "900.00")),
                List.of()));

        DailyLog saved = captureSaved();
        assertThat(saved.getEmployee().getId()).isEqualTo(employeeId);
        // Nunca debe usarse el id de la cuenta como si fuera el del empleado.
        assertThat(saved.getEmployee().getId()).isNotEqualTo(userAccountId);
    }

    @Test
    void createTakesTheWorkshopFromTheAuthenticatedEmployee() {
        when(currentUserService.getCurrentEmployee()).thenReturn(employee);
        givenCompletedSale();
        givenServiceNamed("ALINEACION");
        givenSaveEchoesBack();

        service.create(request(
                List.of(),
                List.of(serviceLine("ALINEACION", "150.00", 1, "150.00"))));

        assertThat(captureSaved().getWorkshop().getId()).isEqualTo(workshopId);
    }

    @Test
    void createFailsInAControlledWayWhenTheAccountHasNoEmployee() {
        when(currentUserService.getCurrentEmployee())
                .thenThrow(new ValidationException(
                        "El usuario autenticado no tiene un empleado asociado"));

        assertThatThrownBy(() -> service.create(request(
                List.of(product("BUJE", "75.00", 4, "300.00")),
                List.of())))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("no tiene un empleado asociado");

        verify(repository, never()).save(any());
    }

    // ── Validacion del nombre del producto ───────────────────────

    @Test
    void anExistingProductNameIsAccepted() {
        when(currentUserService.getCurrentEmployee()).thenReturn(employee);
        givenCompletedSale();
        givenProductNamed("HOJA DE RESORTE FREIGHTLINER M2");
        givenSaveEchoesBack();

        service.create(request(
                List.of(product("HOJA DE RESORTE FREIGHTLINER M2", "450.00", 2, "900.00")),
                List.of()));

        assertThat(captureSaved().getProducts())
                .singleElement()
                .satisfies(line -> {
                    assertThat(line.getName()).isEqualTo("HOJA DE RESORTE FREIGHTLINER M2");
                    assertThat(line.getUnitPrice()).isEqualByComparingTo("450.00");
                    assertThat(line.getQuantity()).isEqualTo(2);
                    assertThat(line.getSubtotal()).isEqualByComparingTo("900.00");
                });
    }

    /**
     * Lo que la secretaria escribe se compara sin mayusculas ni espacios, pero
     * lo que se guarda es SIEMPRE el nombre del catalogo. Asi el historico no
     * acumula una grafia distinta por cada persona que lo teclea.
     */
    @Test
    void aProductNameDifferingInCaseAndSpacesIsAcceptedAndStoredCanonical() {
        when(currentUserService.getCurrentEmployee()).thenReturn(employee);
        givenCompletedSale();
        givenProductNamed("HOJA DE RESORTE FREIGHTLINER M2");
        givenSaveEchoesBack();

        service.create(request(
                List.of(product("  hoja de resorte freightliner m2 ", "450.00", 2, "900.00")),
                List.of()));

        assertThat(captureSaved().getProducts())
                .singleElement()
                .extracting(DailyLogProduct::getName)
                .isEqualTo("HOJA DE RESORTE FREIGHTLINER M2");

        // El nombre viaja ya recortado al repositorio.
        ArgumentCaptor<String> queried = ArgumentCaptor.forClass(String.class);
        verify(productRepository).findByNameIgnoringCaseAndSpaces(queried.capture());
        assertThat(queried.getValue()).isEqualTo("hoja de resorte freightliner m2");
    }

    @Test
    void anInventedProductNameIsRejected() {
        when(currentUserService.getCurrentEmployee()).thenReturn(employee);
        givenCompletedSale();
        when(productRepository.findByNameIgnoringCaseAndSpaces(any()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(request(
                List.of(product("PRODUCTO INVENTADO 123", "10.00", 1, "10.00")),
                List.of())))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("No existe un producto con el nombre indicado");

        verify(repository, never()).save(any());
    }

    // ── Validacion del nombre del servicio ───────────────────────

    @Test
    void anExistingServiceNameIsAcceptedAndStoredCanonical() {
        when(currentUserService.getCurrentEmployee()).thenReturn(employee);
        givenCompletedSale();
        givenServiceNamed("ALINEACION");
        givenSaveEchoesBack();

        service.create(request(
                List.of(),
                List.of(serviceLine(" alineacion ", "150.00", 1, "150.00"))));

        assertThat(captureSaved().getServices())
                .singleElement()
                .satisfies(line -> {
                    assertThat(line.getName()).isEqualTo("ALINEACION");
                    assertThat(line.getSubtotal()).isEqualByComparingTo("150.00");
                });
    }

    @Test
    void anInventedServiceNameIsRejected() {
        when(currentUserService.getCurrentEmployee()).thenReturn(employee);
        givenCompletedSale();
        when(jobRepository.findByNameIgnoringCaseAndSpaces(eq(workshopId), any()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(request(
                List.of(),
                List.of(serviceLine("SERVICIO INVENTADO 123", "10.00", 1, "10.00")))))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("No existe un servicio con el nombre indicado");

        verify(repository, never()).save(any());
    }

    /**
     * Un servicio se comprueba SOLO contra JobRepository. Validarlo contra el
     * catalogo de productos daria por bueno cualquier repuesto como si fuera un
     * servicio, porque todo Job tiene un producto detras pero no al reves.
     */
    @Test
    void servicesAreNeverValidatedAgainstTheProductCatalogue() {
        when(currentUserService.getCurrentEmployee()).thenReturn(employee);
        givenCompletedSale();
        givenServiceNamed("ALINEACION");
        givenSaveEchoesBack();

        service.create(request(
                List.of(),
                List.of(serviceLine("ALINEACION", "150.00", 1, "150.00"))));

        verifyNoInteractions(productRepository);
    }

    // ── Importes ─────────────────────────────────────────────────

    @Test
    void aSubtotalThatDoesNotMatchPriceTimesQuantityIsRejected() {
        when(currentUserService.getCurrentEmployee()).thenReturn(employee);
        givenCompletedSale();

        assertThatThrownBy(() -> service.create(request(
                List.of(product("BUJE", "100.00", 2, "900.00")),
                List.of())))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("El subtotal no coincide");

        verify(repository, never()).save(any());
    }

    @Test
    void aSubtotalWrittenWithAnotherScaleIsStillTheSameAmount() {
        when(currentUserService.getCurrentEmployee()).thenReturn(employee);
        givenCompletedSale();
        givenProductNamed("BUJE");
        givenSaveEchoesBack();

        // 75 * 4 = 300; el cliente manda 300.00 y es el mismo importe.
        service.create(request(
                List.of(product("BUJE", "75", 4, "300.00")),
                List.of()));

        assertThat(captureSaved().getProducts()).hasSize(1);
    }

    @Test
    void theServiceSubtotalIsCheckedToo() {
        when(currentUserService.getCurrentEmployee()).thenReturn(employee);
        givenCompletedSale();

        assertThatThrownBy(() -> service.create(request(
                List.of(),
                List.of(serviceLine("ALINEACION", "150.00", 2, "150.00")))))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("El subtotal no coincide");

        verify(repository, never()).save(any());
    }

    /**
     * El precio del registro es el declarado, no el vigente. El catalogo puede
     * haber subido de precio despues de emitir la factura y el historico tiene
     * que seguir diciendo a cuanto se vendio.
     */
    @Test
    void thePriceIsNotComparedAgainstTheCurrentCataloguePrice() {
        when(currentUserService.getCurrentEmployee()).thenReturn(employee);
        givenCompletedSale();
        givenProductNamed("BUJE");
        givenSaveEchoesBack();

        service.create(request(
                List.of(product("BUJE", "1.00", 1, "1.00")),
                List.of()));

        assertThat(captureSaved().getProducts())
                .singleElement()
                .extracting(DailyLogProduct::getUnitPrice)
                .isEqualTo(new BigDecimal("1.00"));
    }

    // ── Venta de referencia ──────────────────────────────────────

    @Test
    void aPendingSaleCannotBeLogged() {
        when(currentUserService.getCurrentEmployee()).thenReturn(employee);
        givenSale(sale(SaleStatus.PENDING));

        assertThatThrownBy(() -> service.create(request(
                List.of(product("BUJE", "75.00", 4, "300.00")),
                List.of())))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("completada");

        verify(repository, never()).save(any());
    }

    @Test
    void aCancelledSaleCannotBeLogged() {
        when(currentUserService.getCurrentEmployee()).thenReturn(employee);
        givenSale(sale(SaleStatus.CANCELLED));

        assertThatThrownBy(() -> service.create(request(
                List.of(product("BUJE", "75.00", 4, "300.00")),
                List.of())))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("completada");

        verify(repository, never()).save(any());
    }

    /**
     * Una cambiaria emitida y todavia sin cobrar SI se registra: el documento ya
     * salio, que es lo unico que la secretaria necesita tener en la mano.
     */
    @Test
    void anIssuedExchangeInvoiceStillAwaitingPaymentCanBeLogged() {
        when(currentUserService.getCurrentEmployee()).thenReturn(employee);
        Sale exchangeInvoice = sale(SaleStatus.COMPLETED);
        exchangeInvoice.setInvoiceType(InvoiceType.EXCHANGE_INVOICE);
        exchangeInvoice.setPaymentStatus(PaymentStatus.PENDING);
        givenSale(exchangeInvoice);
        givenProductNamed("BUJE");
        givenSaveEchoesBack();

        service.create(request(
                List.of(product("BUJE", "75.00", 4, "300.00")),
                List.of()));

        assertThat(captureSaved().getSale().getId()).isEqualTo(saleId);
    }

    @Test
    void aSaleFromAnotherWorkshopIsNotVisible() {
        when(currentUserService.getCurrentEmployee()).thenReturn(employee);
        when(saleRepository.findByIdAndWorkshopId(saleId, workshopId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(request(
                List.of(product("BUJE", "75.00", 4, "300.00")),
                List.of())))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("No existe la venta especificada");

        verify(repository, never()).save(any());
    }

    @Test
    void aSaleCannotBeLoggedTwice() {
        when(currentUserService.getCurrentEmployee()).thenReturn(employee);
        givenCompletedSale();
        when(repository.existsBySaleId(saleId)).thenReturn(true);

        assertThatThrownBy(() -> service.create(request(
                List.of(product("BUJE", "75.00", 4, "300.00")),
                List.of())))
                .isInstanceOf(EntityAlreadyExistsException.class)
                .hasMessageContaining("ya tiene un registro");

        verify(repository, never()).save(any());
    }

    // ── Arrays y atomicidad ──────────────────────────────────────

    @Test
    void aLogWithNeitherProductsNorServicesIsRejected() {
        assertThatThrownBy(() -> service.create(request(List.of(), List.of())))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("al menos un producto o un servicio");

        verify(repository, never()).save(any());
    }

    @Test
    void nullArraysAreTreatedAsEmpty() {
        assertThatThrownBy(() -> service.create(
                new CreateDailyLogRequestDTO(saleId, null, null, null)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("al menos un producto o un servicio");
    }

    /**
     * Cinco productos y tres servicios, y el tercer servicio no existe: no se
     * guarda un registro a medias con las siete lineas buenas.
     */
    @Test
    void aSingleUnknownLineRollsBackTheWholeRequest() {
        when(currentUserService.getCurrentEmployee()).thenReturn(employee);
        givenCompletedSale();
        givenProductNamed("BUJE");
        when(jobRepository.findByNameIgnoringCaseAndSpaces(eq(workshopId), any()))
                .thenReturn(Optional.of(Job.builder()
                        .id(UUID.randomUUID())
                        .product(Product.builder().id(UUID.randomUUID()).name("ALINEACION").build())
                        .build()))
                .thenReturn(Optional.of(Job.builder()
                        .id(UUID.randomUUID())
                        .product(Product.builder().id(UUID.randomUUID()).name("BALANCEO").build())
                        .build()))
                .thenReturn(Optional.empty());

        List<DailyLogProductRequestDTO> products = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            products.add(product("BUJE", "75.00", 4, "300.00"));
        }
        List<DailyLogServiceRequestDTO> services = List.of(
                serviceLine("ALINEACION", "150.00", 1, "150.00"),
                serviceLine("BALANCEO", "100.00", 1, "100.00"),
                serviceLine("SERVICIO INVENTADO 123", "50.00", 1, "50.00"));

        assertThatThrownBy(() -> service.create(request(products, services)))
                .isInstanceOf(ValidationException.class);

        verify(repository, never()).save(any());
    }

    // ── El historico no guarda relaciones ────────────────────────

    /**
     * Guardia estructural: si alguien anade mañana un `@ManyToOne Product` o un
     * `productId`, esta prueba lo dice. El unico UUID de la linea es su propia
     * clave.
     */
    @Test
    void aProductLineHoldsNoReferenceToTheCatalogue() {
        assertThat(DailyLogProduct.class.getDeclaredFields())
                .noneMatch(field -> field.getType() == Product.class);
        assertThat(uuidFieldNames(DailyLogProduct.class)).containsExactly("id");
    }

    @Test
    void aServiceLineHoldsNoReferenceToTheCatalogue() {
        Class<?> serviceLine = com.cns.spring_house.dailylog.entity.DailyLogService.class;
        assertThat(serviceLine.getDeclaredFields())
                .noneMatch(field -> field.getType() == Job.class
                        || field.getType() == Product.class);
        assertThat(uuidFieldNames(serviceLine)).containsExactly("id");
    }

    private List<String> uuidFieldNames(Class<?> type) {
        return java.util.Arrays.stream(type.getDeclaredFields())
                .filter(field -> field.getType() == UUID.class)
                .map(Field::getName)
                .toList();
    }

}
