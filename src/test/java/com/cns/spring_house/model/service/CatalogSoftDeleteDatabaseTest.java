package com.cns.spring_house.model.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.cns.spring_house.brand.dto.BrandResponseDTO;
import com.cns.spring_house.brand.dto.CreateBrandRequestDTO;
import com.cns.spring_house.brand.dto.SearchBrandRequestDTO;
import com.cns.spring_house.brand.dto.UpdateBrandRequestDTO;
import com.cns.spring_house.brand.entity.Brand;
import com.cns.spring_house.brand.repository.BrandRepository;
import com.cns.spring_house.brand.service.BrandService;
import com.cns.spring_house.family.entity.Family;
import com.cns.spring_house.family.entity.FamilyCategory;
import com.cns.spring_house.family.repository.FamilyRepository;
import com.cns.spring_house.model.dto.CreateModelRequestDTO;
import com.cns.spring_house.model.dto.ModelResponseDTO;
import com.cns.spring_house.model.dto.SearchModelRequestDTO;
import com.cns.spring_house.model.dto.UpdateModelRequestDTO;
import com.cns.spring_house.product.dto.CreateProductRequestDTO;
import com.cns.spring_house.product.dto.ProductResponseDTO;
import com.cns.spring_house.product.dto.SearchProductRequestDTO;
import com.cns.spring_house.product.dto.UpdateProductRequestDTO;
import com.cns.spring_house.product.service.ProductService;
import com.cns.spring_house.s3.service.S3Service;
import com.cns.spring_house.shared.exception.EntityAlreadyExistsException;
import com.cns.spring_house.shared.exception.EntityNotFoundException;
import com.cns.spring_house.shared.exception.ValidationException;
import com.cns.spring_house.testsupport.TestDatabases;

