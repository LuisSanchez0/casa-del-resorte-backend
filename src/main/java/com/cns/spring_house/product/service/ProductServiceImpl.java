package com.cns.spring_house.product.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
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
import com.cns.spring_house.shared.exception.ValidationException;
import com.cns.spring_house.product.mapper.ProductMapper;
import com.cns.spring_house.attribute.entity.Attribute;
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
        specs.add(ProductSpecs.hasModel(requestDTO.model()));
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
        updateSkuIfNeeded(product, requestDTO.sku(), id);
        updateFamilyIfNeeded(product, requestDTO.family());
        updateModelIfNeeded(product, requestDTO.model());
        mapper.updateProductFromDTO(requestDTO, product);
        validateAttributes(product.getFamily(), product.getAttributes());
        Product saved = repository.save(product);
        return mapper.toResponse(saved);
    }

    private void updateSkuIfNeeded(Product product, String sku, UUID productId) {
        if (sku == null || sku.equals(product.getSku())) {
            return;
        }
        if (repository.existsBySkuAndIdNot(sku, productId)) {
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
        Map<String, Attribute> allowed = family.getAttributes()
                .stream()
                .collect(Collectors.toMap(Attribute::getKey, Function.identity()));
        Map<String, String> errors = new HashMap<>();
        attributes.forEach((key, value) -> {
            if (!allowed.containsKey(key)) {
                errors.put(key, "Atributo no permitido");
            }
        });
        allowed.forEach((key, attr) -> {
            Object value = attributes.get(key);
            if (value == null) {
                if (attr.getRequired()) {
                    errors.put(key, "Es obligatorio");
                }
            } else if (!attr.getDataType().isValid(value)) {
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
