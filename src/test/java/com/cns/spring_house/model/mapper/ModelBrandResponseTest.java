package com.cns.spring_house.model.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.cns.spring_house.brand.entity.Brand;
import com.cns.spring_house.family.entity.Family;
import com.cns.spring_house.model.dto.ModelResponseDTO;
import com.cns.spring_house.model.entity.Model;
import com.cns.spring_house.product.dto.ProductResponseDTO;
import com.cns.spring_house.product.entity.Product;
import com.cns.spring_house.product.mapper.ProductMapper;
import com.cns.spring_house.product.mapper.ProductMapperImpl;

/**
 * La marca del modelo viaja en la respuesta.
 *
 * El defecto que fija esta prueba: `Model.brand` existe en el dominio desde
 * siempre —es un `@ManyToOne` de la entidad— pero NINGUNA respuesta la exponia.
 * `brand` solo aparecia como filtro de busqueda, asi que se podia preguntar por
 * una marca y nunca leer la del modelo devuelto. Quien recibia un producto tenia
 * su modelo y no podia saber de que marca era.
 *
 * Se comprueba en los dos caminos que de verdad usa el cliente: el modelo suelto
 * y el modelo anidado dentro de un producto, que es como lo recibe la caja.
 */
class ModelBrandResponseTest {

    private final ModelMapper modelMapper = new ModelMapperImpl();
    private final ProductMapper productMapper = new ProductMapperImpl();

    private final Brand toyota = Brand.builder()
            .id(UUID.randomUUID())
            .name("Toyota")
            .logoKey("brands/toyota.png")
            .build();

    private Model model(Brand brand) {
        return Model.builder()
                .id(UUID.randomUUID())
                .name("HILUX")
                .yearFrom(2020)
                .yearTo(2025)
                .brand(brand)
                .build();
    }

    private Product product(Model model) {
        return Product.builder()
                .id(UUID.randomUUID())
                .sku("SKU-1")
                .name("RESORTE DELANTERO")
                .family(Family.builder().id(UUID.randomUUID()).name("RESORTE").build())
                .model(model)
                .attributes(Map.of())
                .build();
    }

    @Test
    void aModelReportsTheBrandItBelongsTo() {
        ModelResponseDTO response = modelMapper.toResponse(model(toyota));

        assertThat(response.brand()).isNotNull();
        assertThat(response.brand().name()).isEqualTo("Toyota");
        assertThat(response.brand().id()).isEqualTo(toyota.getId());
        assertThat(response.brand().logoKey()).isEqualTo("brands/toyota.png");
    }

    /**
     * El camino que de verdad usa la caja: el producto trae su modelo, y el
     * modelo su marca, en la MISMA respuesta. Sin esto haria falta una segunda
     * consulta por cada producto solo para saber la marca.
     */
    @Test
    void aProductCarriesTheBrandThroughItsModel() {
        ProductResponseDTO response = productMapper.toResponse(product(model(toyota)));

        assertThat(response.model()).isNotNull();
        assertThat(response.model().brand()).isNotNull();
        assertThat(response.model().brand().name()).isEqualTo("Toyota");
        // Y lo que ya viajaba sigue viajando igual.
        assertThat(response.model().name()).isEqualTo("HILUX");
        assertThat(response.model().yearFrom()).isEqualTo(2020);
        assertThat(response.model().yearTo()).isEqualTo(2025);
        assertThat(response.family().name()).isEqualTo("RESORTE");
    }

    /** Un modelo sin marca sigue respondiendo como siempre, con `brand` en null. */
    @Test
    void aModelWithoutBrandStaysBackwardCompatible() {
        ModelResponseDTO response = modelMapper.toResponse(model(null));

        assertThat(response.brand()).isNull();
        assertThat(response.name()).isEqualTo("HILUX");
    }

    /** Un producto sin modelo tampoco cambia: no hay marca que colgar de nada. */
    @Test
    void aProductWithoutModelStaysBackwardCompatible() {
        ProductResponseDTO response = productMapper.toResponse(product(null));

        assertThat(response.model()).isNull();
        assertThat(response.name()).isEqualTo("RESORTE DELANTERO");
    }
}
