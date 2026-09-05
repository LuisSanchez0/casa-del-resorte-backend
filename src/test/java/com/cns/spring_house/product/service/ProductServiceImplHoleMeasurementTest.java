package com.cns.spring_house.product.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.cns.spring_house.family.entity.Attribute;
import com.cns.spring_house.family.entity.AttributeDataType;
import com.cns.spring_house.family.entity.Family;
import com.cns.spring_house.family.entity.FamilyCategory;
import com.cns.spring_house.family.repository.FamilyRepository;
import com.cns.spring_house.model.repository.ModelRepository;
import com.cns.spring_house.product.dto.CreateProductRequestDTO;
import com.cns.spring_house.product.dto.UpdateProductRequestDTO;
import com.cns.spring_house.product.entity.Product;
import com.cns.spring_house.product.mapper.ProductMapper;
import com.cns.spring_house.product.repository.ProductRepository;
import com.cns.spring_house.shared.exception.ValidationException;

/**
 * La regla de medidas con orificio en el alta y la edicion de productos. El
 * backend es la autoridad: la comprobacion vive en el servicio, no en el DTO ni
 * en el cliente.
 *
 * La familia de la prueba declara el conjunto completo
 * {@code largo + tieneOrificio + largoIzquierdo + largoDerecho}; una familia
 * cualquiera que no lo declare queda intacta.
 */
@ExtendWith(MockitoExtension.class)
class ProductServiceImplHoleMeasurementTest {

    @Mock
    private ProductRepository repository;

    @Mock
    private ProductMapper mapper;

    @Mock
    private FamilyRepository familyRepository;

    @Mock
    private ModelRepository modelRepository;

    @InjectMocks
    private ProductServiceImpl service;

    private final Family holeFamily = familyDeclaring(
            attribute("largo", AttributeDataType.NUMBER, true),
            attribute("tieneOrificio", AttributeDataType.BOOLEAN, true),
            attribute("largoIzquierdo", AttributeDataType.NUMBER, false),
            attribute("largoDerecho", AttributeDataType.NUMBER, false));

    /** El mismo conjunto, pero con los largos de cada lado declarados obligatorios. */
    private final Family holeFamilyWithRequiredSides = familyDeclaring(
            attribute("largo", AttributeDataType.NUMBER, true),
            attribute("tieneOrificio", AttributeDataType.BOOLEAN, true),
            attribute("largoIzquierdo", AttributeDataType.NUMBER, true),
            attribute("largoDerecho", AttributeDataType.NUMBER, true));

    private final Family plainFamily = familyDeclaring(
            attribute("largo", AttributeDataType.NUMBER, true),
            attribute("ancho", AttributeDataType.NUMBER, true));

