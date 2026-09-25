package com.cns.spring_house.job.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.cns.spring_house.family.entity.Family;
import com.cns.spring_house.family.entity.FamilyCategory;
import com.cns.spring_house.family.repository.FamilyRepository;
import com.cns.spring_house.job.entity.Job;
import com.cns.spring_house.product.entity.Product;
import com.cns.spring_house.product.repository.ProductRepository;
import com.cns.spring_house.testsupport.TestDatabases;
import com.cns.spring_house.workshop.entity.Workshop;
import com.cns.spring_house.workshop.repository.WorkshopRepository;

/**
 * La busqueda de servicios contra PostgreSQL de verdad.
 *
 * Un mock no habria servido: lo que fallaba era la comparacion que hace el
 * motor. La especificacion subia a MAYUSCULAS la columna y dejaba el parametro
 * como llegara, asi que `UPPER(product.name) LIKE '%soldadura%'` no casaba nunca
 * con "SOLDADURA ESPECIAL DE RESORTE". La caja lo tapaba mandando el texto ya en
 * mayusculas desde Angular; la bitacora, que manda lo que la persona escribio,
 * no encontraba nada.
 *
 * El nombre del servicio vive en `Job.product.name`, de modo que la busqueda
 * cruza a `product`: por eso se comprueba aqui y no en una prueba de `Job` a
 * secas.
 *
 * Usa una base temporal creada por la propia prueba; nunca la de desarrollo.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class JobSearchQueryTest {

    private static final String TEMPORARY_DATABASE = "job_search_it";

    @Autowired
    private JobRepository repository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private FamilyRepository familyRepository;

    @Autowired
    private WorkshopRepository workshopRepository;

    private Workshop workshop;

    private Family family;

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
        // suma de todos agota max_connections de PostgreSQL.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 2);
        registry.add("spring.datasource.hikari.minimum-idle", () -> 0);
    }

    @BeforeEach
    void seedWorkshop() {
        String code = UUID.randomUUID().toString().substring(0, 8);
        workshop = workshopRepository.save(Workshop.builder()
                .name("TALLER " + code)
                .address("DIRECCION")
                .build());
        family = familyRepository.save(Family.builder()
                .code(code)
                .name("SERVICIOS " + code)
                .requiresModel(false)
                .category(FamilyCategory.JOB)
                .build());
    }

    private Job job(String name) {
        Product product = productRepository.save(Product.builder()
                .family(family)
                .name(name)
                .sku("SKU-" + UUID.randomUUID().toString().substring(0, 8))
                .attributes(new HashMap<>())
                .build());
        return repository.save(Job.builder()
                .product(product)
                .workshop(workshop)
                .price(new BigDecimal("150.00"))
                .build());
    }

    /** Lo mismo que hace `JobServiceImpl.getAll`, acotado al taller. */
    private List<String> search(String query) {
        return repository.findAll(Specification.allOf(
                        JobSpecs.nameContains(query),
                        JobSpecs.hasWorkshop(workshop.getId())))
                .stream()
                .map(job -> job.getProduct().getName())
                .toList();
    }

    // ── Mayusculas y minusculas ──────────────────────────────────

    @Test
    void theSameServiceIsFoundInAnyCase() {
        job("SOLDADURA ESPECIAL DE RESORTE");

        assertThat(search("soldadura")).containsExactly("SOLDADURA ESPECIAL DE RESORTE");
        assertThat(search("SOLDADURA")).containsExactly("SOLDADURA ESPECIAL DE RESORTE");
        assertThat(search("SoLdAdUrA")).containsExactly("SOLDADURA ESPECIAL DE RESORTE");
        assertThat(search("Soldadura")).containsExactly("SOLDADURA ESPECIAL DE RESORTE");
    }

    @Test
    void aFragmentMatchesAnywhereInTheServiceName() {
        job("SOLDADURA ESPECIAL DE RESORTE");

        assertThat(search("especial")).containsExactly("SOLDADURA ESPECIAL DE RESORTE");
        assertThat(search("ESPECIAL DE RESORTE")).containsExactly("SOLDADURA ESPECIAL DE RESORTE");
        assertThat(search("de resorte")).containsExactly("SOLDADURA ESPECIAL DE RESORTE");
    }

    @Test
    void aQueryThatMatchesNothingReturnsNothing() {
        job("SOLDADURA ESPECIAL DE RESORTE");

        assertThat(search("alineacion")).isEmpty();
        assertThat(search("ALINEACION")).isEmpty();
    }

    /** El alcance por taller no se toca: sigue siendo del token. */
    @Test
    void aServiceFromAnotherWorkshopIsNeverReturned() {
        job("SOLDADURA ESPECIAL DE RESORTE");
        Workshop other = workshopRepository.save(Workshop.builder()
                .name("OTRO TALLER")
                .address("DIRECCION")
                .build());
        Product foreignProduct = productRepository.save(Product.builder()
                .family(family)
                .name("SOLDADURA DE OTRO TALLER")
                .sku("SKU-" + UUID.randomUUID().toString().substring(0, 8))
                .attributes(new HashMap<>())
                .build());
        repository.save(Job.builder()
                .product(foreignProduct)
                .workshop(other)
                .price(new BigDecimal("150.00"))
                .build());

        assertThat(search("soldadura")).containsExactly("SOLDADURA ESPECIAL DE RESORTE");
    }

    // ── Comodines literales ──────────────────────────────────────

    @Test
    void aPercentSignIsSearchedLiterally() {
        job("RECARGO 50% URGENTE");
        job("SOLDADURA ESPECIAL DE RESORTE");

        assertThat(search("50%")).containsExactly("RECARGO 50% URGENTE");
        assertThat(search("%")).containsExactly("RECARGO 50% URGENTE");
    }

    @Test
    void anUnderscoreIsSearchedLiterally() {
        job("SERVICIO_2026");
        job("SERVICIOX2026");

        assertThat(search("servicio_2026")).containsExactly("SERVICIO_2026");
    }

    @Test
    void aBackslashIsSearchedLiterally() {
        job("AJUSTE 1\\2 PULGADA");
        job("SOLDADURA ESPECIAL DE RESORTE");

        assertThat(search("1\\2")).containsExactly("AJUSTE 1\\2 PULGADA");
    }

}
