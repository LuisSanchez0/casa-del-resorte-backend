package com.cns.spring_house.transformation.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.cns.spring_house.inventory.dto.InventoryItemResponseDTO;
import com.cns.spring_house.inventory.entity.Inventory;
import com.cns.spring_house.inventory.entity.InventoryItem;
import com.cns.spring_house.inventory.entity.InventoryItemStatus;
import com.cns.spring_house.inventory.mapper.InventoryItemMapperImpl;
import com.cns.spring_house.product.entity.Product;
import com.cns.spring_house.transformation.dto.TransformationItemResponseDTO;
import com.cns.spring_house.transformation.dto.TransformationResponseDTO;
import com.cns.spring_house.transformation.entity.Transformation;
import com.cns.spring_house.transformation.entity.TransformationItem;
import com.cns.spring_house.transformation.entity.TransformationItemRole;
import com.cns.spring_house.workshop.entity.Workshop;

import tools.jackson.databind.ObjectMapper;

/**
 * El detalle de una transformación tiene que contar qué ES cada pieza, no solo
 * cómo se llama: el historial es lo único que queda de una unidad ya consumida.
 *
 * Las características salen del Product persistido de cada item —del mismo que
 * ya aporta el nombre—, así que describen lo que se guardó y no lo que se pidió
 * al crear la transformación.
 */
class TransformationDetailAttributesTest {

    private final TransformationMapper mapper = transformationMapper();

    private final ObjectMapper json = new ObjectMapper();

    /**
     * Los mapeadores generados por MapStruct reciben sus colaboradores por
     * inyección de campo, así que aquí se arma la misma pareja que compone
     * Spring en producción: TransformationMapper delegando en
     * InventoryItemMapper. Se prueba el mapeo real, no una imitación.
     */
    private static TransformationMapper transformationMapper() {
        TransformationMapperImpl mapper = new TransformationMapperImpl();
        ReflectionTestUtils.setField(
                mapper, "inventoryItemMapper", new InventoryItemMapperImpl());
        return mapper;
    }

    private final Workshop workshop = Workshop.builder()
            .id(UUID.randomUUID())
            .name("TALLER")
            .address("DIRECCION")
            .build();

