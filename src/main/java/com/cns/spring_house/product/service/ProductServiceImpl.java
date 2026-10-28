package com.cns.spring_house.product.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cns.spring_house.product.dto.CreateProductRequestDTO;
import com.cns.spring_house.product.dto.ProductResponseDTO;
import com.cns.spring_house.product.dto.SearchProductRequestDTO;
import com.cns.spring_house.product.dto.UpdateProductRequestDTO;
import com.cns.spring_house.shared.exception.EntityAlreadyExistsException;
import com.cns.spring_house.shared.exception.EntityNotFoundException;
import com.cns.spring_house.product.mapper.ProductMapper;
import com.cns.spring_house.family.entity.Attribute;
import com.cns.spring_house.family.entity.AttributeDataType;
import com.cns.spring_house.family.entity.Family;
import com.cns.spring_house.model.entity.Model;
import com.cns.spring_house.product.entity.Product;
import com.cns.spring_house.family.repository.FamilyRepository;
import com.cns.spring_house.model.repository.ModelRepository;
import com.cns.spring_house.product.repository.ProductRepository;
import com.cns.spring_house.product.repository.ProductSpecs;

import lombok.RequiredArgsConstructor;

@Service
@Transactional
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {

    private final ProductRepository repository;
    private final ProductMapper mapper;
    private final FamilyRepository familyRepository;
    private final ModelRepository modelRepository;

    @Override
    public ProductResponseDTO get(UUID id) {
        Product product = repository
                .findById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el producto solicitado"));
        return mapper.toResponse(product);
    }

    @Override
    public List<ProductResponseDTO> getAll(SearchProductRequestDTO requestDTO) {
        List<Specification<Product>> specs = new ArrayList<>();
        specs.add(ProductSpecs.hasFamily(requestDTO.family()));
        // Marca y modelo son criterios INDEPENDIENTES, no una secuencia: se
        // puede buscar solo por marca (todas las piezas de sus modelos), solo
        // por modelo, o por los dos a la vez. Cada uno se ignora solo cuando no
        // viene, de modo que el orden en que la caja los ponga da igual.
        specs.add(ProductSpecs.hasBrand(requestDTO.brand()));
        specs.add(ProductSpecs.hasModel(requestDTO.model()));
        specs.add(
                Specification.anyOf(
                        ProductSpecs.skuContains(requestDTO.q()),
                        ProductSpecs.nameContains(requestDTO.q())));
        if (requestDTO.attributes() != null) {
            specs.addAll(buildSpecsByAttribute(
                    requestDTO.attributes(), requestDTO.family()));
        }
        Specification<Product> finalSpec = Specification.allOf(specs);
        return repository.findAll(finalSpec)
                .stream()
                .map(mapper::toResponse)
                .toList();
    }

    @Override
    public ProductResponseDTO create(CreateProductRequestDTO requestDTO) {
        if (repository.existsBySku(requestDTO.sku())) {
            throw new EntityAlreadyExistsException(
                    "Ya existe un producto con ese sku");
        }
        Family family = familyRepository
                .findById(requestDTO.family())
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe la familia especificada"));
        validateAttributes(family, requestDTO.attributes());
        Product product = mapper.toProduct(requestDTO);
        product.setFamily(family);
        if (product.getFamily().getRequiresModel()) {
            Model model = modelRepository
                    .findById(requestDTO.model())
                    .orElseThrow(() -> new EntityNotFoundException(
                            "No existe el modelo especificado"));
            product.setModel(model);
        }
        product = repository.save(product);
        return mapper.toResponse(product);
    }

    @Override
    public ProductResponseDTO update(UUID id, UpdateProductRequestDTO requestDTO) {
        Product product = repository
                .findById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el producto solicitado"));
        updateSkuIfNeeded(product, requestDTO.sku());
        updateFamilyIfNeeded(product, requestDTO.family());
        updateModelIfNeeded(product, requestDTO.model());
        mapper.updateProductFromDTO(requestDTO, product);
        validateAttributes(product.getFamily(), product.getAttributes());
        Product saved = repository.save(product);
        return mapper.toResponse(saved);
    }

    private void updateSkuIfNeeded(Product product, String sku) {
        if (sku == null || sku.equals(product.getSku())) {
            return;
        }
        if (repository.existsBySkuAndIdNot(sku, product.getId())) {
            throw new EntityAlreadyExistsException(
                    "Ya existe un producto con ese sku");
        }
        product.setSku(sku);
    }

    private void updateFamilyIfNeeded(Product product, UUID familyId) {
        if (familyId == null || familyId.equals(product.getFamily().getId())) {
            return;
        }
        Family family = familyRepository.findById(familyId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe la familia solicitada"));
        product.setFamily(family);
    }

    private void updateModelIfNeeded(Product product, UUID modelId) {
        Model currentModel = product.getModel();
        if (!product.getFamily().getRequiresModel()) {
            product.setModel(null);
            return;
        }
        if (modelId == null) {
            return;
        }
        if (currentModel != null && modelId.equals(currentModel.getId())) {
            return;
        }
        Model model = modelRepository.findById(modelId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el modelo especificado"));
        product.setModel(model);
    }

    private void validateAttributes(Family family, Map<String, Object> attributes) {
        ProductAttributesValidator.validate(family, attributes);
    }

    /**
     * Un filtro por caracteristica, comparado como lo que la familia dice que
     * ES.
     *
     * El tipo importa porque el JSONB no serializa los numeros de forma
     * uniforme: la misma medida esta guardada como `15.0` en unas piezas y como
     * `200` en otras, segun por que camino se dieron de alta. Comparando texto,
     * buscar largo=15 no encontraba la hoja de largo 15; comparando numeros,
     * `15.0 = 15` es cierto y el filtro significa lo que aparenta.
     *
     * La conversion a numero se aplica UNICAMENTE a los atributos que la familia
     * declara NUMBER, y ahi es segura porque {@link #validateAttributes} impide
     * guardar otra cosa. Sin familia en la busqueda no hay nada que consultar
     * sobre los tipos, asi que se compara como texto: es el comportamiento de
     * siempre y no puede hacer fallar la consulta.
     */
    private List<Specification<Product>> buildSpecsByAttribute(
            Map<String, Object> attributes, UUID familyId) {

        Map<String, AttributeDataType> declared = declaredTypes(familyId);

        return attributes.entrySet()
                .stream()
                .map(entry -> {
                    BigDecimal number = declared.get(entry.getKey()) == AttributeDataType.NUMBER
                            ? parseNumber(entry.getValue())
                            : null;

                    return number == null
                            ? ProductSpecs.hasAttribute(entry.getKey(), entry.getValue())
                            : ProductSpecs.hasNumericAttribute(entry.getKey(), number);
                })
                .toList();
    }

    /** Tipos declarados por la familia, o vacio cuando la busqueda no la acota. */
    private Map<String, AttributeDataType> declaredTypes(UUID familyId) {
        if (familyId == null) {
            return Map.of();
        }

        return familyRepository.findById(familyId)
                .map(Family::getAttributes)
                .orElseGet(List::of)
                .stream()
                .collect(Collectors.toMap(
                        Attribute::getName, Attribute::getDataType));
    }

    /** El valor como numero, o null si el texto recibido no lo es. */
    private static BigDecimal parseNumber(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(value).trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

}
