package com.cns.spring_house.family.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.cns.spring_house.family.dto.FamilyResponseDTO;
import com.cns.spring_house.family.entity.Family;
import com.cns.spring_house.family.entity.FamilyCategory;
import com.cns.spring_house.family.mapper.AttributeMapper;
import com.cns.spring_house.family.mapper.FamilyMapper;
import com.cns.spring_house.family.repository.FamilyRepository;
import com.cns.spring_house.shared.exception.EntityNotFoundException;

/**
 * `GET /v1/families/{id}/transformation-targets`, el lado del servicio.
 *
 * Es lo que alimenta al selector del frontend, y existe para que el cliente no
 * tenga que conocer ninguna familia por su nombre ni por su codigo. Lo que se
 * fija aqui es que la lista sale de la MISMA regla que autoriza el POST y que se
 * resuelve con UNA consulta al catalogo, no con una por destino.
 */
@ExtendWith(MockitoExtension.class)
class FamilyTransformationTargetsTest {

    @Mock
    private FamilyRepository repository;

    @Mock
    private FamilyMapper mapper;

    @Mock
    private AttributeMapper attributeMapper;

    @InjectMocks
    private FamilyServiceImpl service;

    private final Family materialLiso = family("ML", "MATERIAL LISO");
    private final Family hojaResorte = family("HR", "HOJA DE RESORTE");
    private final Family maleta = family("MAL", "MALETA");

    @BeforeEach
    void setUpMapper() {
        lenient().when(mapper.toResponse(materialLiso)).thenReturn(response(materialLiso));
        lenient().when(mapper.toResponse(hojaResorte)).thenReturn(response(hojaResorte));
        lenient().when(mapper.toResponse(maleta)).thenReturn(response(maleta));
    }

    @Test
    void materialLisoOffersItsOwnFamilyAndTheLeafSpring() {
        when(repository.findById(materialLiso.getId()))
                .thenReturn(Optional.of(materialLiso));
        when(repository.findAll())
                .thenReturn(List.of(materialLiso, hojaResorte, maleta));

        List<FamilyResponseDTO> targets =
                service.getTransformationTargets(materialLiso.getId());

        assertThat(targets)
                .extracting(FamilyResponseDTO::code)
                .containsExactly("ML", "HR");
    }

    /** El sentido contrario no se habilita por el hecho de existir el de ida. */
    @Test
    void aLeafSpringOffersOnlyItsOwnFamily() {
        when(repository.findById(hojaResorte.getId()))
                .thenReturn(Optional.of(hojaResorte));
        when(repository.findAll())
                .thenReturn(List.of(materialLiso, hojaResorte, maleta));

        List<FamilyResponseDTO> targets =
                service.getTransformationTargets(hojaResorte.getId());

        assertThat(targets)
                .extracting(FamilyResponseDTO::code)
                .containsExactly("HR");
    }

    /** Una consulta al catalogo, no una por destino. */
    @Test
    void theCatalogIsReadOnlyOnce() {
        when(repository.findById(materialLiso.getId()))
                .thenReturn(Optional.of(materialLiso));
        when(repository.findAll())
                .thenReturn(List.of(materialLiso, hojaResorte, maleta));

        service.getTransformationTargets(materialLiso.getId());

        verify(repository).findAll();
    }

    @Test
    void anUnknownFamilyIsReportedAsMissing() {
        UUID unknown = UUID.randomUUID();
        when(repository.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getTransformationTargets(unknown))
                .isInstanceOf(EntityNotFoundException.class);

        verify(repository, never()).findAll();
    }

    private static Family family(String code, String name) {
        return Family.builder()
                .id(UUID.randomUUID())
                .code(code)
                .name(name)
                .requiresModel(false)
                .category(FamilyCategory.SPECIAL)
                .attributes(new ArrayList<>())
                .build();
    }

    private static FamilyResponseDTO response(Family family) {
        return new FamilyResponseDTO(
                family.getId(),
                family.getCode(),
                family.getName(),
                family.getRequiresModel(),
                family.getCategory().name(),
                List.of());
    }

}
