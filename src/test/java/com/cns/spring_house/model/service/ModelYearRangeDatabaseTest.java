package com.cns.spring_house.model.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.cns.spring_house.brand.entity.Brand;
import com.cns.spring_house.brand.repository.BrandRepository;
import com.cns.spring_house.model.dto.CreateModelRequestDTO;
import com.cns.spring_house.model.dto.ModelResponseDTO;
import com.cns.spring_house.model.dto.SearchModelRequestDTO;
import com.cns.spring_house.model.dto.UpdateModelRequestDTO;
import com.cns.spring_house.model.entity.Model;
import com.cns.spring_house.model.mapper.ModelMapperImpl;
import com.cns.spring_house.model.repository.ModelRepository;
import com.cns.spring_house.shared.exception.EntityAlreadyExistsException;
import com.cns.spring_house.shared.exception.ValidationException;
import com.cns.spring_house.testsupport.TestDatabases;

/**
 * El intervalo de años del modelo contra PostgreSQL de verdad: el servicio
 * real, el mapper real y el esquema que deja la migracion V35.
 *
 * Con mocks no se veria lo que aqui importa. El PATCH mueve UN extremo y el
 * otro se queda como estaba, asi que el intervalo que hay que validar es el
 * fusionado, no el que viajo en el cuerpo; y la busqueda por año no compara
 * igualdad sino cobertura, que es una consulta, no una rama de codigo.
 *
 * Usa una base temporal creada por la propia prueba; nunca la de desarrollo.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class ModelYearRangeDatabaseTest {

    private static final String TEMPORARY_DATABASE = "model_year_range_it";

    @Autowired
    private ModelRepository repository;

    @Autowired
    private BrandRepository brandRepository;

    @Autowired
    private TestEntityManager entityManager;

    private ModelServiceImpl service;
    private Brand brand;

    @DynamicPropertySource
    static void temporaryDatabase(DynamicPropertyRegistry registry) {
        String url = TestDatabases.provision(TEMPORARY_DATABASE);

        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", TestDatabases::username);
        registry.add("spring.datasource.password", TestDatabases::password);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        // Cada clase con @DynamicPropertySource levanta su propio contexto y el
        // TestContext los CACHEA hasta que acaba la JVM: sin acotar el pool, la
        // suma de todos agota max_connections de PostgreSQL.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 2);
        registry.add("spring.datasource.hikari.minimum-idle", () -> 0);
    }

    @BeforeEach
    void setUp() {
        service = new ModelServiceImpl(
                new ModelMapperImpl(), repository, brandRepository);
        brand = entityManager.persistAndFlush(Brand.builder()
                .name("MARCA " + unique())
                .logoKey("logo.png")
                .build());
    }

    private static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private CreateModelRequestDTO creation(String name, int from, int to) {
        return new CreateModelRequestDTO(brand.getId(), name, from, to);
    }

    // ── Alta ─────────────────────────────────────────────────────

    /** "2020" no es un caso especial: es el intervalo [2020, 2020]. */
    @Test
    void createsASingleYearModelAsAClosedRange() {
        ModelResponseDTO response = service.create(creation("Corolla", 2020, 2020));

        assertThat(response.yearFrom()).isEqualTo(2020);
        assertThat(response.yearTo()).isEqualTo(2020);

        Model stored = repository.findById(response.id()).orElseThrow();
        assertThat(stored.getYearFrom()).isEqualTo(2020);
        assertThat(stored.getYearTo()).isEqualTo(2020);
    }

    @Test
    void createsAMultiYearModel() {
        ModelResponseDTO response = service.create(creation("Hilux", 2020, 2025));

        assertThat(response.yearFrom()).isEqualTo(2020);
        assertThat(response.yearTo()).isEqualTo(2025);

        Model stored = repository.findById(response.id()).orElseThrow();
        assertThat(stored.getYearFrom()).isEqualTo(2020);
        assertThat(stored.getYearTo()).isEqualTo(2025);
    }

    /**
     * El error apunta a `yearTo` para que el formulario lo pinte sobre el campo
     * que el usuario puede corregir sin cambiar lo que quiso decir.
     */
    @Test
    void rejectsARangeInTheWrongOrder() {
        assertThatThrownBy(() -> service.create(creation("Invertido", 2025, 2020)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("El año final no puede ser menor que el año inicial")
                .extracting(error -> ((ValidationException) error).getErrors())
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsKey("yearTo");

        assertThat(repository.count()).isZero();
    }

    // ── Edicion ──────────────────────────────────────────────────

    /** 2020 → 2020-2025 mandando solo el extremo que cambia. */
    @Test
    void widensASingleYearIntoARange() {
        UUID id = service.create(creation("Corolla", 2020, 2020)).id();

        ModelResponseDTO updated = service.update(
                id, new UpdateModelRequestDTO(null, null, null, 2025));

        assertThat(updated.yearFrom()).isEqualTo(2020);
        assertThat(updated.yearTo()).isEqualTo(2025);
    }

    /** 2020-2025 → 2022-2024. */
    @Test
    void movesBothEndsOfARange() {
        UUID id = service.create(creation("Corolla", 2020, 2025)).id();

        ModelResponseDTO updated = service.update(
                id, new UpdateModelRequestDTO(null, null, 2022, 2024));

        assertThat(updated.yearFrom()).isEqualTo(2022);
        assertThat(updated.yearTo()).isEqualTo(2024);
    }

    /** 2020-2025 → 2020: el modelo vuelve a valer para un solo año. */
    @Test
    void narrowsARangeIntoASingleYear() {
        UUID id = service.create(creation("Corolla", 2020, 2025)).id();

        ModelResponseDTO updated = service.update(
                id, new UpdateModelRequestDTO(null, null, 2020, 2020));

        assertThat(updated.yearFrom()).isEqualTo(2020);
        assertThat(updated.yearTo()).isEqualTo(2020);
    }

    /**
     * El valor enviado no tiene nada de raro por si solo —2019 es un año
     * perfectamente valido—: lo que lo invalida es el otro extremo, que sigue
     * guardado en la fila.
     */
    @Test
    void rejectsAPatchThatInvertsTheStoredRange() {
        UUID id = service.create(creation("Corolla", 2020, 2025)).id();

        assertThatThrownBy(() -> service.update(
                id, new UpdateModelRequestDTO(null, null, null, 2019)))
                .isInstanceOf(ValidationException.class);

        Model stored = repository.findById(id).orElseThrow();
        assertThat(stored.getYearFrom()).isEqualTo(2020);
        assertThat(stored.getYearTo()).isEqualTo(2025);
    }

    /** Cambiar el intervalo no toca la marca de creacion de la fila. */
    @Test
    void leavesTheAuditTimestampsAlone() {
        UUID id = service.create(creation("Corolla", 2020, 2020)).id();
        entityManager.flush();
        LocalDateTime createdAt = repository.findById(id).orElseThrow().getCreatedAt();

        service.update(id, new UpdateModelRequestDTO(null, null, null, 2025));
        entityManager.flush();

        assertThat(repository.findById(id).orElseThrow().getCreatedAt())
                .isEqualTo(createdAt);
    }

    // ── Busqueda ─────────────────────────────────────────────────

    /**
     * El filtro pregunta por un año suelto y responde con los modelos que lo
     * CUBREN: el de 2020-2025 aparece en 2023 aunque no empiece ahi.
     */
    @Test
    void findsEveryModelWhoseRangeCoversTheRequestedYear() {
        service.create(creation("Corolla", 2020, 2025));
        service.create(creation("Mazda 3", 2023, 2023));
        service.create(creation("Civic", 2018, 2019));

        List<String> covering2023 = namesOf(service.getAll(
                new SearchModelRequestDTO(brand.getId(), null, 2023)));
        assertThat(covering2023).containsExactlyInAnyOrder("Corolla", "Mazda 3");

        List<String> covering2019 = namesOf(service.getAll(
                new SearchModelRequestDTO(brand.getId(), null, 2019)));
        assertThat(covering2019).containsExactly("Civic");

        assertThat(service.getAll(
                new SearchModelRequestDTO(brand.getId(), null, 1999))).isEmpty();
    }

    /** Sin año preguntado el filtro no filtra: el catalogo entero. */
    @Test
    void listsEveryModelWhenNoYearIsRequested() {
        service.create(creation("Corolla", 2020, 2025));
        service.create(creation("Mazda 3", 2023, 2023));

        assertThat(service.getAll(
                new SearchModelRequestDTO(brand.getId(), null, null)))
                .hasSize(2);
    }

    // ── Unicidad ─────────────────────────────────────────────────

    /**
     * La unicidad incluye el intervalo, igual que antes incluia el año: dos
     * generaciones del mismo nombre son dos modelos distintos, pero la misma
     * generacion dos veces es un duplicado.
     */
    @Test
    void rejectsTheSameNameAndRangeTwiceButAllowsAnotherRange() {
        service.create(creation("Corolla", 2020, 2025));

        assertThatThrownBy(() -> service.create(creation("Corolla", 2020, 2025)))
                .isInstanceOf(EntityAlreadyExistsException.class);

        service.create(creation("Corolla", 2026, 2026));

        assertThat(repository.count()).isEqualTo(2);
    }

    private static List<String> namesOf(List<ModelResponseDTO> models) {
        return models.stream().map(ModelResponseDTO::name).toList();
    }

}
