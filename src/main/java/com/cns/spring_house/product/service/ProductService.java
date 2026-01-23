package com.cns.spring_house.product.service;

import java.util.List;
import java.util.UUID;

import com.cns.spring_house.product.dto.CreateProductRequestDTO;
import com.cns.spring_house.product.dto.ProductResponseDTO;
import com.cns.spring_house.product.dto.SearchProductRequestDTO;
import com.cns.spring_house.product.dto.UpdateProductRequestDTO;

public interface ProductService {
    
    ProductResponseDTO get(UUID id);
    List<ProductResponseDTO> getAll(SearchProductRequestDTO requestDTO);
    ProductResponseDTO create(CreateProductRequestDTO requestDTO);
    ProductResponseDTO update(UUID id, UpdateProductRequestDTO requestDTO);

}
