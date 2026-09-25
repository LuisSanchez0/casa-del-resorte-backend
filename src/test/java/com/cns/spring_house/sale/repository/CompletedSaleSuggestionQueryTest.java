package com.cns.spring_house.sale.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.cns.spring_house.customer.entity.Customer;
import com.cns.spring_house.employee.entity.Employee;
import com.cns.spring_house.family.entity.Family;
import com.cns.spring_house.family.entity.FamilyCategory;
import com.cns.spring_house.product.entity.Product;
import com.cns.spring_house.sale.dto.SaleSummaryDTO;
import com.cns.spring_house.sale.entity.InvoiceType;
import com.cns.spring_house.sale.entity.PaymentMethod;
import com.cns.spring_house.sale.entity.PaymentStatus;
import com.cns.spring_house.sale.entity.Sale;
import com.cns.spring_house.sale.entity.SaleDetail;
import com.cns.spring_house.sale.entity.SaleStatus;
import com.cns.spring_house.shared.util.LikePatterns;
import com.cns.spring_house.testsupport.TestDatabases;
import com.cns.spring_house.workshop.entity.Workshop;

/**
 * El buscador de facturas de la bitacora tiene dos exigencias que solo se
 * pueden comprobar contra la base real: que encuentre una venta por un TROZO de
 * su identificador —la secretaria teclea los primeros caracteres del numero
 * impreso, no el UUID entero— y que sugerir diez facturas no lea las lineas de
 * ninguna.
 *
 * Se interceptan las sentencias enviadas a PostgreSQL, igual que en
 * {@link ExchangeInvoicePortfolioQueryTest}, porque el problema nunca fue la
 * forma del DTO sino la consulta: {@code Sale.details} es EAGER.
 *
 * Usa una base temporal creada por la propia prueba; nunca la de desarrollo.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class CompletedSaleSuggestionQueryTest {

    private static final String TEMPORARY_DATABASE = "sale_suggestions_it";

    private static final List<String> STATEMENTS = new CopyOnWriteArrayList<>();

    /** El mismo orden determinista que usa el servicio. */
    private static final Sort MOST_RECENTLY_ISSUED = Sort.by(
            Sort.Order.desc("completedAt"),
            Sort.Order.desc("id"));

    /** Registrado por Hibernate: necesita constructor publico sin argumentos. */
    public static class RecordingInspector implements StatementInspector {

        @Override
        public String inspect(String sql) {
            STATEMENTS.add(sql.toLowerCase(Locale.ROOT));
            return sql;
        }

    }

    @Autowired
    private SaleRepository repository;

    @Autowired
    private TestEntityManager entityManager;

    private Workshop workshop;
    private Workshop otherWorkshop;

    @DynamicPropertySource
    static void temporaryDatabase(DynamicPropertyRegistry registry) {
        String url = TestDatabases.provision(TEMPORARY_DATABASE);

        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", TestDatabases::username);
        registry.add("spring.datasource.password", TestDatabases::password);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        // Mismo tope que el resto de pruebas con base propia. Spring cachea un
        // contexto —y su pool— por cada clase, y todos siguen vivos hasta el
        // final de la ejecucion: pedir el pool por defecto agota el
        // `max_connections` de PostgreSQL y la victima es una clase cualquiera.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 2);
        registry.add(
                "spring.jpa.properties.hibernate.session_factory.statement_inspector",
                RecordingInspector.class::getName);
    }

    @BeforeEach
    void setUp() {
        workshop = persistWorkshop();
        otherWorkshop = persistWorkshop();
    }

    private Workshop persistWorkshop() {
        return entityManager.persistAndFlush(Workshop.builder()
                .name("TALLER " + unique())
                .address("DIRECCION")
                .build());
    }

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private Sale sale(
            Workshop owner,
            SaleStatus status,
            InvoiceType invoiceType,
            PaymentStatus paymentStatus,
            PaymentMethod paymentMethod,
            String customerName,
            String nit,
            String plate,
            LocalDateTime completedAt,
            LocalDateTime paidAt,
            int lines) {
        String code = unique();
        Family family = entityManager.persistAndFlush(Family.builder()
                .code(code)
                .name("NORMAL " + code)
                .requiresModel(false)
                .category(FamilyCategory.NORMAL)
                .build());
        Product product = entityManager.persistAndFlush(Product.builder()
                .family(family)
                .name("PRODUCTO " + code)
                .attributes(new HashMap<>())
                .build());
        Customer customer = entityManager.persistAndFlush(Customer.builder()
                .nit(nit)
                .name(customerName)
                .build());
        Employee employee = entityManager.persistAndFlush(Employee.builder()
                .workshop(owner)
                .firstName("NOMBRE")
                .lastName("APELLIDO")
                .dpi(UUID.randomUUID().toString().substring(0, 13))
                .birthDate(LocalDate.of(1990, 1, 1))
                .build());
        Sale sale = entityManager.persistAndFlush(Sale.builder()
                .workshop(owner)
                .employee(employee)
                .customer(customer)
                .plate(plate)
                .status(status)
                .invoiceType(invoiceType)
                .paymentStatus(paymentStatus)
                .paymentMethod(paymentMethod)
                .completedAt(completedAt)
                .paidAt(paidAt)
                .total(new BigDecimal("100.00"))
                .build());
        for (int index = 0; index < lines; index++) {
            entityManager.persistAndFlush(SaleDetail.builder()
                    .sale(sale)
                    .product(product)
                    .quantity(1)
                    .price(BigDecimal.TEN)
                    .subTotal(BigDecimal.TEN)
                    .build());
        }
        return sale;
    }

    /**
     * Factura normal cobrada en el acto. `paidAt` no es decorativo: la base
     * impone `ck_sale_payment_consistency`, que exige fecha de cobro en cuanto
     * el estado es PAID.
     */
    private Sale completedInvoice(String customerName, String nit, String plate, LocalDateTime completedAt) {
        return sale(workshop, SaleStatus.COMPLETED, InvoiceType.INVOICE, PaymentStatus.PAID,
                PaymentMethod.CASH, customerName, nit, plate, completedAt, completedAt, 3);
    }

    /**
     * Busca con el patron REAL que arma el servicio, no con uno escrito a mano:
     * si el escapado cambia, estas pruebas lo ven.
     *
     * `null` conserva el significado de "sin filtro de texto".
     */
    private List<SaleSummaryDTO> suggest(String query, int limit) {
        String pattern = query == null ? null : LikePatterns.containsIgnoreCase(query);
        Pageable page = PageRequest.of(0, limit, MOST_RECENTLY_ISSUED);
        return repository.findCompletedSaleSummaries(workshop.getId(), pattern, page);
    }

    private void startRecording() {
        entityManager.flush();
        entityManager.clear();
        STATEMENTS.clear();
    }

    private List<String> selectsTouchingSaleDetail() {
        return STATEMENTS.stream()
                .filter(sql -> sql.contains("sale_detail"))
                .toList();
    }

    /** El caso que motiva todo: un fragmento del identificador, no el UUID entero. */
    @Test
    void findsASaleByAFragmentOfItsIdentifier() {
        Sale target = completedInvoice("ACME", "12345678", "P123ABC", LocalDateTime.now());
        completedInvoice("OTRO CLIENTE", "87654321", "P999ZZZ", LocalDateTime.now());
        String fragment = target.getId().toString().substring(0, 6);

        List<SaleSummaryDTO> suggestions = suggest(fragment, 8);

        assertThat(suggestions)
                .extracting(SaleSummaryDTO::id)
                .contains(target.getId());
    }

    @Test
    void alsoMatchesNitNameAndPlate() {
        Sale target = completedInvoice("FERRETERIA CENTRAL", "55667788", "P777XYZ", LocalDateTime.now());
        completedInvoice("OTRO", "11112222", "P000AAA", LocalDateTime.now());

        assertThat(suggest("5566", 8)).extracting(SaleSummaryDTO::id).containsExactly(target.getId());
        assertThat(suggest("ferreteria", 8)).extracting(SaleSummaryDTO::id).containsExactly(target.getId());
        assertThat(suggest("777xyz", 8)).extracting(SaleSummaryDTO::id).containsExactly(target.getId());
    }

    /**
     * Una cambiaria sin cobrar esta emitida, que es lo unico que la bitacora
     * exige. Excluirla dejaria sin registrar toda la venta a credito.
     */
    @Test
    void includesCompletedExchangeInvoicesStillPendingOfPayment() {
        Sale unpaidExchange = sale(workshop, SaleStatus.COMPLETED, InvoiceType.EXCHANGE_INVOICE,
                PaymentStatus.PENDING, null, "CLIENTE CREDITO", "99887766", "P111BBB",
                LocalDateTime.now(), null, 2);

        List<SaleSummaryDTO> suggestions = suggest(null, 8);

        assertThat(suggestions).extracting(SaleSummaryDTO::id).contains(unpaidExchange.getId());
        SaleSummaryDTO found = suggestions.stream()
                .filter(summary -> summary.id().equals(unpaidExchange.getId()))
                .findFirst()
                .orElseThrow();
        assertThat(found.invoiceType()).isEqualTo(InvoiceType.EXCHANGE_INVOICE);
        assertThat(found.paymentStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(found.paymentMethod()).as("sin cobrar no hay forma de pago").isNull();
        assertThat(found.paidAt()).isNull();
    }

    @Test
    void reportsThePaymentMethodOfAPaidSale() {
        LocalDateTime now = LocalDateTime.now();
        Sale paid = sale(workshop, SaleStatus.COMPLETED, InvoiceType.EXCHANGE_INVOICE,
                PaymentStatus.PAID, PaymentMethod.TRANSFER, "CLIENTE", "12312312", "P222CCC",
                now, now, 1);

        SaleSummaryDTO found = suggest(null, 8).stream()
                .filter(summary -> summary.id().equals(paid.getId()))
                .findFirst()
                .orElseThrow();

        assertThat(found.paymentStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(found.paymentMethod()).isEqualTo(PaymentMethod.TRANSFER);
    }

    /** Una venta abierta, anulada o revertida no se puede registrar en la bitacora. */
    @Test
    void excludesSalesThatAreNotCompleted() {
        sale(workshop, SaleStatus.PENDING, null, PaymentStatus.PENDING, null,
                "ABIERTA", "10000001", "P001AAA", null, null, 1);
        sale(workshop, SaleStatus.CANCELLED, null, PaymentStatus.PENDING, null,
                "ANULADA", "10000002", "P002AAA", null, null, 1);
        sale(workshop, SaleStatus.REVERSED, InvoiceType.INVOICE, PaymentStatus.PENDING, null,
                "REVERTIDA", "10000003", "P003AAA", LocalDateTime.now(), null, 1);
        Sale valid = completedInvoice("VALIDA", "10000004", "P004AAA", LocalDateTime.now());

        assertThat(suggest(null, 8))
                .extracting(SaleSummaryDTO::id)
                .containsExactly(valid.getId());
    }

    /** El aislamiento ocurre en la sentencia, no filtrando despues en Java. */
    @Test
    void neverSuggestsSalesOfAnotherWorkshop() {
        Sale mine = completedInvoice("MIA", "20000001", "P100AAA", LocalDateTime.now());
        LocalDateTime issued = LocalDateTime.now();
        sale(otherWorkshop, SaleStatus.COMPLETED, InvoiceType.INVOICE, PaymentStatus.PAID,
                PaymentMethod.CASH, "AJENA", "20000002", "P200AAA", issued, issued, 1);

        assertThat(suggest(null, 8))
                .extracting(SaleSummaryDTO::id)
                .containsExactly(mine.getId());
    }

    @Test
    void ordersFromTheMostRecentlyIssued() {
        LocalDateTime now = LocalDateTime.now();
        Sale oldest = completedInvoice("A", "30000001", "P301AAA", now.minusDays(2));
        Sale newest = completedInvoice("B", "30000002", "P302AAA", now);
        Sale middle = completedInvoice("C", "30000003", "P303AAA", now.minusDays(1));

        assertThat(suggest(null, 8))
                .extracting(SaleSummaryDTO::id)
                .containsExactly(newest.getId(), middle.getId(), oldest.getId());
    }

    @Test
    void honoursTheResultLimit() {
        LocalDateTime now = LocalDateTime.now();
        for (int index = 0; index < 12; index++) {
            completedInvoice("CLIENTE " + index, "4000000" + index, "P4" + index + "AAA",
                    now.minusMinutes(index));
        }

        assertThat(suggest(null, 8)).hasSize(8);
        assertThat(suggest(null, 3)).hasSize(3);
    }

    // ── Comodines de LIKE escritos por una persona ─────────────
    //
    // El parametro va enlazado, asi que no hay inyeccion posible; lo que si
    // tenia efecto era el significado de comodin de `%` y `_`. Quien busca una
    // factura escribe texto literal.

    @Test
    void aPercentSignIsLiteralAndDoesNotMatchEveryInvoice() {
        LocalDateTime now = LocalDateTime.now();
        completedInvoice("ACME", "60000001", "P601AAA", now);
        completedInvoice("FERRETERIA", "60000002", "P602AAA", now.minusMinutes(1));
        Sale withPercent = completedInvoice("DESCUENTO 50% ANUAL", "60000003", "P603AAA", now.minusMinutes(2));

        // Sin escapar, "%" significaba "cualquier cosa" y devolvia las tres.
        assertThat(suggest("%", 8))
                .extracting(SaleSummaryDTO::id)
                .containsExactly(withPercent.getId());
    }

    @Test
    void anUnderscoreIsLiteralAndIsNotASingleCharacterWildcard() {
        LocalDateTime now = LocalDateTime.now();
        completedInvoice("ABC", "61000001", "P611AAA", now);
        Sale withUnderscore = completedInvoice("LOTE_2026", "61000002", "P612AAA", now.minusMinutes(1));

        assertThat(suggest("_", 8))
                .extracting(SaleSummaryDTO::id)
                .containsExactly(withUnderscore.getId());
        // "a_c" no puede casar con "ABC": el guion bajo ya no es un comodin.
        assertThat(suggest("a_c", 8)).isEmpty();
    }

    @Test
    void theEscapeCharacterDoesNotBreakTheQuery() {
        LocalDateTime now = LocalDateTime.now();
        completedInvoice("SIN BARRA", "62000001", "P621AAA", now);
        Sale withBackslash = completedInvoice("RUTA C\\NORTE", "62000002", "P622AAA", now.minusMinutes(1));

        assertThat(suggest("\\", 8))
                .extracting(SaleSummaryDTO::id)
                .containsExactly(withBackslash.getId());
        assertThat(suggest("c\\norte", 8))
                .extracting(SaleSummaryDTO::id)
                .containsExactly(withBackslash.getId());
    }

    @Test
    void literalWildcardsAreFoundExactlyAsTyped() {
        LocalDateTime now = LocalDateTime.now();
        Sale percent = completedInvoice("DESCUENTO 50% ANUAL", "63000001", "P631AAA", now);
        Sale underscore = completedInvoice("LOTE_2026", "63000002", "P632AAA", now.minusMinutes(1));

        assertThat(suggest("50%", 8))
                .extracting(SaleSummaryDTO::id)
                .containsExactly(percent.getId());
        assertThat(suggest("lote_2026", 8))
                .extracting(SaleSummaryDTO::id)
                .containsExactly(underscore.getId());
    }

    /** El texto en blanco sigue significando "las mas recientes", no "nada". */
    @Test
    void aQueryWithoutTextStillReturnsTheMostRecentInvoices() {
        LocalDateTime now = LocalDateTime.now();
        Sale newest = completedInvoice("A", "64000001", "P641AAA", now);
        Sale older = completedInvoice("B", "64000002", "P642AAA", now.minusDays(1));

        assertThat(suggest(null, 8))
                .extracting(SaleSummaryDTO::id)
                .containsExactly(newest.getId(), older.getId());
    }

    /**
     * La razon de ser de la proyeccion: ocho sugerencias son UNA sentencia y
     * ninguna lectura de lineas. Con entidades Sale serian ocho graficos
     * completos de venta.
     */
    @Test
    void suggestingNeverReadsTheSaleDetails() {
        LocalDateTime now = LocalDateTime.now();
        for (int index = 0; index < 8; index++) {
            completedInvoice("CLIENTE " + index, "5000000" + index, "P5" + index + "AAA",
                    now.minusMinutes(index));
        }
        startRecording();

        List<SaleSummaryDTO> suggestions = suggest(null, 8);

        assertThat(suggestions).hasSize(8);
        assertThat(selectsTouchingSaleDetail())
                .as("sugerir facturas no puede leer las lineas de ninguna")
                .isEmpty();
        assertThat(STATEMENTS)
                .as("una sola sentencia para todas las sugerencias, nada por fila")
                .hasSize(1);
    }

}
