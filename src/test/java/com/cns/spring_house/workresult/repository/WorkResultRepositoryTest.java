package com.cns.spring_house.workresult.repository;

import static java.util.Comparator.reverseOrder;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
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
import com.cns.spring_house.inventory.entity.Inventory;
import com.cns.spring_house.inventory.entity.InventoryItem;
import com.cns.spring_house.inventory.entity.InventoryItemStatus;
import com.cns.spring_house.job.entity.Job;
import com.cns.spring_house.job.entity.JobLog;
import com.cns.spring_house.product.entity.Product;
import com.cns.spring_house.sale.entity.Sale;
import com.cns.spring_house.sale.entity.TaxMode;
import com.cns.spring_house.sale.entity.SaleDetail;
import com.cns.spring_house.testsupport.TestDatabases;
import com.cns.spring_house.workresult.dto.WorkResultResponseDTO;
import com.cns.spring_house.workresult.dto.WorkResultSource;
import com.cns.spring_house.workresult.entity.WorkResult;
import com.cns.spring_house.workresult.entity.WorkResultStatus;
import com.cns.spring_house.workresult.mapper.WorkResultMapper;
import com.cns.spring_house.workresult.mapper.WorkResultMapperImpl;
import com.cns.spring_house.workshop.entity.Workshop;

