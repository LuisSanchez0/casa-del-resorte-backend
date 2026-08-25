package com.cns.spring_house.sale.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Properties;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.cns.spring_house.employee.entity.Employee;
import com.cns.spring_house.family.entity.Family;
import com.cns.spring_house.family.entity.FamilyCategory;
import com.cns.spring_house.job.entity.Job;
import com.cns.spring_house.job.entity.JobLog;
import com.cns.spring_house.job.repository.JobRepository;
import com.cns.spring_house.product.entity.Product;
import com.cns.spring_house.sale.entity.InvoiceType;
import com.cns.spring_house.sale.entity.PaymentMethod;
import com.cns.spring_house.sale.entity.PaymentStatus;
import com.cns.spring_house.sale.entity.Sale;
import com.cns.spring_house.sale.entity.SaleDetail;
import com.cns.spring_house.sale.entity.SaleStatus;
import com.cns.spring_house.workresult.entity.WorkResult;
import com.cns.spring_house.workshop.entity.Workshop;

/**
 * Consultas y garantias reales contra PostgreSQL, sobre una base temporal creada
 * por la propia prueba. Nunca utiliza la base de desarrollo.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class SaleRepositoryTest {

    private static final String TEMPORARY_DATABASE = "sale_it";

    @Autowired
    private SaleRepository repository;

    @Autowired
    private SaleDetailRepository detailRepository;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private TestEntityManager entityManager;

    @DynamicPropertySource
    static void temporaryDatabase(DynamicPropertyRegistry registry)
            throws IOException, SQLException {
        Properties environment = loadEnvironment();
        String host = value(environment, "DB_HOST", "localhost");
        String port = value(environment, "DB_PORT", "5432");
        String username = value(environment, "DB_USER", "postgres");
        String password = value(environment, "DB_PASS", null);
        String server = "jdbc:postgresql://" + host + ":" + port + "/";

        try (Connection connection = DriverManager.getConnection(
                server + "postgres", username, password);
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "DROP DATABASE IF EXISTS " + TEMPORARY_DATABASE
                    + " WITH (FORCE)");
            statement.execute("CREATE DATABASE " + TEMPORARY_DATABASE);
        }

        registry.add("spring.datasource.url",
                () -> server + TEMPORARY_DATABASE);
        registry.add("spring.datasource.username", () -> username);
        registry.add("spring.datasource.password", () -> password);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        // Cada clase con @DynamicPropertySource levanta su propio contexto, y
        // el TestContext los CACHEA hasta que acaba la JVM: cada pool que no
        // se acota se queda con las 10 conexiones por defecto de Hikari, y la
        // suma de todos agota max_connections de PostgreSQL. Mismo tope que ya
        // usan las demas pruebas de base de datos del proyecto.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 2);
        registry.add("spring.datasource.hikari.minimum-idle", () -> 0);
    }

    private static Properties loadEnvironment() throws IOException {
        Properties environment = new Properties();
        Path envFile = Path.of(".env");
        if (Files.exists(envFile)) {
            try (InputStream input = Files.newInputStream(envFile)) {
                environment.load(input);
            }
        }
        return environment;
    }

    private static String value(
            Properties environment, String key, String fallback) {
        String fromSystem = System.getenv(key);
        if (fromSystem != null && !fromSystem.isBlank()) {
            return fromSystem;
        }
        return environment.getProperty(key, fallback);
    }

    private Workshop workshop(String name) {
        return entityManager.persistAndFlush(Workshop.builder()
                .name(name)
                .address("DIRECCION")
                .build());
    }

    private Product product(FamilyCategory category) {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        Family family = entityManager.persistAndFlush(Family.builder()
                .code(unique)
                .name(category.name() + " " + unique)
                .requiresModel(false)
                .category(category)
                .build());
        return entityManager.persistAndFlush(Product.builder()
                .family(family)
                .name("PRODUCTO " + unique)
                .attributes(new HashMap<>())
                .build());
    }

    private Employee employee(Workshop workshop) {
        return entityManager.persistAndFlush(Employee.builder()
                .workshop(workshop)
                .firstName("NOMBRE")
                .lastName("APELLIDO")
                .dpi(UUID.randomUUID().toString().substring(0, 13))
                .birthDate(LocalDate.of(1990, 1, 1))
                .build());
    }

    private Sale sale(Workshop workshop, String plate) {
        return entityManager.persistAndFlush(Sale.builder()
                .workshop(workshop)
                .employee(employee(workshop))
                .plate(plate)
                .build());
    }

    @Test
    void findByIdAndWorkshopIdDoesNotReachAnotherWorkshop() {
        Workshop workshopA = workshop("TALLER A");
        Workshop workshopB = workshop("TALLER B");
        Sale a1 = sale(workshopA, null);
        Sale b1 = sale(workshopB, null);

        assertThat(repository.findByIdAndWorkshopId(a1.getId(), workshopA.getId()))
                .isPresent();
        assertThat(repository.findByIdAndWorkshopId(b1.getId(), workshopA.getId()))
                .isEmpty();
    }

    @Test
    void aNewSaleIsStoredOpenAndUnpaid() {
        Sale sale = sale(workshop("TALLER A"), null);

        entityManager.clear();
        Sale stored = repository.findById(sale.getId()).orElseThrow();

        assertThat(stored.getStatus()).isEqualTo(SaleStatus.PENDING);
        assertThat(stored.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(stored.getPaymentMethod()).isNull();
        assertThat(stored.getPaidAt()).isNull();
    }

    @Test
    void thePlateIsStoredAsGivenByTheService() {
        Sale sale = sale(workshop("TALLER A"), "P123ABC");

        entityManager.clear();

        assertThat(repository.findById(sale.getId()).orElseThrow().getPlate())
                .isEqualTo("P123ABC");
    }

    /**
     * La base impide estados de cobro a medias: una venta cobrada tiene fecha de
     * cobro, y una pendiente no admite ni metodo ni fecha.
     */
    @Test
    void theDatabaseRejectsAPaidSaleWithoutPaymentDate() {
        Workshop workshopA = workshop("TALLER A");
        Sale sale = Sale.builder()
                .workshop(workshopA)
                .employee(employee(workshopA))
                .status(SaleStatus.COMPLETED)
                .invoiceType(InvoiceType.INVOICE)
                .paymentStatus(PaymentStatus.PAID)
                .paymentMethod(PaymentMethod.CASH)
                .build();

        assertThatThrownBy(() -> entityManager.persistAndFlush(sale))
                .rootCause()
                .hasMessageContaining("ck_sale_payment_consistency");
    }

    @Test
    void theDatabaseRejectsAPendingSaleThatAlreadyHasAPaymentMethod() {
        Workshop workshopA = workshop("TALLER A");
        Sale sale = Sale.builder()
                .workshop(workshopA)
                .employee(employee(workshopA))
                .status(SaleStatus.COMPLETED)
                .invoiceType(InvoiceType.EXCHANGE_INVOICE)
                .paymentStatus(PaymentStatus.PENDING)
                .paymentMethod(PaymentMethod.CASH)
                .paidAt(LocalDateTime.now())
                .build();

        assertThatThrownBy(() -> entityManager.persistAndFlush(sale))
                .rootCause()
                .hasMessageContaining("ck_sale_payment_consistency");
    }

    @Test
    void anIssuedExchangeInvoiceIsStoredCompletedButUnpaid() {
        Workshop workshopA = workshop("TALLER A");
        Sale sale = entityManager.persistAndFlush(Sale.builder()
                .workshop(workshopA)
                .employee(employee(workshopA))
                .status(SaleStatus.COMPLETED)
                .invoiceType(InvoiceType.EXCHANGE_INVOICE)
                .paymentStatus(PaymentStatus.PENDING)
                .completedAt(LocalDateTime.now())
                .build());

        entityManager.clear();
        Sale stored = repository.findById(sale.getId()).orElseThrow();

        assertThat(stored.getStatus()).isEqualTo(SaleStatus.COMPLETED);
        assertThat(stored.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(stored.getInvoiceType())
                .isEqualTo(InvoiceType.EXCHANGE_INVOICE);
    }

    /**
     * El servicio identifica el JOB por producto. En produccion Product.id, Job.id,
     * JobLog.id y WorkResult.id son todos distintos, que es justo lo que rompia el
     * contrato anterior.
     */
    @Test
    void aJobIsResolvedByItsProductWithAllIdsBeingDifferent() {
        Workshop workshopA = workshop("TALLER A");
        Product jobProduct = product(FamilyCategory.JOB);
        Job job = entityManager.persistAndFlush(Job.builder()
                .product(jobProduct)
                .workshop(workshopA)
                .price(new BigDecimal("150.00"))
                .build());
        JobLog jobLog = entityManager.persistAndFlush(JobLog.builder()
                .employee(employee(workshopA))
                .job(job)
                .performedDate(LocalDateTime.now())
                .build());
        WorkResult workResult = entityManager.persistAndFlush(WorkResult.builder()
                .workshop(workshopA)
                .jobLog(jobLog)
                .build());

        assertThat(jobProduct.getId())
                .isNotEqualTo(job.getId())
                .isNotEqualTo(jobLog.getId())
                .isNotEqualTo(workResult.getId());

        assertThat(jobRepository.findByProductIdAndWorkshopId(
                jobProduct.getId(), workshopA.getId()))
                .contains(job);
        // El id del Job ya no sirve como identificador de venta.
        assertThat(jobRepository.findByProductIdAndWorkshopId(
                job.getId(), workshopA.getId()))
                .isEmpty();
    }

    @Test
    void aJobOfAnotherWorkshopIsNotResolvable() {
        Workshop workshopA = workshop("TALLER A");
        Workshop workshopB = workshop("TALLER B");
        Product jobProduct = product(FamilyCategory.JOB);
        entityManager.persistAndFlush(Job.builder()
                .product(jobProduct)
                .workshop(workshopA)
                .price(new BigDecimal("150.00"))
                .build());

        assertThat(jobRepository.findByProductIdAndWorkshopId(
                jobProduct.getId(), workshopB.getId()))
                .isEmpty();
    }

    /** Un WorkResult no puede quedar reservado en dos ventas distintas. */
    @Test
    void theDatabaseRejectsTheSameWorkResultInTwoSales() {
        Workshop workshopA = workshop("TALLER A");
        Product jobProduct = product(FamilyCategory.JOB);
        Job job = entityManager.persistAndFlush(Job.builder()
                .product(jobProduct)
                .workshop(workshopA)
                .price(new BigDecimal("150.00"))
                .build());
        JobLog jobLog = entityManager.persistAndFlush(JobLog.builder()
                .employee(employee(workshopA))
                .job(job)
                .performedDate(LocalDateTime.now())
                .build());
        WorkResult workResult = entityManager.persistAndFlush(WorkResult.builder()
                .workshop(workshopA)
                .jobLog(jobLog)
                .build());

        Sale first = sale(workshopA, null);
        entityManager.persistAndFlush(SaleDetail.builder()
                .sale(first)
                .product(jobProduct)
                .workResult(workResult)
                .quantity(1)
                .price(new BigDecimal("150.00"))
                .subTotal(new BigDecimal("150.00"))
                .build());

        assertThat(detailRepository.existsByWorkResultId(workResult.getId()))
                .isTrue();

        Sale second = sale(workshopA, null);
        assertThatThrownBy(() -> entityManager.persistAndFlush(SaleDetail.builder()
                .sale(second)
                .product(jobProduct)
                .workResult(workResult)
                .quantity(1)
                .price(new BigDecimal("150.00"))
                .subTotal(new BigDecimal("150.00"))
                .build()))
                .rootCause()
                .hasMessageContaining("uk_sale_detail_work_result");
    }

    @Test
    void aFreeWorkResultIsNotReportedAsTaken() {
        Workshop workshopA = workshop("TALLER A");
        Product jobProduct = product(FamilyCategory.JOB);
        Job job = entityManager.persistAndFlush(Job.builder()
                .product(jobProduct)
                .workshop(workshopA)
                .price(new BigDecimal("150.00"))
                .build());
        JobLog jobLog = entityManager.persistAndFlush(JobLog.builder()
                .employee(employee(workshopA))
                .job(job)
                .performedDate(LocalDateTime.now())
                .build());
        WorkResult workResult = entityManager.persistAndFlush(WorkResult.builder()
                .workshop(workshopA)
                .jobLog(jobLog)
                .build());

        assertThat(detailRepository.existsByWorkResultId(workResult.getId()))
                .isFalse();
    }

    @Test
    void aSaleDetailOfAnotherWorkshopIsNotReachable() {
        Workshop workshopA = workshop("TALLER A");
        Workshop workshopB = workshop("TALLER B");
        Product normalProduct = product(FamilyCategory.NORMAL);
        Sale saleB = sale(workshopB, null);
        SaleDetail detail = entityManager.persistAndFlush(SaleDetail.builder()
                .sale(saleB)
                .product(normalProduct)
                .quantity(1)
                .price(BigDecimal.TEN)
                .subTotal(BigDecimal.TEN)
                .build());

        assertThat(detailRepository.findByIdAndSaleWorkshopId(
                detail.getId(), workshopA.getId()))
                .isEmpty();
        assertThat(detailRepository.findByIdAndSaleWorkshopId(
                detail.getId(), workshopB.getId()))
                .isPresent();
    }

}