    @Test
    @DisplayName("Cada rol devuelve las características de SU producto")
    void everyRoleCarriesTheAttributesOfItsOwnProduct() {
        Transformation transformation = transformationWith(
                itemOf(TransformationItemRole.INPUT, "Hoja de entrada",
                        attributes("largo", 200, "tieneOrificio", true)),
                itemOf(TransformationItemRole.OUTPUT, "Hoja resultante",
                        attributes("largo", 150, "tieneOrificio", false)),
                itemOf(TransformationItemRole.REMAINDER, "Sobrante de hoja",
                        attributes("largo", 50, "cantidadColochos", 0)));

        TransformationResponseDTO response = mapper.toResponse(transformation);

        assertThat(itemOfRole(response, TransformationItemRole.INPUT).attributes())
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of("largo", 200, "tieneOrificio", true));
        assertThat(itemOfRole(response, TransformationItemRole.OUTPUT).attributes())
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of("largo", 150, "tieneOrificio", false));
        assertThat(itemOfRole(response, TransformationItemRole.REMAINDER).attributes())
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of("largo", 50, "cantidadColochos", 0));
    }

    /** El nombre que ya se devolvía sigue estando: esto solo añade. */
    @Test
    void theProductNameIsStillThere() {
        Transformation transformation = transformationWith(
                itemOf(TransformationItemRole.OUTPUT, "Hoja resultante",
                        attributes("largo", 150)));

        assertThat(itemOfRole(mapper.toResponse(transformation),
                TransformationItemRole.OUTPUT).productName())
                .isEqualTo("Hoja resultante");
    }

    /**
     * Un booleano tiene que llegar al cliente como booleano JSON. Serializado
     * como "true" obligaría al frontend a adivinar el tipo y `false` acabaría
     * leyéndose como texto con valor.
     */
    @Test
    void booleansTravelAsJsonBooleansAndNotAsText() {
        Transformation transformation = transformationWith(
                itemOf(TransformationItemRole.INPUT, "Hoja con orificio",
                        attributes("tieneOrificio", true, "esRecta", false)));

        String body = json.writeValueAsString(mapper.toResponse(transformation));

        assertThat(body).contains("\"tieneOrificio\":true");
        assertThat(body).contains("\"esRecta\":false");
        assertThat(body).doesNotContain("\"tieneOrificio\":\"true\"");
        assertThat(body).doesNotContain("\"esRecta\":\"false\"");
    }

    /** Los números tampoco se convierten en texto por el camino. */
    @Test
    void numbersTravelAsJsonNumbers() {
        Transformation transformation = transformationWith(
                itemOf(TransformationItemRole.OUTPUT, "Hoja resultante",
                        attributes("largo", 150, "diametro", 10.5)));

        String body = json.writeValueAsString(mapper.toResponse(transformation));

        assertThat(body).contains("\"largo\":150");
        assertThat(body).contains("\"diametro\":10.5");
    }

    /** Un producto sin características registradas no inventa ninguna. */
    @Test
    void aProductWithoutAttributesReturnsAnEmptyMap() {
        Transformation transformation = transformationWith(
                itemOf(TransformationItemRole.INPUT, "Pieza sin atributos",
                        new HashMap<>()));

        assertThat(itemOfRole(mapper.toResponse(transformation),
                TransformationItemRole.INPUT).attributes())
                .isEmpty();
    }

    /** Y uno cuyo JSONB es nulo no revienta el mapeo: viaja en null. */
    @Test
    void aProductWithNullAttributesIsMappedAsNull() {
        Transformation transformation = transformationWith(
                itemOf(TransformationItemRole.INPUT, "Pieza historica", null));

        assertThat(itemOfRole(mapper.toResponse(transformation),
                TransformationItemRole.INPUT).attributes())
                .isNull();
    }

    /**
     * Las características son una copia: modificar la respuesta no puede tocar
     * el JSONB de un producto que sigue vivo en el catálogo.
     */
    @Test
    void theResponseDoesNotShareTheProductAttributeMap() {
        Map<String, Object> persisted = attributes("largo", 200);
        Transformation transformation = transformationWith(
                itemOf(TransformationItemRole.INPUT, "Hoja de entrada", persisted));

        Map<String, Object> mapped = itemOfRole(mapper.toResponse(transformation),
                TransformationItemRole.INPUT).attributes();
        mapped.put("largo", 999);

        assertThat(persisted).containsEntry("largo", 200);
    }

    // ── Auxiliares ─────────────────────────────────────────────────────

    private static Map<String, Object> attributes(Object... keysAndValues) {
        Map<String, Object> productAttributes = new LinkedHashMap<>();
        for (int index = 0; index < keysAndValues.length; index += 2) {
            productAttributes.put((String) keysAndValues[index], keysAndValues[index + 1]);
        }
        return productAttributes;
    }

    private TransformationItem itemOf(
            TransformationItemRole role,
            String productName,
            Map<String, Object> productAttributes) {
        Inventory inventory = Inventory.builder()
                .id(UUID.randomUUID())
                .workshop(workshop)
                .product(Product.builder()
                        .id(UUID.randomUUID())
                        .name(productName)
                        .sku("SKU-" + productName.hashCode())
                        .attributes(productAttributes)
                        .build())
                .cost(new BigDecimal("125.50"))
                .price(new BigDecimal("400.00"))
                .stock(1)
                .build();
        InventoryItem unit = InventoryItem.builder()
                .id(UUID.randomUUID())
                .status(InventoryItemStatus.AVAILABLE)
                .build();
        inventory.addItem(unit);

        return TransformationItem.builder()
                .id(UUID.randomUUID())
                .item(unit)
                .role(role)
                .build();
    }

    private Transformation transformationWith(TransformationItem... items) {
        Transformation transformation = Transformation.builder()
                .id(UUID.randomUUID())
                .workshop(workshop)
                .performedDate(LocalDateTime.now())
                .build();
        for (TransformationItem item : items) {
            transformation.addItem(item);
        }
        return transformation;
    }

    private InventoryItemResponseDTO itemOfRole(
            TransformationResponseDTO response, TransformationItemRole role) {
        return response.items()
                .stream()
                .filter(item -> item.role() == role)
                .map(TransformationItemResponseDTO::item)
                .findFirst()
                .orElseThrow();
    }

}