    @BeforeEach
    void setUpCommonStubs() {
        lenient().when(repository.existsBySku(any())).thenReturn(false);
        lenient().when(repository.save(any(Product.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(mapper.toProduct(any()))
                .thenAnswer(invocation -> {
                    CreateProductRequestDTO dto = invocation.getArgument(0);
                    return Product.builder().attributes(new HashMap<>(dto.attributes())).build();
                });
        lenient().when(mapper.toResponse(any())).thenReturn(null);
    }

    // ── create ───────────────────────────────────────────────────────

    @Test
    void createWithoutHoleDoesNotRequireSideLengths() {
        when(familyRepository.findById(holeFamily.getId())).thenReturn(Optional.of(holeFamily));

        assertThatCode(() -> service.create(createRequest(holeFamily.getId(), attributes(
                "largo", 100, "tieneOrificio", false))))
                .doesNotThrowAnyException();

        verify(repository).save(any(Product.class));
    }

    @Test
    void createWithHoleAndAValidSidePairSucceeds() {
        when(familyRepository.findById(holeFamily.getId())).thenReturn(Optional.of(holeFamily));

        assertThatCode(() -> service.create(createRequest(holeFamily.getId(), attributes(
                "largo", 100, "tieneOrificio", true,
                "largoIzquierdo", 45, "largoDerecho", 55))))
                .doesNotThrowAnyException();

        verify(repository).save(any(Product.class));
    }

    @Test
    void createWithHoleAndASideSumAboveTheTotalIsRejected() {
        when(familyRepository.findById(holeFamily.getId())).thenReturn(Optional.of(holeFamily));

        assertThatThrownBy(() -> service.create(createRequest(holeFamily.getId(), attributes(
                "largo", 100, "tieneOrificio", true,
                "largoIzquierdo", 60, "largoDerecho", 50))))
                .isInstanceOf(ValidationException.class)
                .hasMessage("La suma del largo izquierdo y derecho debe ser exactamente igual al largo total.");

        verify(repository, never()).save(any(Product.class));
    }

    @Test
    void createWithHoleAndAMissingSideLengthIsRejected() {
        when(familyRepository.findById(holeFamily.getId())).thenReturn(Optional.of(holeFamily));

        assertThatThrownBy(() -> service.create(createRequest(holeFamily.getId(), attributes(
                "largo", 100, "tieneOrificio", true, "largoIzquierdo", 45))))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Debe indicar el largo derecho cuando el producto tiene orificio.");

        verify(repository, never()).save(any(Product.class));
    }

    @Test
    void createWithoutHoleSucceedsEvenWhenTheFamilyDeclaresTheSidesRequired() {
        when(familyRepository.findById(holeFamilyWithRequiredSides.getId()))
                .thenReturn(Optional.of(holeFamilyWithRequiredSides));

        assertThatCode(() -> service.create(createRequest(
                holeFamilyWithRequiredSides.getId(),
                attributes("largo", 100, "tieneOrificio", false))))
                .doesNotThrowAnyException();

        verify(repository).save(any(Product.class));
    }

    @Test
    void createWithHoleStillRequiresTheSidesEvenWhenTheFamilyMarksThemOptional() {
        when(familyRepository.findById(holeFamily.getId())).thenReturn(Optional.of(holeFamily));

        assertThatThrownBy(() -> service.create(createRequest(holeFamily.getId(), attributes(
                "largo", 100, "tieneOrificio", true))))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("largo izquierdo");

        verify(repository, never()).save(any(Product.class));
    }

    @Test
    void createForAFamilyOutsideTheSetIsUnaffected() {
        when(familyRepository.findById(plainFamily.getId())).thenReturn(Optional.of(plainFamily));

        assertThatCode(() -> service.create(createRequest(plainFamily.getId(), attributes(
                "largo", 100, "ancho", 20))))
                .doesNotThrowAnyException();

        verify(repository).save(any(Product.class));
    }

    // ── update ───────────────────────────────────────────────────────

    @Test
    void updateThatTurnsOnTheHoleWithBadSidesIsRejected() {
        UUID productId = UUID.randomUUID();
        Product stored = Product.builder()
                .id(productId)
                .family(holeFamily)
                .attributes(attributes(
                        "largo", 100, "tieneOrificio", true,
                        "largoIzquierdo", 60, "largoDerecho", 50))
                .build();
        when(repository.findById(productId)).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> service.update(productId, updateRequest()))
                .isInstanceOf(ValidationException.class)
                .hasMessage("La suma del largo izquierdo y derecho debe ser exactamente igual al largo total.");

        verify(repository, never()).save(any(Product.class));
    }

    @Test
    void updateWithHoleAndAValidSidePairSucceeds() {
        UUID productId = UUID.randomUUID();
        Product stored = Product.builder()
                .id(productId)
                .family(holeFamily)
                .attributes(attributes(
                        "largo", 100, "tieneOrificio", true,
                        "largoIzquierdo", 45, "largoDerecho", 55))
                .build();
        when(repository.findById(productId)).thenReturn(Optional.of(stored));

        assertThatCode(() -> service.update(productId, updateRequest()))
                .doesNotThrowAnyException();

        verify(repository).save(any(Product.class));
    }

    // ── Auxiliares ───────────────────────────────────────────────────

    private static Attribute attribute(
            String name, AttributeDataType dataType, boolean isRequired) {
        return Attribute.builder()
                .id(UUID.randomUUID())
                .name(name)
                .dataType(dataType)
                .isRequired(isRequired)
                .build();
    }

    private static Family familyDeclaring(Attribute... declared) {
        return Family.builder()
                .id(UUID.randomUUID())
                .name("FAMILIA")
                .category(FamilyCategory.SPECIAL)
                .requiresModel(false)
                .attributes(new ArrayList<>(List.of(declared)))
                .build();
    }

    private static Map<String, Object> attributes(Object... keysAndValues) {
        Map<String, Object> map = new HashMap<>();
        for (int index = 0; index < keysAndValues.length; index += 2) {
            map.put((String) keysAndValues[index], keysAndValues[index + 1]);
        }
        return map;
    }

    private static CreateProductRequestDTO createRequest(
            UUID familyId, Map<String, Object> attributes) {
        return new CreateProductRequestDTO("SKU-1", "Pieza", familyId, attributes, null);
    }

    /**
     * El PATCH real no cambia atributos en esta prueba: `mapper.updateProductFromDTO`
     * es un mock sin efecto, asi que el producto conserva lo que se le puso al
     * construirlo y la regla se valida sobre ese estado.
     */
    private static UpdateProductRequestDTO updateRequest() {
        return new UpdateProductRequestDTO(null, null, null, null, null);
    }

}