/**
 * Eliminacion LOGICA de marcas y modelos contra PostgreSQL, con los servicios
 * reales y el esquema que deja V44.
 *
 * Lo que se comprueba es el contrato completo: la fila sobrevive, el historial
 * sigue leyendose, nada operativo la ofrece, ninguna ruta de escritura la
 * vuelve a asignar ni a revivir, y una eliminacion simultanea con una
 * asignacion no deja recien asignado algo eliminado.
 *
 * Usa una base temporal creada por la propia prueba; nunca la de desarrollo.
 * S3 se sustituye: el alta de una marca sube su logo.
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class CatalogSoftDeleteDatabaseTest {

    private static final String TEMPORARY_DATABASE = "catalog_soft_delete_it";

    @Autowired private BrandService brandService;
    @Autowired private ModelService modelService;
    @Autowired private ProductService productService;
    @Autowired private BrandRepository brandRepository;
    @Autowired private FamilyRepository familyRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    @MockitoBean private S3Service s3Service;

    private final ExecutorService pool = Executors.newFixedThreadPool(2);

    @DynamicPropertySource
    static void temporaryDatabase(DynamicPropertyRegistry registry) {
        String url = TestDatabases.provision(TEMPORARY_DATABASE);

        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", TestDatabases::username);
        registry.add("spring.datasource.password", TestDatabases::password);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        // Las pruebas de carrera necesitan dos transacciones a la vez.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 4);
        registry.add("spring.datasource.hikari.minimum-idle", () -> 0);
    }

    @AfterEach
    void stopPool() {
        pool.shutdownNow();
    }

    // ── Eliminar conserva la fila y el historial ──────────────────

    @Test
    void deletingABrandKeepsItsRowItsModelsAndTheirProducts() {
        Brand brand = brand("TOYOTA");
        ModelResponseDTO hilux = model(brand, "HILUX");
        ProductResponseDTO product = product(hilux);
        Map<String, Long> before = rowCounts();

        brandService.delete(brand.getId());

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT name, logo_key, deleted_at FROM brand WHERE id = ?", brand.getId());
        assertThat(row.get("name")).isEqualTo(brand.getName());
        assertThat(row.get("logo_key")).isEqualTo(brand.getLogoKey());
        assertThat(row.get("deleted_at")).isNotNull();
        // El modelo conserva SU estado: no se elimino, solo deja de ser elegible.
        assertThat(jdbc.queryForObject(
                "SELECT deleted_at FROM model WHERE id = ?", LocalDateTime.class, hilux.id())).isNull();
        assertThat(jdbc.queryForObject(
                "SELECT model_id FROM product WHERE id = ?", UUID.class, product.id())).isEqualTo(hilux.id());
        assertThat(rowCounts()).isEqualTo(before);

        // El historial se sigue leyendo con sus nombres, marcado como eliminado.
        ProductResponseDTO historical = productService.get(product.id());
        assertThat(historical.model().name()).isEqualTo("HILUX");
        assertThat(historical.model().deleted()).isFalse();
        assertThat(historical.model().brand().name()).isEqualTo(brand.getName());
        assertThat(historical.model().brand().deleted()).isTrue();
    }

    @Test
    void deletingAModelKeepsItsRowAndItsProducts() {
        Brand brand = brand("ISUZU");
        ModelResponseDTO dmax = model(brand, "D-MAX");
        ProductResponseDTO product = product(dmax);
        Map<String, Long> before = rowCounts();

        modelService.delete(dmax.id());

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT name, brand_id, year_from, year_to, deleted_at FROM model WHERE id = ?", dmax.id());
        assertThat(row.get("name")).isEqualTo("D-MAX");
        assertThat(row.get("brand_id")).isEqualTo(brand.getId());
        assertThat(row.get("deleted_at")).isNotNull();
        assertThat(rowCounts()).isEqualTo(before);

        ProductResponseDTO historical = productService.get(product.id());
        assertThat(historical.model().id()).isEqualTo(dmax.id());
        assertThat(historical.model().name()).isEqualTo("D-MAX");
        assertThat(historical.model().deleted()).isTrue();
        assertThat(historical.model().brand().deleted()).isFalse();
    }

    // ── Listados, busquedas y detalle: solo lo vigente ───────────

    @Test
    void activeBrandListsSearchesAndDetailExcludeADeletedBrand() {
        String tag = unique();
        Brand kept = brand("MAZDA " + tag);
        Brand gone = brand("DAEWOO " + tag);

        brandService.delete(gone.getId());

        assertThat(ids(brandService.getAll(new SearchBrandRequestDTO(tag))))
                .containsExactly(kept.getId());
        assertThat(ids(brandService.getAll(new SearchBrandRequestDTO(null))))
                .contains(kept.getId())
                .doesNotContain(gone.getId());
        assertThatThrownBy(() -> brandService.get(gone.getId()))
                .isInstanceOf(EntityNotFoundException.class);
        assertThat(brandService.get(kept.getId()).deleted()).isFalse();
    }

    @Test
    void deletingOneModelHidesOnlyThatModelAndKeepsItsBrandAndSiblings() {
        Brand brand = brand("NISSAN");
        ModelResponseDTO frontier = model(brand, "FRONTIER");
        ModelResponseDTO navara = model(brand, "NAVARA");

        modelService.delete(frontier.id());

        assertThat(modelIds(new SearchModelRequestDTO(brand.getId(), null, null)))
                .containsExactly(navara.id());
        assertThat(modelIds(new SearchModelRequestDTO(null, "FRONTIER", null)))
                .doesNotContain(frontier.id());
        assertThat(modelIds(new SearchModelRequestDTO(null, null, 2021)))
                .contains(navara.id())
                .doesNotContain(frontier.id());
        assertThatThrownBy(() -> modelService.get(frontier.id()))
                .isInstanceOf(EntityNotFoundException.class);
        assertThat(modelService.get(navara.id()).name()).isEqualTo("NAVARA");
        assertThat(ids(brandService.getAll(new SearchBrandRequestDTO(brand.getName()))))
                .containsExactly(brand.getId());
    }

    @Test
    void theModelsOfADeletedBrandAreNoLongerSelectable() {
        Brand brand = brand("MITSUBISHI");
        ModelResponseDTO l200 = model(brand, "L200");

        brandService.delete(brand.getId());

        assertThat(modelIds(new SearchModelRequestDTO(brand.getId(), null, null))).isEmpty();
        assertThat(modelIds(new SearchModelRequestDTO(null, "L200", null))).doesNotContain(l200.id());
        assertThatThrownBy(() -> modelService.get(l200.id()))
                .isInstanceOf(EntityNotFoundException.class);
        // Ni un modelo nuevo bajo la marca eliminada.
        assertThatThrownBy(() -> modelService.create(
                new CreateModelRequestDTO(brand.getId(), "MONTERO", 2020, 2022)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("marca seleccionada fue eliminada");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM model WHERE brand_id = ?", Long.class, brand.getId())).isEqualTo(1);
    }

    // ── Nada nuevo se asigna a lo eliminado ──────────────────────

    @Test
    void aNewProductCannotUseADeletedModelNorAModelOfADeletedBrand() {
        Brand brand = brand("HYUNDAI");
        ModelResponseDTO h100 = model(brand, "H100");
        Brand otherBrand = brand("KIA");
        ModelResponseDTO k2700 = model(otherBrand, "K2700");
        ModelResponseDTO frontera = model(brand("OPEL"), "FRONTERA");
        ProductResponseDTO onAnActiveModel = product(frontera);
        modelService.delete(h100.id());
        brandService.delete(otherBrand.getId());
        long products = count("product");

        for (ModelResponseDTO unavailable : List.of(h100, k2700)) {
            assertThatThrownBy(() -> productService.create(creation(unavailable.id())))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("modelo seleccionado fue eliminado")
                    .extracting(error -> ((ValidationException) error).getErrors())
                    .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                    .containsKey("model");
            // Tampoco moviendo a el un producto existente.
            assertThatThrownBy(() -> productService.update(onAnActiveModel.id(),
                    new UpdateProductRequestDTO(null, null, null, unavailable.id(), null)))
                    .isInstanceOf(ValidationException.class);
        }

        assertThat(count("product")).isEqualTo(products);
        assertThat(productService.get(onAnActiveModel.id()).model().id()).isEqualTo(frontera.id());
    }

    /**
     * Editar un producto que ya usa un modelo eliminado no lo rompe: el modelo
     * se conserva —ni se vacia ni se sustituye— y el resto de sus datos tambien.
     * Solo cambia si se elige OTRO modelo, y vigente.
     */
    @Test
    void editingAProductThatAlreadyUsesADeletedModelKeepsItIntact() {
        Brand brand = brand("FORD");
        ModelResponseDTO ranger = model(brand, "RANGER");
        ModelResponseDTO f150 = model(brand, "F150");
        ProductResponseDTO product = product(ranger);
        modelService.delete(ranger.id());

        ProductResponseDTO renamed = productService.update(product.id(),
                new UpdateProductRequestDTO(null, "RENOMBRADO " + unique(), null, null, null));
        assertThat(renamed.model().id()).isEqualTo(ranger.id());
        assertThat(renamed.model().deleted()).isTrue();
        assertThat(renamed.sku()).isEqualTo(product.sku());
        assertThat(renamed.family().id()).isEqualTo(product.family().id());

        // Reenviar el MISMO modelo no es una eleccion nueva.
        ProductResponseDTO same = productService.update(product.id(),
                new UpdateProductRequestDTO(null, null, null, ranger.id(), null));
        assertThat(same.model().id()).isEqualTo(ranger.id());

        ProductResponseDTO moved = productService.update(product.id(),
                new UpdateProductRequestDTO(null, null, null, f150.id(), null));
        assertThat(moved.model().id()).isEqualTo(f150.id());
    }

    /**
     * Eliminar una OPCION del catalogo no elimina la pieza: el producto de un
     * modelo eliminado —o de un modelo cuya marca se elimino— sigue saliendo en
     * la busqueda por texto de la caja (`GET /v1/products?q=`, nombre o sku) y
     * por familia, con sus nombres legibles. Lo que deja de existir es elegir
     * ese modelo o esa marca para algo NUEVO.
     */
    @Test
    void aProductWhoseModelOrBrandWasDeletedIsStillFoundByTextAndFamilySearch() {
        ModelResponseDTO deletedModel = model(brand("KIA"), "SPORTAGE");
        Brand deletedBrand = brand("HYUNDAI");
        ModelResponseDTO orphanModel = model(deletedBrand, "H1");
        ProductResponseDTO onDeletedModel = product(deletedModel);
        ProductResponseDTO onDeletedBrand = product(orphanModel);
        modelService.delete(deletedModel.id());
        brandService.delete(deletedBrand.getId());

        for (ProductResponseDTO legacy : List.of(onDeletedModel, onDeletedBrand)) {
            assertThat(searchIds(legacy.name(), null)).as("por nombre").contains(legacy.id());
            assertThat(searchIds(legacy.sku(), null)).as("por sku").contains(legacy.id());
            assertThat(searchIds(null, legacy.family().id())).as("por familia").contains(legacy.id());
        }
        ProductResponseDTO found = productService.getAll(search(onDeletedBrand.name(), null)).getFirst();
        assertThat(found.model().name()).isEqualTo("H1");
        assertThat(found.model().brand().name()).isEqualTo(deletedBrand.getName());
        assertThat(found.model().brand().deleted()).isTrue();

        // Y aun asi ninguno se ofrece para elegir.
        assertThat(modelIds(new SearchModelRequestDTO(null, null, null)))
                .doesNotContain(deletedModel.id(), orphanModel.id());
        assertThat(ids(brandService.getAll(new SearchBrandRequestDTO(null)))).doesNotContain(deletedBrand.getId());
    }

    // ── Repetir, reusar nombres, revivir ─────────────────────────

    @Test
    void deletingTwiceIsAcceptedAndKeepsTheOriginalDate() {
        Brand brand = brand("CHEVROLET");
        ModelResponseDTO luv = model(brand("GMC"), "LUV");

        brandService.delete(brand.getId());
        modelService.delete(luv.id());
        LocalDateTime brandDeletedAt = deletedAt("brand", brand.getId());
        LocalDateTime modelDeletedAt = deletedAt("model", luv.id());

        brandService.delete(brand.getId());
        modelService.delete(luv.id());

        assertThat(deletedAt("brand", brand.getId())).isEqualTo(brandDeletedAt);
        assertThat(deletedAt("model", luv.id())).isEqualTo(modelDeletedAt);
        assertThatThrownBy(() -> brandService.delete(UUID.randomUUID()))
                .isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> modelService.delete(UUID.randomUUID()))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void aDeletedNameIsNeverReusedNorRevivedByCreatingOrRenaming() {
        Brand gone = brand("VOLVO");
        String name = gone.getName();
        Brand other = brand("SCANIA");
        brandService.delete(gone.getId());

        assertThatThrownBy(() -> brandService.create(new CreateBrandRequestDTO(name, logo())))
                .isInstanceOf(EntityAlreadyExistsException.class)
                .hasMessageContaining("fue eliminada");
        assertThatThrownBy(() -> brandService.update(other.getId(), new UpdateBrandRequestDTO(name, null)))
                .isInstanceOf(EntityAlreadyExistsException.class)
                .hasMessageContaining("fue eliminada");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM brand WHERE name = ?", Long.class, name))
                .isEqualTo(1);
        assertThat(deletedAt("brand", gone.getId())).isNotNull();

        Brand brand = brand("RENAULT");
        ModelResponseDTO kangoo = model(brand, "KANGOO");
        modelService.delete(kangoo.id());
        assertThatThrownBy(() -> modelService.create(
                new CreateModelRequestDTO(brand.getId(), "KANGOO", 2020, 2022)))
                .isInstanceOf(EntityAlreadyExistsException.class)
                .hasMessageContaining("fue eliminado");
        assertThat(deletedAt("model", kangoo.id())).isNotNull();
    }

    /**
     * Ninguna escritura ordinaria devuelve a la vida lo eliminado: editar una
     * marca o un modelo eliminado responde como inexistente y la fila no cambia,
     * y un modelo vigente no se puede mover a una marca eliminada.
     */
    @Test
    void noOrdinaryWriteBringsADeletedBrandOrModelBack() {
        Brand gone = brand("DODGE");
        brandService.delete(gone.getId());
        LocalDateTime goneAt = deletedAt("brand", gone.getId());

        assertThatThrownBy(() -> brandService.update(gone.getId(), new UpdateBrandRequestDTO("DODGE RAM " + unique(), null)))
                .isInstanceOf(EntityNotFoundException.class);
        assertThat(deletedAt("brand", gone.getId())).isEqualTo(goneAt);

        Brand brand = brand("JEEP");
        ModelResponseDTO cherokee = model(brand, "CHEROKEE");
        ModelResponseDTO wrangler = model(brand, "WRANGLER");
        modelService.delete(cherokee.id());
        assertThatThrownBy(() -> modelService.update(cherokee.id(),
                new UpdateModelRequestDTO(null, "CHEROKEE II", null, null)))
                .isInstanceOf(EntityNotFoundException.class);
        assertThat(deletedAt("model", cherokee.id())).isNotNull();

        assertThatThrownBy(() -> modelService.update(wrangler.id(),
                new UpdateModelRequestDTO(gone.getId(), null, null, null)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("marca seleccionada fue eliminada");
        assertThat(jdbc.queryForObject(
                "SELECT brand_id FROM model WHERE id = ?", UUID.class, wrangler.id())).isEqualTo(brand.getId());

        // Un modelo cuya marca se elimino tampoco se edita por su cuenta.
        brandService.delete(brand.getId());
        assertThatThrownBy(() -> modelService.update(wrangler.id(),
                new UpdateModelRequestDTO(null, "WRANGLER II", null, null)))
                .isInstanceOf(EntityNotFoundException.class);
    }

    // ── Carreras: eliminar mientras se asigna ────────────────────

    /**
     * El alta del producto ya comprobo el modelo y tiene su cerrojo compartido:
     * la eliminacion espera a que el alta termine. El producto queda con un
     * modelo que estaba vigente al asignarse —historial legitimo— y desde ese
     * momento nadie mas puede elegirlo.
     */
    @Test
    void aDeletionWaitsForAnAssignmentAlreadyInProgress() throws Exception {
        ModelResponseDTO model = model(brand("SUZUKI"), "JIMNY");
        CountDownLatch assigned = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        Future<UUID> creation = pool.submit(() -> tx.execute(status -> {
            UUID id = productService.create(creation(model.id())).id();
            assigned.countDown();
            await(release);
            return id;
        }));
        assertThat(assigned.await(10, TimeUnit.SECONDS)).isTrue();
        Future<?> deletion = pool.submit(() -> modelService.delete(model.id()));

        Thread.sleep(400);
        assertThat(deletion.isDone()).as("la eliminacion espera al alta en curso").isFalse();
        release.countDown();

        UUID product = creation.get(10, TimeUnit.SECONDS);
        deletion.get(10, TimeUnit.SECONDS);
        assertThat(jdbc.queryForObject("SELECT model_id FROM product WHERE id = ?", UUID.class, product))
                .isEqualTo(model.id());
        assertThat(deletedAt("model", model.id())).isNotNull();
        assertThatThrownBy(() -> productService.create(creation(model.id())))
                .isInstanceOf(ValidationException.class);
    }

    /**
     * Al reves: la eliminacion de la MARCA ya tomo su fila; el alta que llega
     * despues espera y, al confirmarse la eliminacion, la ve y se rechaza. No
     * queda ningun producto recien asignado a un modelo de una marca eliminada.
     */
    @Test
    void anAssignmentThatRacesABrandDeletionSeesItAndIsRejected() throws Exception {
        Brand brand = brand("SUBARU");
        ModelResponseDTO model = model(brand, "FORESTER");
        CountDownLatch deleted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        long products = count("product");

        Future<?> deletion = pool.submit(() -> tx.executeWithoutResult(status -> {
            brandService.delete(brand.getId());
            deleted.countDown();
            await(release);
        }));
        assertThat(deleted.await(10, TimeUnit.SECONDS)).isTrue();
        Future<Throwable> creation = pool.submit(() -> {
            try {
                productService.create(creation(model.id()));
                return null;
            } catch (RuntimeException rejected) {
                return rejected;
            }
        });

        Thread.sleep(400);
        assertThat(creation.isDone()).as("el alta espera a la eliminacion en curso").isFalse();
        release.countDown();
        deletion.get(10, TimeUnit.SECONDS);

        assertThat(creation.get(10, TimeUnit.SECONDS))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("modelo seleccionado fue eliminado");
        assertThat(count("product")).isEqualTo(products);
    }

    // ── Carreras: editar mientras se elimina ─────────────────────

    /**
     * La eliminacion ya tomo la fila; el PATCH que llega despues ESPERA y, al
     * confirmarse la eliminacion, la encuentra eliminada y responde como
     * inexistente. Sin el FOR UPDATE de la edicion, el PATCH habria leido la
     * copia vigente y su UPDATE de columnas completas habria escrito
     * `deleted_at = NULL` encima: la marca habria revivido.
     */
    @Test
    void anEditThatRacesADeletionWaitsAndCannotBringItBack() throws Exception {
        Brand brand = brand("PEUGEOT");
        CountDownLatch deleted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        Future<?> deletion = pool.submit(() -> tx.executeWithoutResult(status -> {
            brandService.delete(brand.getId());
            deleted.countDown();
            await(release);
        }));
        assertThat(deleted.await(10, TimeUnit.SECONDS)).isTrue();
        Future<Throwable> edit = pool.submit(() -> {
            try {
                brandService.update(brand.getId(), new UpdateBrandRequestDTO("PEUGEOT II " + unique(), null));
                return null;
            } catch (RuntimeException rejected) {
                return rejected;
            }
        });

        Thread.sleep(400);
        assertThat(edit.isDone()).as("la edicion espera a la eliminacion en curso").isFalse();
        release.countDown();
        deletion.get(10, TimeUnit.SECONDS);

        assertThat(edit.get(10, TimeUnit.SECONDS)).isInstanceOf(EntityNotFoundException.class);
        Map<String, Object> row = jdbc.queryForMap("SELECT name, deleted_at FROM brand WHERE id = ?", brand.getId());
        assertThat(row.get("deleted_at")).isNotNull();
        assertThat(row.get("name")).isEqualTo(brand.getName());
    }

    /**
     * Al reves: la edicion del modelo ya tomo la fila; la eliminacion espera a
     * que termine y despues se aplica. La edicion queda guardada y el modelo
     * queda eliminado: ninguna de las dos pisa a la otra.
     */
    @Test
    void aDeletionThatRacesAnEditWaitsAndStillDeletes() throws Exception {
        ModelResponseDTO model = model(brand("CITROEN"), "BERLINGO");
        String renamed = "BERLINGO II " + unique();
        CountDownLatch edited = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        Future<?> edit = pool.submit(() -> tx.executeWithoutResult(status -> {
            modelService.update(model.id(), new UpdateModelRequestDTO(null, renamed, null, null));
            edited.countDown();
            await(release);
        }));
        assertThat(edited.await(10, TimeUnit.SECONDS)).isTrue();
        Future<?> deletion = pool.submit(() -> modelService.delete(model.id()));

        Thread.sleep(400);
        assertThat(deletion.isDone()).as("la eliminacion espera a la edicion en curso").isFalse();
        release.countDown();
        edit.get(10, TimeUnit.SECONDS);
        deletion.get(10, TimeUnit.SECONDS);

        Map<String, Object> row = jdbc.queryForMap("SELECT name, deleted_at FROM model WHERE id = ?", model.id());
        assertThat(row.get("name")).isEqualTo(renamed);
        assertThat(row.get("deleted_at")).isNotNull();
        assertThat(modelIds(new SearchModelRequestDTO(null, null, null))).doesNotContain(model.id());
    }

    // ── Esquema ──────────────────────────────────────────────────

    /**
     * V44 solo AÑADE columnas nulas: ninguna fila existente cambia de estado, y
     * las UNIQUE de siempre siguen cubriendo las filas eliminadas.
     */
    @Test
    void theMigrationAddsNullableColumnsAndKeepsTheUniquenessOfDeletedRows() {
        assertThat(jdbc.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = '44'", Boolean.class)).isTrue();
        assertThat(jdbc.queryForList("""
                SELECT table_name || '.' || is_nullable FROM information_schema.columns
                WHERE column_name = 'deleted_at' AND table_name IN ('brand', 'model')
                ORDER BY table_name""", String.class))
                .containsExactly("brand.YES", "model.YES");

        Brand gone = brand("LADA");
        brandService.delete(gone.getId());
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO brand (id, name, logo_key, created_at, updated_at) VALUES (?, ?, 'x', now(), now())",
                UUID.randomUUID(), gone.getName()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ── Datos de partida ─────────────────────────────────────────

    private Brand brand(String name) {
        return brandRepository.save(Brand.builder()
                .name(name.length() > 40 ? name : name + " " + unique())
                .logoKey("brands/" + unique() + ".png")
                .build());
    }

    private ModelResponseDTO model(Brand brand, String name) {
        return modelService.create(new CreateModelRequestDTO(brand.getId(), name, 2020, 2022));
    }

    private Family family() {
        return familyRepository.save(Family.builder()
                .code("SD" + unique())
                .name("FAMILIA " + unique())
                .requiresModel(true)
                .category(FamilyCategory.NORMAL)
                .attributes(new ArrayList<>())
                .build());
    }

    private CreateProductRequestDTO creation(UUID model) {
        return new CreateProductRequestDTO(
                "SKU-" + unique(), "PIEZA " + unique(), family().getId(), new HashMap<>(), model);
    }

    private ProductResponseDTO product(ModelResponseDTO model) {
        return productService.create(creation(model.id()));
    }

    private static MockMultipartFile logo() {
        return new MockMultipartFile("logo", "logo.png", "image/png", new byte[] {1, 2, 3});
    }

    private List<UUID> ids(List<BrandResponseDTO> brands) {
        return brands.stream().map(BrandResponseDTO::id).toList();
    }

    private List<UUID> modelIds(SearchModelRequestDTO query) {
        return modelService.getAll(query).stream().map(ModelResponseDTO::id).toList();
    }

    private SearchProductRequestDTO search(String q, UUID family) {
        return new SearchProductRequestDTO(q, family, null, null, new HashMap<>());
    }

    private List<UUID> searchIds(String q, UUID family) {
        return productService.getAll(search(q, family)).stream().map(ProductResponseDTO::id).toList();
    }

    private LocalDateTime deletedAt(String table, UUID id) {
        return jdbc.queryForObject(
                "SELECT deleted_at FROM " + table + " WHERE id = ?", LocalDateTime.class, id);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private Map<String, Long> rowCounts() {
        return Map.of("brand", count("brand"), "model", count("model"), "product", count("product"));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static String unique() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

}
