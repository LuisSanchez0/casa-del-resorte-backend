package com.cns.spring_house.service.product;

import java.util.List;
import java.util.UUID;

import com.cns.spring_house.dto.product.CreateProductRequestDTO;
import com.cns.spring_house.dto.product.ProductResponseDTO;
import com.cns.spring_house.dto.product.SearchProductRequestDTO;
import com.cns.spring_house.dto.product.UpdateProductRequestDTO;

public interface ProductService {
    
    ProductResponseDTO get(UUID id);
    List<ProductResponseDTO> getAll(SearchProductRequestDTO requestDTO);
    ProductResponseDTO create(CreateProductRequestDTO requestDTO);
    ProductResponseDTO update(UUID id, UpdateProductRequestDTO requestDTO);

}
