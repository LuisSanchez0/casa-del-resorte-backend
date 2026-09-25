package com.cns.spring_house.transformation.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.cns.spring_house.acquisition.entity.AcquisitionOrigin;
import com.cns.spring_house.acquisition.entity.InventoryAcquisition;
import com.cns.spring_house.acquisition.repository.InventoryAcquisitionRepository;
import com.cns.spring_house.acquisition.repository.SaleDetailCostAllocationRepository;
import com.cns.spring_house.acquisition.service.InventoryLotService;
import com.cns.spring_house.brand.entity.Brand;
import com.cns.spring_house.family.entity.Attribute;
import com.cns.spring_house.family.entity.AttributeDataType;
import com.cns.spring_house.family.entity.Family;
import com.cns.spring_house.family.repository.FamilyRepository;
import com.cns.spring_house.family.service.HoleMeasurementValidator;
import com.cns.spring_house.inventory.entity.Inventory;
import com.cns.spring_house.inventory.entity.InventoryItem;
import com.cns.spring_house.inventory.entity.InventoryItemStatus;
import com.cns.spring_house.inventory.repository.InventoryItemRepository;
import com.cns.spring_house.inventory.repository.InventoryRepository;
import com.cns.spring_house.model.entity.Model;
import com.cns.spring_house.product.entity.Product;
import com.cns.spring_house.product.repository.ProductRepository;
import com.cns.spring_house.shared.barcode.BarcodeIdentity;
import com.cns.spring_house.shared.exception.ValidationException;
import com.cns.spring_house.testsupport.TestDatabases;
import com.cns.spring_house.transformation.dto.CreateTransformationRequestDTO;
import com.cns.spring_house.transformation.entity.Transformation;
import com.cns.spring_house.transformation.entity.TransformationItem;
import com.cns.spring_house.transformation.entity.TransformationItemRole;
import com.cns.spring_house.workshop.entity.Workshop;
import com.cns.spring_house.workshop.repository.WorkshopRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * MATERIAL LISO -> HOJA DE RESORTE contra PostgreSQL de verdad, sobre las
 * familias que SIEMBRA LA MIGRACION V6, no sobre familias inventadas por la
 * prueba.
 *
 * Es lo que con mocks no se puede ver:
 *
 * <ul>
 *   <li>que la regla reconozca las filas reales de `family` tal y como el
 *       esquema las crea;</li>
 *   <li>que el producto de destino se REUTILICE en vez de duplicarse, que es
 *       una decision de `findTargetProduct`, una consulta nativa;</li>
 *   <li>que el sobrante de un material liso SIN MODELO se reutilice entre
 *       transformaciones, que depende de `model_id IS NOT DISTINCT FROM :modelId`
 *       y con `= NULL` daria siempre falso y duplicaria el catalogo;</li>
 *   <li>que `createdInventoryIds` diga la verdad sobre que inventario nacio de
 *       verdad en cada llamada.</li>
 * </ul>
 *
 * Usa una base temporal creada por la propia prueba; nunca la de desarrollo.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class SpecialTransformationCrossFamilyDatabaseTest {

    private static final String TEMPORARY_DATABASE = "special_transformation_cross_family_it";
    private static final BigDecimal SOURCE_COST = new BigDecimal("125.50");

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private InventoryItemRepository itemRepository;

    @Autowired
    private WorkshopRepository workshopRepository;

    @Autowired
    private FamilyRepository familyRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private InventoryAcquisitionRepository acquisitionRepository;

    @Autowired
    private SaleDetailCostAllocationRepository allocationRepository;

    private SpecialTransformationHandler handler;

    private Workshop workshop;
    private Family materialLiso;
    private Family hojaResorte;
    private Family maleta;
    private Model model;
    private Inventory inputInventory;

    @DynamicPropertySource
    static void temporaryDatabase(DynamicPropertyRegistry registry) {
        String url = TestDatabases.provision(TEMPORARY_DATABASE);

        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", TestDatabases::username);
        registry.add("spring.datasource.password", TestDatabases::password);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        // Mismo tope que el resto de pruebas de base de datos del proyecto: sin
        // el, cada contexto cacheado se queda sus 10 conexiones de Hikari y
        // entre todos agotan max_connections.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 2);
        registry.add("spring.datasource.hikari.minimum-idle", () -> 0);
    }

    @BeforeEach
    void setUp() {
        handler = new SpecialTransformationHandler(
                productRepository,
                inventoryRepository,
                itemRepository,
                workshopRepository,
                new ObjectMapper(),
                new InventoryLotService(
                        acquisitionRepository,
                        allocationRepository,
                        Clock.systemDefaultZone()));

        workshop = entityManager.persistAndFlush(Workshop.builder()
                .name("TALLER " + unique())
                .address("DIRECCION")
                .build());

        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(Jwt.withTokenValue("token")
                        .header("alg", "HS384")
                        .subject(UUID.randomUUID().toString())
                        .claim("workshop", workshop.getId().toString())
                        .build()));

        // Las filas que siembra V6, no unas inventadas: si alguien cambia el
        // codigo o el nombre con los que nacen, esta prueba lo nota.
        materialLiso = seededFamily("ML");
        hojaResorte = seededFamily("HR");
        maleta = seededFamily("MAL");

        // V22 ya declara `largo` y `tieneOrificio` en la hoja. El material liso
        // no los recibe de ninguna migracion —se dan de alta por la API—, asi
        // que la prueba los declara igual que lo haria la instalacion.
        // NADA que declarar: V36 ya da a las DOS familias el esquema de
        // transformacion completo —largo, tieneOrificio, cantidadColochos,
        // largoIzquierdo y largoDerecho—. Declararlo aqui volveria a esta prueba
        // ciega a una regresion de la migracion, que es justo lo que tiene que
        // detectar.
        assertThat(declaredNames(materialLiso))
                .containsExactlyInAnyOrder(
                        "largo", "tieneOrificio", "cantidadColochos",
                        "largoIzquierdo", "largoDerecho");
        assertThat(declaredNames(hojaResorte))
                .containsExactlyInAnyOrder(
                        "largo", "tieneOrificio", "cantidadColochos",
                        "largoIzquierdo", "largoDerecho");

        model = persistedModel();
        // El material liso no exige modelo (V6: requires_model = false), asi que
        // su producto nace con model_id NULL. Es justo el caso que la consulta
        // de equivalencia tiene que saber comparar.
        inputInventory = inventoryOf(
                materialLiso, null, "MATERIAL LISO 1200",
                attributes("largo", 1200, "tieneOrificio", false));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    // ── B. La transformacion entre familias llega a la base ────────────

    @Test
    void aMaterialLisoUnitBecomesAUnitOfTheSelectedLeafSpringProduct() {
        Product leaf = leafSpring("HOJA DE RESORTE 900", 900);

        InventoryItem output = transform(availableItem(), leaf);

        assertThat(productIdOf(output.getId())).isEqualTo(leaf.getId());
        assertThat(familyIdOfProduct(leaf.getId())).isEqualTo(hojaResorte.getId());
    }

    /**
     * G. La hoja elegida ya es la pieza resultante: no nace ningun producto
     * nuevo. Se cuenta sobre la tabla, no sobre el grafo en memoria.
     */
    @Test
    void theExistingLeafSpringProductIsReusedAndNotDuplicated() {
        Product leaf = leafSpring("HOJA DE RESORTE 900", 900);
        long before = productCount();

        transform(availableItem(), leaf);

        assertThat(productCount()).isEqualTo(before);
    }

    // ── H. `createdInventoryIds` dice la verdad ────────────────────────

    /**
     * El taller no tenia inventario de esa hoja: nace uno y se declara como
     * nuevo. En la segunda transformacion ya existe, se reutiliza y NO vuelve a
     * declararse: marcarlo otra vez haria que el cliente imprimiera una etiqueta
     * de alta para un inventario viejo.
     */
    @Test
    void theOutputInventoryIsOnlyReportedAsNewTheFirstTime() {
        Product leaf = leafSpring("HOJA DE RESORTE 900", 900);

        Set<UUID> first = transformReportingCreated(availableItem(), leaf);
        UUID inventoryId = inventoryIdOfProduct(leaf.getId());
        assertThat(first).contains(inventoryId);

        Set<UUID> second = transformReportingCreated(availableItem(), leaf);
        assertThat(second).doesNotContain(inventoryId);

        // Y el stock de ese unico inventario acumula las dos unidades.
        assertThat(stockOf(inventoryId)).isEqualTo(2);
    }

    /** El inventario de salida pertenece al taller del token, nunca a otro. */
    @Test
    void theOutputInventoryBelongsToTheWorkshopInTheToken() {
        Product leaf = leafSpring("HOJA DE RESORTE 900", 900);

        transform(availableItem(), leaf);

        assertThat(workshopIdOfInventory(inventoryIdOfProduct(leaf.getId())))
                .isEqualTo(workshop.getId());
    }

    // ── El sobrante sin modelo se reutiliza (IS NOT DISTINCT FROM) ─────

    /**
     * El sobrante de un material liso sin modelo se guarda con `model_id` NULL.
     * Dos transformaciones iguales tienen que compartirlo: comparar con
     * `model_id = NULL` en vez de `IS NOT DISTINCT FROM` daria siempre falso y
     * el catalogo se llenaria de sobrantes identicos.
     */
    @Test
    void theModellessRemainderIsReusedBetweenTransformations() {
        Product leaf = leafSpring("HOJA DE RESORTE 500", 500);

        transformationWithRemainder(availableItem(), leaf, 0);
        long afterFirst = remainderProductCount();

        transformationWithRemainder(availableItem(), leaf, 0);

        assertThat(afterFirst).isEqualTo(1);
        assertThat(remainderProductCount()).isEqualTo(1);
    }

    /** Y ese sobrante se queda en MATERIAL LISO, sin modelo y con el largo justo. */
    @Test
    void theRemainderStaysInTheInputFamilyWithoutAModel() {
        Product leaf = leafSpring("HOJA DE RESORTE 500", 500);

        Transformation transformation = transformationWithRemainder(availableItem(), leaf, 0);
        InventoryItem remainder = itemWithRole(
                transformation, TransformationItemRole.REMAINDER);

        UUID productId = productIdOf(remainder.getId());
        assertThat(familyIdOfProduct(productId)).isEqualTo(materialLiso.getId());
        assertThat(modelIdOfProduct(productId)).isNull();
        assertThat(lengthOfProduct(productId)).isEqualTo("700.0");
    }

    // ── I. Un destino ajeno no pasa, venga de donde venga ──────────────

    @Test
    void aForgedTargetInAnUnrelatedFamilyIsRejected() {
        Product suitcase = entityManager.persistAndFlush(Product.builder()
                .family(maleta)
                .model(model)
                .name("MALETA " + unique())
                .sku("SKU-" + unique())
                .attributes(attributes())
                .build());
        InventoryItem input = availableItem();
        long before = productCount();

        assertThatThrownBy(() -> transform(input, suitcase))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("no pertenece a una familia");

        assertThat(productCount()).isEqualTo(before);
        assertThat(statusOf(input.getId())).isEqualTo(InventoryItemStatus.AVAILABLE.name());
    }

    // ── D. El sentido contrario sigue cerrado ──────────────────────────

    /**
     * La entrada es una hoja, que SI declara colochos, asi que la peticion los
     * lleva: sin ellos el rechazo llegaria por la cantidad que falta y no se
     * habria comprobado lo que esta prueba mide, que es la compatibilidad.
     */
    @Test
    void aLeafSpringUnitStillCannotBecomeMaterialLiso() {
        Product leaf = leafSpring("HOJA DE RESORTE 900", 900);
        Inventory leafInventory = entityManager.persistAndFlush(Inventory.builder()
                .product(leaf)
                .workshop(workshop)
                .price(new BigDecimal("400.00"))
                .cost(SOURCE_COST)
                .stock(0)
                .minStock(0)
                .build());
        InventoryItem leafItem = availableItemOf(leafInventory);
        Product material = inputInventory.getProduct();

        assertThatThrownBy(() -> transformWithCurls(leafItem, material, 2))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("no pertenece a una familia");

        assertThat(statusOf(leafItem.getId()))
                .isEqualTo(InventoryItemStatus.AVAILABLE.name());
    }

    // ── E. El largo sigue mandando ─────────────────────────────────────

    @Test
    void aLeafSpringLongerThanTheMaterialIsRejected() {
        Product leaf = leafSpring("HOJA DE RESORTE 1500", 1500);
        InventoryItem input = availableItem();

        assertThatThrownBy(() -> transform(input, leaf))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("El largo resultante no puede ser mayor");

        assertThat(statusOf(input.getId()))
                .isEqualTo(InventoryItemStatus.AVAILABLE.name());
    }

    // ── 14. El modelo sale del producto de destino ─────────────────────

    /**
     * El material liso no exige modelo (V6: requires_model = false) y su pieza
     * de entrada no lo tiene; la hoja si. La pieza resultante adopta el del
     * producto de destino, y NO se exige que coincidan.
     */
    @Test
    void theOutputAdoptsTheTargetModelEvenThoughTheInputHasNone() {
        Product leaf = leafSpring("HOJA DE RESORTE 900", 900);
        assertThat(modelIdOfProduct(inputInventory.getProduct().getId())).isNull();

        InventoryItem output = transform(availableItem(), leaf);

        assertThat(modelIdOfProduct(productIdOf(output.getId())))
                .isEqualTo(model.getId());
    }

    // ── 12. Colochos, dirigidos por atributo tras la paridad ───────────

    /**
     * Tras V36 el material liso declara `cantidadColochos`, asi que una
     * transformacion suya DENTRO de su familia exige la cantidad como cualquier
     * otra familia que lo declare. No queda ninguna excepcion por familia.
     */
    @Test
    void materialLisoNowRequiresACurlCountForItsOwnFamilyResults() {
        InventoryItem input = availableItem();

        assertThatThrownBy(() -> customTransformation(input, Map.of("largo", 900), null))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("cantidad de colochos de la pieza resultante");
    }

    @Test
    void materialLisoAcceptsACurlCountForItsOwnFamilyResults() {
        InventoryItem output = itemWithRole(
                customTransformation(availableItem(), Map.of("largo", 900), 3),
                TransformationItemRole.OUTPUT);

        assertThat(curlCountOfProduct(productIdOf(output.getId()))).isEqualTo("3");
    }

    /**
     * Cambiando de familia la cantidad ya la fija el producto elegido: pedir una
     * distinta describiria una pieza que dice ser ese producto sin serlo.
     */
    @Test
    void aCurlCountIsRejectedWhenTheTargetProductAlreadyFixesIt() {
        Product leaf = leafSpring("HOJA DE RESORTE 900", 900);
        InventoryItem input = availableItem();

        assertThatThrownBy(() -> transformWithCurls(input, leaf, 5))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("es el producto de salida elegido");

        assertThat(statusOf(input.getId()))
                .isEqualTo(InventoryItemStatus.AVAILABLE.name());
    }

    /** Y la pieza resultante conserva los colochos que la hoja ya tenia. */
    @Test
    void theResultKeepsTheCurlCountTheTargetProductHad() {
        Product leaf = leafSpring("HOJA DE RESORTE 900", 900);

        InventoryItem output = transform(availableItem(), leaf);

        assertThat(curlCountOfProduct(productIdOf(output.getId()))).isEqualTo("2");
    }

    // ── 17. El codigo de barras identifica la unidad persistida ────────

    @Test
    void theOutputItemBarcodeMatchesThePersistedItem() {
        Product leaf = leafSpring("HOJA DE RESORTE 900", 900);

        InventoryItem output = transform(availableItem(), leaf);

        UUID persistedId = itemIdFromDatabase(output.getId());
        assertThat(persistedId).isEqualTo(output.getId());
        assertThat(BarcodeIdentity.item(persistedId))
                .isEqualTo(BarcodeIdentity.item(output.getId()))
                .startsWith("I1-");
    }

    // ── 8. El validador de orificio esta ACTIVO en la configuracion real ──

    /**
     * Antes de V36 `largoIzquierdo` / `largoDerecho` no los declaraba ninguna de
     * las dos familias, asi que `applies()` era false y la regla estaba dormida
     * en produccion. Esto lo fija sobre la configuracion que deja la migracion,
     * sin declarar nada la prueba.
     */
    @Test
    void theHoleMeasurementRuleIsActiveForBothFamilies() {
        assertThat(HoleMeasurementValidator.applies(materialLiso)).isTrue();
        assertThat(HoleMeasurementValidator.applies(hojaResorte)).isTrue();
    }

    /**
     * Y sigue siendo dirigida por atributos, no por familia: una familia que no
     * declara el conjunto completo queda intacta.
     */
    @Test
    void theHoleMeasurementRuleStaysInactiveForAFamilyWithoutTheFullSet() {
        assertThat(HoleMeasurementValidator.applies(maleta)).isFalse();
    }

    /**
     * Los dos lados NO son obligatorios por columna: su obligatoriedad la decide
     * la regla segun `tieneOrificio`. Si la migracion los hubiera declarado
     * `is_required = true`, toda pieza sin orificio dejaria de poder guardarse.
     */
    @Test
    void theSideLengthsAreNotUnconditionallyRequired() {
        for (Family family : List.of(materialLiso, hojaResorte)) {
            for (String side : List.of("largoIzquierdo", "largoDerecho")) {
                assertThat(requiredFlagOf(family, side))
                        .as("%s.%s is_required", family.getCode(), side)
                        .isFalse();
                assertThat(HoleMeasurementValidator.ownsRequiredness(family, side))
                        .as("%s.%s lo gobierna la regla", family.getCode(), side)
                        .isTrue();
            }
        }
    }

    // ── 9. Casos de orificio contra la configuracion migrada ──────────

    /** A. Sin orificio los lados no intervienen y la pieza pasa. */
    @Test
    void holeFalseIgnoresTheSideLengths() {
        Product leaf = leafSpring("HOJA SIN ORIFICIO", 900);

        InventoryItem output = transform(availableItem(), leaf);

        assertThat(productIdOf(output.getId())).isEqualTo(leaf.getId());
    }

    /** B / I. Con orificio, la suma exacta pasa. */
    @Test
    void holeTrueWithExactSumIsAccepted() {
        Product leaf = holedLeafSpring(100, 40, 60);

        // La entrada mide 1200 y no tiene orificio, asi que cortar a 100 deja un
        // sobrante de material liso: lleva su propia cantidad de colochos.
        InventoryItem output = itemWithRole(
                transformationWithRemainder(availableItem(), leaf, 0),
                TransformationItemRole.OUTPUT);

        assertThat(productIdOf(output.getId())).isEqualTo(leaf.getId());
    }

    /** C. Izquierdo igual a cero. */
    @Test
    void holeTrueWithZeroLeftIsRejected() {
        assertHoleTargetRejected(holedLeafSpring(100, 0, 100), "izquierdo debe ser mayor a 0");
    }

    /** D. Derecho igual a cero. */
    @Test
    void holeTrueWithZeroRightIsRejected() {
        assertHoleTargetRejected(holedLeafSpring(100, 100, 0), "derecho debe ser mayor a 0");
    }

    /** E. Lados negativos. */
    @Test
    void holeTrueWithNegativeSidesIsRejected() {
        assertHoleTargetRejected(holedLeafSpring(100, -40, 140), "izquierdo debe ser mayor a 0");
        assertHoleTargetRejected(holedLeafSpring(100, 140, -40), "derecho debe ser mayor a 0");
    }

    /** F. Falta uno de los dos lados. */
    @Test
    void holeTrueWithAMissingSideIsRejected() {
        assertHoleTargetRejected(
                holedLeafSpringWithout(100, 40, "largoDerecho"), "largo derecho");
        assertHoleTargetRejected(
                holedLeafSpringWithout(100, 60, "largoIzquierdo"), "largo izquierdo");
    }

    /** G. La suma se queda corta. */
    @Test
    void holeTrueWithADeficitIsRejected() {
        assertHoleTargetRejected(holedLeafSpring(100, 40, 50), "exactamente igual");
    }

    /** H. La suma se pasa. */
    @Test
    void holeTrueWithAnExcessIsRejected() {
        assertHoleTargetRejected(holedLeafSpring(100, 60, 60), "exactamente igual");
    }

    /**
     * La comparacion es decimal exacta: 45.1 + 54.9 == 100 es cierto en
     * BigDecimal y falso en coma flotante.
     */
    @Test
    void holeTrueWithExactDecimalSumIsAccepted() {
        Product leaf = holedLeafSpringDecimal("100", "45.1", "54.9");

        InventoryItem output = itemWithRole(
                transformationWithRemainder(availableItem(), leaf, 0),
                TransformationItemRole.OUTPUT);

        assertThat(productIdOf(output.getId())).isEqualTo(leaf.getId());
    }
    // ── 11. Mismo-familia: la regla ya activa no rompe lo de siempre ───

    /**
     * MATERIAL LISO -> MATERIAL LISO sin orificio. Es el flujo de siempre, y
     * tras activar la regla tiene que seguir pasando igual: sin orificio los dos
     * lados no intervienen aunque la familia ya los declare.
     */
    @Test
    void sameFamilyMaterialLisoWithoutHoleStillWorks() {
        InventoryItem output = itemWithRole(
                customTransformation(availableItem(),
                        Map.of("largo", 900, "tieneOrificio", false), 0, null),
                TransformationItemRole.OUTPUT);

        UUID productId = productIdOf(output.getId());
        assertThat(familyIdOfProduct(productId)).isEqualTo(materialLiso.getId());
        assertThat(lengthOfProduct(productId)).isEqualTo("900");
    }

    /** MATERIAL LISO -> MATERIAL LISO con orificio y medidas exactas. */
    @Test
    void sameFamilyMaterialLisoWithAValidHoleIsAccepted() {
        InventoryItem output = itemWithRole(
                customTransformation(availableItem(),
                        Map.of("largo", 100, "tieneOrificio", true,
                                "largoIzquierdo", 40, "largoDerecho", 60),
                        0, 0),
                TransformationItemRole.OUTPUT);

        UUID productId = productIdOf(output.getId());
        assertThat(familyIdOfProduct(productId)).isEqualTo(materialLiso.getId());
    }

    /** Y con medidas que no cuadran se rechaza, sin escribir nada. */
    @Test
    void sameFamilyMaterialLisoWithAnInvalidHoleIsRejected() {
        InventoryItem input = availableItem();
        long before = productCount();

        assertThatThrownBy(() -> customTransformation(input,
                Map.of("largo", 100, "tieneOrificio", true,
                        "largoIzquierdo", 40, "largoDerecho", 50),
                0, 0))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("exactamente igual");

        assertThat(productCount()).isEqualTo(before);
        assertThat(statusOf(input.getId()))
                .isEqualTo(InventoryItemStatus.AVAILABLE.name());
    }

    /** HOJA DE RESORTE -> HOJA DE RESORTE sin orificio. */
    @Test
    void sameFamilyLeafSpringWithoutHoleStillWorks() {
        InventoryItem output = itemWithRole(
                customTransformation(availableLeafSpringItem(),
                        Map.of("largo", 900, "tieneOrificio", false), 2, null),
                TransformationItemRole.OUTPUT);

        UUID productId = productIdOf(output.getId());
        assertThat(familyIdOfProduct(productId)).isEqualTo(hojaResorte.getId());
        assertThat(curlCountOfProduct(productId)).isEqualTo("2");
    }

    /** HOJA DE RESORTE -> HOJA DE RESORTE con orificio valido. */
    @Test
    void sameFamilyLeafSpringWithAValidHoleIsAccepted() {
        InventoryItem output = itemWithRole(
                customTransformation(availableLeafSpringItem(),
                        Map.of("largo", 100, "tieneOrificio", true,
                                "largoIzquierdo", 45, "largoDerecho", 55),
                        1, 0),
                TransformationItemRole.OUTPUT);

        assertThat(familyIdOfProduct(productIdOf(output.getId())))
                .isEqualTo(hojaResorte.getId());
    }

    /** HOJA DE RESORTE con orificio invalido: rechazado, sin escrituras. */
    @Test
    void sameFamilyLeafSpringWithAnInvalidHoleIsRejected() {
        InventoryItem input = availableLeafSpringItem();
        long before = productCount();

        assertThatThrownBy(() -> customTransformation(input,
                Map.of("largo", 100, "tieneOrificio", true,
                        "largoIzquierdo", 70, "largoDerecho", 70),
                1, 0))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("exactamente igual");

        assertThat(productCount()).isEqualTo(before);
        assertThat(statusOf(input.getId()))
                .isEqualTo(InventoryItemStatus.AVAILABLE.name());
    }
    // ── Auxiliares ─────────────────────────────────────────────────────

    /** Una hoja con orificio y un lado ausente a proposito. */
    private Product holedLeafSpringWithout(int length, int presentValue, String missing) {
        Map<String, Object> attrs = attributes(
                "largo", length, "tieneOrificio", true, "cantidadColochos", 0);
        attrs.put(missing.equals("largoDerecho") ? "largoIzquierdo" : "largoDerecho", presentValue);
        return entityManager.persistAndFlush(Product.builder()
                .family(hojaResorte)
                .model(model)
                .name("HOJA SIN " + missing + " " + unique())
                .sku("SKU-" + unique())
                .attributes(attrs)
                .build());
    }

    /** Medidas con decimales, para que la comparacion exacta se note. */
    private Product holedLeafSpringDecimal(String length, String left, String right) {
        return entityManager.persistAndFlush(Product.builder()
                .family(hojaResorte)
                .model(model)
                .name("HOJA DECIMAL " + unique())
                .sku("SKU-" + unique())
                .attributes(attributes(
                        "largo", new java.math.BigDecimal(length),
                        "tieneOrificio", true,
                        "largoIzquierdo", new java.math.BigDecimal(left),
                        "largoDerecho", new java.math.BigDecimal(right),
                        "cantidadColochos", 0))
                .build());
    }

    /** El `is_required` que la migracion dejo escrito para ese atributo. */
    private boolean requiredFlagOf(Family family, String attributeName) {
        return family.getAttributes().stream()
                .filter(a -> a.getName().equalsIgnoreCase(attributeName))
                .findFirst()
                .orElseThrow()
                .getIsRequired();
    }

    /** Una hoja con orificio y las medidas que se quieran probar. */
    private Product holedLeafSpring(int length, int left, int right) {
        return entityManager.persistAndFlush(Product.builder()
                .family(hojaResorte)
                .model(model)
                .name("HOJA CON ORIFICIO " + unique())
                .sku("SKU-" + unique())
                .attributes(attributes(
                        "largo", length, "tieneOrificio", true,
                        "largoIzquierdo", left, "largoDerecho", right,
                        "cantidadColochos", 0))
                .build());
    }

    private void assertHoleTargetRejected(Product leaf, String expectedMessage) {
        InventoryItem input = availableItem();
        long before = productCount();

        assertThatThrownBy(() -> transform(input, leaf))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining(expectedMessage);

        assertThat(productCount()).isEqualTo(before);
        assertThat(statusOf(input.getId()))
                .isEqualTo(InventoryItemStatus.AVAILABLE.name());
    }

    /** Transformacion DENTRO de la familia de la entrada, por caracteristicas. */
    private Transformation customTransformation(
            InventoryItem inputItem,
            Map<String, Object> outputAttributes,
            Integer outputCurlCount) {
        return customTransformation(inputItem, outputAttributes, outputCurlCount, null);
    }

    private Transformation customTransformation(
            InventoryItem inputItem,
            Map<String, Object> outputAttributes,
            Integer outputCurlCount,
            Integer remainderCurlCount) {
        Transformation transformation = Transformation.builder().build();
        handler.generate(transformation, inputItem, new CreateTransformationRequestDTO(
                inputItem.getId(), null, null, null,
                outputAttributes, outputCurlCount, remainderCurlCount, null));
        entityManager.flush();
        return transformation;
    }

    /** Una unidad fisica disponible de HOJA DE RESORTE, para el flujo propio. */
    private InventoryItem availableLeafSpringItem() {
        Inventory inventory = inventoryOf(
                hojaResorte, model, "HOJA DE ENTRADA",
                attributes("largo", 1200, "tieneOrificio", false, "cantidadColochos", 2));
        return availableItemOf(inventory);
    }

    private String curlCountOfProduct(UUID productId) {
        entityManager.flush();
        return (String) entityManager.getEntityManager()
                .createNativeQuery(
                        "SELECT jsonb_extract_path_text(attributes, 'cantidadColochos')"
                        + " FROM product WHERE id = :id")
                .setParameter("id", productId)
                .getSingleResult();
    }

    private UUID itemIdFromDatabase(UUID itemId) {
        entityManager.flush();
        return (UUID) entityManager.getEntityManager()
                .createNativeQuery("SELECT id FROM inventory_item WHERE id = :id")
                .setParameter("id", itemId)
                .getSingleResult();
    }
    // ── Auxiliares ─────────────────────────────────────────────────────

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** La familia que V6 sembro con ese codigo. */
    private Family seededFamily(String code) {
        return familyRepository.findAll()
                .stream()
                .filter(family -> code.equals(family.getCode()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "La migracion V6 deberia haber sembrado la familia " + code));
    }

    /** Los nombres que la familia declara, tal y como los dejo la migracion. */
    private List<String> declaredNames(Family family) {
        return family.getAttributes().stream().map(Attribute::getName).toList();
    }

    private Model persistedModel() {
        Brand brand = entityManager.persistAndFlush(Brand.builder()
                .name("MARCA " + unique())
                .logoKey("brands/" + unique() + ".png")
                .build());
        return entityManager.persistAndFlush(Model.builder()
                .brand(brand)
                .name("MODELO " + unique())
                .yearFrom(2020)
                .yearTo(2020)
                .build());
    }

    private static Map<String, Object> attributes(Object... keysAndValues) {
        Map<String, Object> productAttributes = new HashMap<>();
        for (int index = 0; index < keysAndValues.length; index += 2) {
            productAttributes.put((String) keysAndValues[index], keysAndValues[index + 1]);
        }
        return productAttributes;
    }

    /**
     * Una hoja de resorte del catalogo. Solo lleva las caracteristicas que su
     * familia declara: `validateDeclaredAttributes` rechaza cualquier otra, igual
     * que en el resto del proyecto.
     */
    private Product leafSpring(String name, int length) {
        return entityManager.persistAndFlush(Product.builder()
                .family(hojaResorte)
                .model(model)
                .name(name + " " + unique())
                .sku("SKU-" + unique())
                .attributes(attributes(
                        "largo", length, "tieneOrificio", false, "cantidadColochos", 2))
                .build());
    }

    private Inventory inventoryOf(
            Family family, Model productModel, String productName,
            Map<String, Object> productAttributes) {
        Product product = entityManager.persistAndFlush(Product.builder()
                .family(family)
                .model(productModel)
                .name(productName + " " + unique())
                .sku("SKU-" + unique())
                .attributes(productAttributes)
                .build());
        return entityManager.persistAndFlush(Inventory.builder()
                .product(product)
                .workshop(workshop)
                .price(new BigDecimal("400.00"))
                .cost(SOURCE_COST)
                .stock(0)
                .minStock(0)
                .build());
    }

    private InventoryItem availableItem() {
        return availableItemOf(inputInventory);
    }

    private InventoryItem availableItemOf(Inventory inventory) {
        InventoryItem item = entityManager.persistAndFlush(InventoryItem.builder()
                .inventory(inventory)
                .status(InventoryItemStatus.AVAILABLE)
                .build());
        inventory.addStock(1);
        entityManager.persistAndFlush(InventoryAcquisition.builder()
                .workshop(workshop)
                .inventory(inventory)
                .origin(AcquisitionOrigin.PRODUCTION)
                .quantityReceived(1)
                .quantityRemaining(1)
                .acquiredAt(LocalDateTime.now())
                .build());
        entityManager.flush();
        return item;
    }

    /**
     * La peticion viaja con `outputProduct`, que es exactamente lo que manda el
     * frontend cuando el destino cambia de familia y lo que mandaria un cliente
     * fabricado a mano.
     */
    private Transformation transformation(InventoryItem inputItem, Product target) {
        return transformation(inputItem, target, null);
    }

    private Transformation transformation(
            InventoryItem inputItem, Product target, Integer outputCurlCount) {
        return transformation(inputItem, target, outputCurlCount, null);
    }

    private Transformation transformation(
            InventoryItem inputItem, Product target,
            Integer outputCurlCount, Integer remainderCurlCount) {
        Transformation transformation = Transformation.builder().build();
        handler.generate(transformation, inputItem, new CreateTransformationRequestDTO(
                inputItem.getId(), null, target.getId(), null,
                null, outputCurlCount, remainderCurlCount, null));
        entityManager.flush();
        return transformation;
    }

    /**
     * Con sobrante. Tras V36 el material liso declara `cantidadColochos`, asi
     * que el recorte —que es una pieza de material liso— exige la suya. La
     * principal NO la lleva: la fija el producto de destino.
     */
    private Transformation transformationWithRemainder(
            InventoryItem inputItem, Product target, int remainderCurlCount) {
        return transformation(inputItem, target, null, remainderCurlCount);
    }

    private InventoryItem transformWithCurls(
            InventoryItem inputItem, Product target, Integer outputCurlCount) {
        return itemWithRole(
                transformation(inputItem, target, outputCurlCount),
                TransformationItemRole.OUTPUT);
    }

    private Set<UUID> transformReportingCreated(InventoryItem inputItem, Product target) {
        Transformation transformation = Transformation.builder().build();
        Set<UUID> created = handler.generate(
                transformation, inputItem, new CreateTransformationRequestDTO(
                        inputItem.getId(), null, target.getId(), null,
                        null, null, null, null));
        entityManager.flush();
        return created;
    }

    private InventoryItem transform(InventoryItem inputItem, Product target) {
        return itemWithRole(
                transformation(inputItem, target), TransformationItemRole.OUTPUT);
    }

    private InventoryItem itemWithRole(
            Transformation transformation, TransformationItemRole role) {
        return transformation.getItems()
                .stream()
                .filter(item -> item.getRole() == role)
                .map(TransformationItem::getItem)
                .findFirst()
                .orElseThrow();
    }

    // ── Lecturas directas de la base de datos ──────────────────────────

    private UUID productIdOf(UUID itemId) {
        entityManager.flush();
        return (UUID) entityManager.getEntityManager()
                .createNativeQuery("""
                    SELECT i.product_id
                    FROM inventory_item ii
                        JOIN inventory i ON i.id = ii.inventory_id
                    WHERE ii.id = :id
                    """)
                .setParameter("id", itemId)
                .getSingleResult();
    }

    private UUID familyIdOfProduct(UUID productId) {
        entityManager.flush();
        return (UUID) entityManager.getEntityManager()
                .createNativeQuery("SELECT family_id FROM product WHERE id = :id")
                .setParameter("id", productId)
                .getSingleResult();
    }

    private UUID modelIdOfProduct(UUID productId) {
        entityManager.flush();
        return (UUID) entityManager.getEntityManager()
                .createNativeQuery("SELECT model_id FROM product WHERE id = :id")
                .setParameter("id", productId)
                .getSingleResult();
    }

    private String lengthOfProduct(UUID productId) {
        entityManager.flush();
        return (String) entityManager.getEntityManager()
                .createNativeQuery("""
                    SELECT jsonb_extract_path_text(attributes, 'largo')
                    FROM product WHERE id = :id
                    """)
                .setParameter("id", productId)
                .getSingleResult();
    }

    private UUID inventoryIdOfProduct(UUID productId) {
        entityManager.flush();
        return (UUID) entityManager.getEntityManager()
                .createNativeQuery("""
                    SELECT id FROM inventory
                    WHERE product_id = :product AND workshop_id = :workshop
                    """)
                .setParameter("product", productId)
                .setParameter("workshop", workshop.getId())
                .getSingleResult();
    }

    private UUID workshopIdOfInventory(UUID inventoryId) {
        entityManager.flush();
        return (UUID) entityManager.getEntityManager()
                .createNativeQuery("SELECT workshop_id FROM inventory WHERE id = :id")
                .setParameter("id", inventoryId)
                .getSingleResult();
    }

    private int stockOf(UUID inventoryId) {
        entityManager.flush();
        return ((Number) entityManager.getEntityManager()
                .createNativeQuery("SELECT stock FROM inventory WHERE id = :id")
                .setParameter("id", inventoryId)
                .getSingleResult())
                .intValue();
    }

    private String statusOf(UUID itemId) {
        entityManager.flush();
        return (String) entityManager.getEntityManager()
                .createNativeQuery("SELECT status FROM inventory_item WHERE id = :id")
                .setParameter("id", itemId)
                .getSingleResult();
    }

    private long productCount() {
        entityManager.flush();
        return ((Number) entityManager.getEntityManager()
                .createNativeQuery("SELECT count(*) FROM product")
                .getSingleResult())
                .longValue();
    }

    /** Sobrantes: material liso, sin modelo y con el largo del recorte. */
    private long remainderProductCount() {
        entityManager.flush();
        return ((Number) entityManager.getEntityManager()
                .createNativeQuery("""
                    SELECT count(*) FROM product
                    WHERE family_id = :family
                        AND model_id IS NULL
                        AND jsonb_extract_path_text(attributes, 'largo') = '700.0'
                    """)
                .setParameter("family", materialLiso.getId())
                .getSingleResult())
                .longValue();
    }

}
