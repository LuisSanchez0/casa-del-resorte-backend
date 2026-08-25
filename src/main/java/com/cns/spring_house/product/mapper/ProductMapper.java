package com.cns.spring_house.product.mapper;

import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;

import com.cns.spring_house.family.entity.Family;
import com.cns.spring_house.product.dto.CreateProductRequestDTO;
import com.cns.spring_house.product.dto.ProductFamilyResponseDTO;
import com.cns.spring_house.product.dto.ProductResponseDTO;
import com.cns.spring_house.product.dto.UpdateProductRequestDTO;
import com.cns.spring_house.product.entity.Product;

@Mapper(componentModel = "spring")
public interface ProductMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "family", ignore = true)
    @Mapping(target = "model", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    Product toProduct(CreateProductRequestDTO requestDTO);

    @BeanMapping(
            nullValuePropertyMappingStrategy =
                    NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "family", ignore = true)
    @Mapping(target = "model", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    void updateProductFromDTO(
            UpdateProductRequestDTO requestDTO,
            @MappingTarget Product product);

    ProductResponseDTO toResponse(Product product);

    ProductFamilyResponseDTO toFamilyResponse(Family family);
}