/**
 * Ejecuta las consultas reales contra PostgreSQL sobre una base temporal
 * creada por la propia prueba. Nunca utiliza la base de desarrollo.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class WorkResultRepositoryTest {

    private static final String TEMPORARY_DATABASE = "work_result_it";

    private final WorkResultMapper mapper = new WorkResultMapperImpl();

    @Autowired
    private WorkResultRepository repository;

    @Autowired
    private TestEntityManager entityManager;

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
    }

    private Workshop workshop(String name) {
        return entityManager.persistAndFlush(Workshop.builder()
                .name(name)
                .address("DIRECCION")
                .build());
    }

    private Product product(String name, FamilyCategory category) {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        Family family = entityManager.persistAndFlush(Family.builder()
                .code(unique)
                .name(category.name() + " " + unique)
                .requiresModel(false)
                .category(category)
                .build());
        return entityManager.persistAndFlush(Product.builder()
                .family(family)
                .name(name)
                .attributes(new HashMap<>())
                .build());
    }

    /**
     * Registro de servicio realizado. La fecha de auditoria del WorkResult se
     * fuerza por SQL porque {@code createdAt} es gestionada por la auditoria
     * de JPA y no es actualizable desde la entidad.
     */
    private WorkResult jobWorkResult(
            Workshop workshop,
            LocalDateTime performedDate,
            LocalDateTime workResultCreatedAt) {
        Employee employee = entityManager.persistAndFlush(Employee.builder()
                .workshop(workshop)
                .firstName("NOMBRE")
                .lastName("APELLIDO")
                .dpi(UUID.randomUUID().toString().substring(0, 13))
                .birthDate(LocalDate.of(1990, 1, 1))
                .build());
        Job job = entityManager.persistAndFlush(Job.builder()
                .product(product("SERVICIO", FamilyCategory.JOB))
                .workshop(workshop)
                .price(BigDecimal.TEN)
                .build());
        JobLog jobLog = entityManager.persistAndFlush(JobLog.builder()
                .employee(employee)
                .job(job)
                .performedDate(performedDate)
                .build());
        WorkResult workResult = entityManager.persistAndFlush(
                WorkResult.builder()
                        .workshop(workshop)
                        .jobLog(jobLog)
                        .build());
        forceCreatedAt("work_result", workResult.getId(), workResultCreatedAt);
        return workResult;
    }

    private WorkResult transformationWorkResult(
            Workshop workshop,
            LocalDateTime itemCreatedAt,
            LocalDateTime workResultCreatedAt) {
        Inventory inventory = entityManager.persistAndFlush(Inventory.builder()
                .product(product("TRANSFORMADO", FamilyCategory.SPECIAL))
                .workshop(workshop)
                .price(BigDecimal.TEN)
                .cost(BigDecimal.ONE)
                .stock(1)
                .minStock(0)
                .build());
        InventoryItem item = entityManager.persistAndFlush(
                InventoryItem.builder()
                        .inventory(inventory)
                        .status(InventoryItemStatus.AVAILABLE)
                        .build());
        WorkResult workResult = entityManager.persistAndFlush(
                WorkResult.builder()
                        .workshop(workshop)
                        .item(item)
                        .build());
        forceCreatedAt("inventory_item", item.getId(), itemCreatedAt);
        forceCreatedAt("work_result", workResult.getId(), workResultCreatedAt);
        return workResult;
    }

    private void forceCreatedAt(String table, UUID id, LocalDateTime value) {
        entityManager.getEntityManager()
                .createNativeQuery(
                        "UPDATE " + table
                        + " SET created_at = :value WHERE id = :id")
                .setParameter("value", value)
                .setParameter("id", id)
                .executeUpdate();
    }

    private void addToOpenSale(Workshop workshop, WorkResult workResult) {
        Employee employee = entityManager.persistAndFlush(Employee.builder()
                .workshop(workshop)
                .firstName("CAJERO")
                .lastName("APELLIDO")
                .dpi(UUID.randomUUID().toString().substring(0, 13))
                .birthDate(LocalDate.of(1990, 1, 1))
                .build());
        Sale sale = entityManager.persistAndFlush(Sale.builder()
                .taxMode(TaxMode.UNRESOLVED)
                .workshop(workshop)
                .employee(employee)
                .build());
        entityManager.persistAndFlush(SaleDetail.builder()
                .sale(sale)
                .product(product("VENDIDO", FamilyCategory.NORMAL))
                .workResult(workResult)
                .quantity(1)
                .price(BigDecimal.TEN)
                .subTotal(BigDecimal.TEN)
                .build());
    }

    private List<WorkResultResponseDTO> availablePending(Workshop workshop) {
        entityManager.flush();
        entityManager.clear();
        return repository.findAvailableByWorkshopIdAndStatus(
                        workshop.getId(), WorkResultStatus.PENDING)
                .stream()
                .map(mapper::toResponse)
                .toList();
    }

    @Test
    void orderingUsesTheSourceDateAndNotTheWorkResultAuditDate() {
        Workshop workshop = workshop("TALLER A");
        WorkResult transformation = transformationWorkResult(
                workshop,
                LocalDateTime.of(2026, 8, 4, 12, 0),
                LocalDateTime.of(2026, 8, 4, 8, 0));
        WorkResult job = jobWorkResult(
                workshop,
                LocalDateTime.of(2026, 8, 4, 10, 0),
                LocalDateTime.of(2026, 8, 4, 11, 0));

        List<WorkResultResponseDTO> results = availablePending(workshop);

        assertThat(results).extracting(WorkResultResponseDTO::id)
                .containsExactly(transformation.getId(), job.getId());
        assertThat(results).extracting(WorkResultResponseDTO::performedDate)
                .containsExactly(
                        LocalDateTime.of(2026, 8, 4, 12, 0),
                        LocalDateTime.of(2026, 8, 4, 10, 0));
    }

    @Test
    void shownDateIsAlwaysTheDateUsedForOrdering() {
        Workshop workshop = workshop("TALLER A");
        transformationWorkResult(
                workshop,
                LocalDateTime.of(2026, 8, 4, 9, 0),
                LocalDateTime.of(2026, 8, 4, 23, 0));
        jobWorkResult(
                workshop,
                LocalDateTime.of(2026, 8, 4, 15, 0),
                LocalDateTime.of(2026, 8, 1, 1, 0));
        transformationWorkResult(
                workshop,
                LocalDateTime.of(2026, 8, 4, 20, 0),
                LocalDateTime.of(2026, 8, 2, 2, 0));
        jobWorkResult(
                workshop,
                LocalDateTime.of(2026, 8, 4, 7, 0),
                LocalDateTime.of(2026, 8, 4, 22, 0));

        List<WorkResultResponseDTO> results = availablePending(workshop);

        assertThat(results).extracting(WorkResultResponseDTO::performedDate)
                .containsExactly(
                        LocalDateTime.of(2026, 8, 4, 20, 0),
                        LocalDateTime.of(2026, 8, 4, 15, 0),
                        LocalDateTime.of(2026, 8, 4, 9, 0),
                        LocalDateTime.of(2026, 8, 4, 7, 0))
                .isSortedAccordingTo(reverseOrder());
        assertThat(results).extracting(WorkResultResponseDTO::source)
                .containsExactly(
                        WorkResultSource.TRANSFORMATION,
                        WorkResultSource.JOB,
                        WorkResultSource.TRANSFORMATION,
                        WorkResultSource.JOB);
    }

    @Test
    void bothSourcesAppearOnceEachInTheSameQuery() {
        Workshop workshop = workshop("TALLER A");
        WorkResult job = jobWorkResult(
                workshop,
                LocalDateTime.of(2026, 8, 4, 10, 0),
                LocalDateTime.of(2026, 8, 4, 10, 0));
        WorkResult transformation = transformationWorkResult(
                workshop,
                LocalDateTime.of(2026, 8, 4, 9, 0),
                LocalDateTime.of(2026, 8, 4, 9, 0));

        List<WorkResultResponseDTO> results = availablePending(workshop);

        assertThat(results).extracting(WorkResultResponseDTO::id)
                .containsExactly(job.getId(), transformation.getId())
                .doesNotHaveDuplicates();
    }

    @Test
    void pendingRecordsAlreadyAddedToAnOpenSaleAreExcluded() {
        Workshop workshop = workshop("TALLER A");
        WorkResult available = jobWorkResult(
                workshop,
                LocalDateTime.of(2026, 8, 4, 10, 0),
                LocalDateTime.of(2026, 8, 4, 10, 0));
        WorkResult reserved = transformationWorkResult(
                workshop,
                LocalDateTime.of(2026, 8, 4, 18, 0),
                LocalDateTime.of(2026, 8, 4, 18, 0));
        addToOpenSale(workshop, reserved);

        List<WorkResultResponseDTO> results = availablePending(workshop);

        assertThat(results).extracting(WorkResultResponseDTO::id)
                .containsExactly(available.getId());
    }

    @Test
    void recordsOfAnotherWorkshopAreNeverReturned() {
        Workshop workshop = workshop("TALLER A");
        Workshop otherWorkshop = workshop("TALLER B");
        WorkResult own = jobWorkResult(
                workshop,
                LocalDateTime.of(2026, 8, 4, 10, 0),
                LocalDateTime.of(2026, 8, 4, 10, 0));
        transformationWorkResult(
                otherWorkshop,
                LocalDateTime.of(2026, 8, 4, 23, 0),
                LocalDateTime.of(2026, 8, 4, 23, 0));

        List<WorkResultResponseDTO> results = availablePending(workshop);

        assertThat(results).extracting(WorkResultResponseDTO::id)
                .containsExactly(own.getId());
    }

}
