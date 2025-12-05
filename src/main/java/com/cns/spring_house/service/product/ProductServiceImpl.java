package com.cns.spring_house.service.product;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cns.spring_house.dto.product.CreateProductRequestDTO;
import com.cns.spring_house.dto.product.ProductResponseDTO;
import com.cns.spring_house.dto.product.SearchProductRequestDTO;
import com.cns.spring_house.dto.product.UpdateProductRequestDTO;
import com.cns.spring_house.exception.EntityAlreadyExistsException;
import com.cns.spring_house.exception.EntityNotFoundException;
import com.cns.spring_house.exception.ValidationException;
import com.cns.spring_house.mapper.product.ProductMapper;
import com.cns.spring_house.model.attribute.Attribute;
import com.cns.spring_house.model.family.Family;
import com.cns.spring_house.model.product.Product;
import com.cns.spring_house.repository.family.FamilyRepository;
import com.cns.spring_house.repository.product.ProductRepository;
import com.cns.spring_house.repository.product.ProductSpecs;

import lombok.RequiredArgsConstructor;

@Service
@Transactional
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {

    private final ProductRepository repository;
    private final ProductMapper mapper;
    private final FamilyRepository familyRepository;

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
        specs.add(
                Specification.anyOf(
                        ProductSpecs.skuContains(requestDTO.q()),
                        ProductSpecs.nameContains(requestDTO.q())));
        if (requestDTO.attributes() != null) {
            specs.addAll(buildSpecsByAttribute(requestDTO.attributes()));
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
        product = repository.save(product);
        return mapper.toResponse(product);
    }

    @Override
    public ProductResponseDTO update(UUID id, UpdateProductRequestDTO requestDTO) {
        Product product = repository
                .findById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el producto solicitado"));
        String sku = requestDTO.sku();
        if (sku != null && !sku.equals(product.getSku())) {
            if (repository.existsBySkuAndIdNot(sku, id)) {
                throw new EntityAlreadyExistsException(
                        "Ya existe un producto con ese sku");
            }
        }
        UUID familyId = requestDTO.family();
        if (familyId != null && !familyId.equals(product.getFamily().getId())) {
            Family family = familyRepository
                    .findById(requestDTO.family())
                    .orElseThrow(() -> new EntityNotFoundException(
                            "No existe la familia solicitada"));
            product.setFamily(family);
        }
        mapper.updateProductFromDTO(requestDTO, product);
        validateAttributes(product.getFamily(), product.getAttributes());
        product = repository.save(product);
        return mapper.toResponse(product);
    }

    private void validateAttributes(Family family, Map<String, Object> attributes) {
        Map<String, Attribute> allowedMap = family.getAttributes()
                .stream()
                .collect(Collectors.toMap(Attribute::getKey, Function.identity()));
        Map<String, String> errors = attributes.keySet()
                .stream()
                .filter(key -> !allowedMap.containsKey(key))
                .collect(Collectors.toMap(key -> key, key -> "Atributo no permitido"));
        allowedMap.forEach((key, attr) -> {
            Object val = attributes.get(key);
            if (val == null && attr.getRequired()) {
                errors.put(key, "Es obligatorio");
            } else if (!attr.getDataType().isValid(val)) {
                errors.put(key, "Tipo inválido, se espera un " + attr.getDataType());
            }
        });
        if (!errors.isEmpty()) {
            throw new ValidationException("Error de validación", errors);
        }
    }

    private List<Specification<Product>> buildSpecsByAttribute(Map<String, Object> attributes) {
        return attributes.entrySet()
                .stream()
                .map(entry -> ProductSpecs.hasAttribute(entry.getKey(), entry.getValue()))
                .toList();
    }

}
