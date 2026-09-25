package com.cns.spring_house.sale.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
import org.springframework.data.domain.Sort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.cns.spring_house.customer.entity.Customer;
import com.cns.spring_house.employee.entity.Employee;
import com.cns.spring_house.family.entity.Family;
import com.cns.spring_house.family.entity.FamilyCategory;
import com.cns.spring_house.product.entity.Product;
import com.cns.spring_house.sale.dto.ExchangeInvoiceSummaryDTO;
import com.cns.spring_house.sale.entity.InvoiceType;
import com.cns.spring_house.sale.entity.PaymentStatus;
import com.cns.spring_house.sale.entity.Sale;
import com.cns.spring_house.sale.entity.SaleDetail;
import com.cns.spring_house.sale.entity.SaleStatus;
import com.cns.spring_house.testsupport.TestDatabases;
import com.cns.spring_house.workshop.entity.Workshop;

import java.util.HashMap;

/**
 * Evidencia de que la cartera cambiaria NO carga las lineas de cada venta.
 *
 * No basta con que el DTO no tenga `details`: el problema era la consulta.
 * `Sale.details` es EAGER, de modo que devolver entidades Sale obliga a
 * Hibernate a traer cada linea —con su producto y su item— aunque el mapeo
 * despues las tire. Aqui se interceptan las sentencias SQL realmente enviadas a
 * PostgreSQL y se comprueba que la consulta del resumen nunca menciona
 * `sale_detail`, mientras que leer la entidad si lo hace.
 *
 * Usa una base temporal creada por la propia prueba; nunca la de desarrollo.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class ExchangeInvoicePortfolioQueryTest {

    private static final String TEMPORARY_DATABASE = "sale_portfolio_it";

    /** Sentencias enviadas a la base durante la ultima operacion observada. */
    private static final List<String> STATEMENTS = new CopyOnWriteArrayList<>();

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

    @DynamicPropertySource
    static void temporaryDatabase(DynamicPropertyRegistry registry) {
        String url = TestDatabases.provision(TEMPORARY_DATABASE);

        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", TestDatabases::username);
        registry.add("spring.datasource.password", TestDatabases::password);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        // Cada clase con @DynamicPropertySource levanta su propio contexto, y
        // el TestContext los CACHEA hasta que acaba la JVM: cada pool que no
        // se acota se queda con las 10 conexiones por defecto de Hikari, y la
        // suma de todos agota max_connections de PostgreSQL. Mismo tope que ya
        // usan las demas pruebas de base de datos del proyecto.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 2);
        registry.add("spring.datasource.hikari.minimum-idle", () -> 0);
        registry.add(
                "spring.jpa.properties.hibernate.session_factory.statement_inspector",
                RecordingInspector.class::getName);
    }

    @BeforeEach
    void setUp() {
        workshop = entityManager.persistAndFlush(Workshop.builder()
                .name("TALLER " + unique())
                .address("DIRECCION")
                .build());
        issuedExchangeInvoiceWithDetails(3);
        issuedExchangeInvoiceWithDetails(2);
    }

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private Sale issuedExchangeInvoiceWithDetails(int lines) {
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
                .nit(unique())
                .name("CLIENTE " + code)
                .build());
        Employee employee = entityManager.persistAndFlush(Employee.builder()
                .workshop(workshop)
                .firstName("NOMBRE")
                .lastName("APELLIDO")
                .dpi(UUID.randomUUID().toString().substring(0, 13))
                .birthDate(LocalDate.of(1990, 1, 1))
                .build());
        Sale sale = entityManager.persistAndFlush(Sale.builder()
                .workshop(workshop)
                .employee(employee)
                .customer(customer)
                .plate("P123ABC")
                .status(SaleStatus.COMPLETED)
                .invoiceType(InvoiceType.EXCHANGE_INVOICE)
                .paymentStatus(PaymentStatus.PENDING)
                .completedAt(LocalDateTime.now())
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

    /** Deja el contexto limpio y empieza a grabar SQL desde cero. */
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

    @Test
    void thePortfolioQueryNeverReadsTheSaleDetails() {
        startRecording();

        List<ExchangeInvoiceSummaryDTO> portfolio =
                repository.findExchangeInvoiceSummaries(
                        workshop.getId(),
                        null,
                        PageRequest.of(0, 20, Sort.by(
                                Sort.Order.desc("completedAt"),
                                Sort.Order.desc("id"))))
                        .getContent();

        assertThat(portfolio).hasSize(2);
        assertThat(selectsTouchingSaleDetail())
                .as("la cartera no puede leer las lineas de cada venta")
                .isEmpty();
        // Una sola sentencia para toda la cartera: nada por fila. Spring Data ni
        // siquiera cuenta, porque la primera pagina ya abarca todo.
        assertThat(STATEMENTS).hasSize(1);
    }

    /**
     * Con varias paginas se añade el conteo, y ni la pagina ni el conteo tocan
     * las lineas: el coste sigue siendo constante, no proporcional al detalle.
     */
    @Test
    void pagingAddsOnlyTheCountQuery() {
        startRecording();

        var page = repository.findExchangeInvoiceSummaries(
                workshop.getId(), null,
                PageRequest.of(0, 1, Sort.by(Sort.Order.desc("completedAt"))));

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(selectsTouchingSaleDetail()).isEmpty();
        assertThat(STATEMENTS).hasSize(2);
    }

    /**
     * Contraste: leer la entidad Sale SI arrastra las lineas. Es exactamente el
     * coste que la proyeccion evita, y prueba que el interceptor detecta el caso.
     */
    @Test
    void readingTheSaleEntityDoesReadItsDetails() {
        startRecording();

        List<Sale> sales = repository.findAll();

        assertThat(sales).isNotEmpty();
        assertThat(selectsTouchingSaleDetail())
                .as("Sale.details es EAGER: leer la entidad trae las lineas")
                .isNotEmpty();
    }

    /** Filtrar tampoco añade lecturas de lineas. */
    @Test
    void filteringDoesNotReadTheSaleDetailsEither() {
        startRecording();

        repository.findExchangeInvoiceSummaries(
                workshop.getId(),
                PaymentStatus.PENDING,
                PageRequest.of(0, 20, Sort.by(Sort.Order.desc("completedAt"))));

        assertThat(selectsTouchingSaleDetail()).isEmpty();
        assertThat(STATEMENTS).hasSize(1);
    }

    /**
     * La proyeccion tampoco carga el empleado ni el taller: el unico JOIN es el
     * del cliente, que si aparece en la pantalla.
     */
    @Test
    void thePortfolioQueryOnlyJoinsTheCustomer() {
        startRecording();

        repository.findExchangeInvoiceSummaries(
                workshop.getId(), null, PageRequest.of(0, 20));

        String pageQuery = STATEMENTS.stream()
                .filter(sql -> sql.contains("from sale"))
                .filter(sql -> !sql.contains("count("))
                .findFirst()
                .orElseThrow();

        assertThat(pageQuery).contains("join customer");
        assertThat(pageQuery).doesNotContain("join employee");
        assertThat(pageQuery).doesNotContain("join workshop");
        assertThat(pageQuery).doesNotContain("sale_detail");
    }

}
