package com.cns.spring_house.service.stockitem;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cns.spring_house.dto.stockitem.CreateStockItemRequestDTO;
import com.cns.spring_house.dto.stockitem.SearchStockItemRequestDTO;
import com.cns.spring_house.dto.stockitem.StockItemResponseDTO;
import com.cns.spring_house.dto.stockitem.UpdateStockItemRequestDTO;
import com.cns.spring_house.exception.EntityAlreadyExistsException;
import com.cns.spring_house.exception.EntityNotFoundException;
import com.cns.spring_house.mapper.stockitem.StockItemMapper;
import com.cns.spring_house.model.product.Product;
import com.cns.spring_house.model.stockitem.StockItem;
import com.cns.spring_house.model.workshop.Workshop;
import com.cns.spring_house.repository.product.ProductRepository;
import com.cns.spring_house.repository.stockitem.StockItemRepository;
import com.cns.spring_house.repository.stockitem.StockItemSpecs;
import com.cns.spring_house.repository.workshop.WorkshopRespository;

import lombok.RequiredArgsConstructor;

@Service
@Transactional
@RequiredArgsConstructor
public class StockItemServiceImp implements StockItemService {
    
    private final StockItemRepository repository;
    private final WorkshopRespository workshopRespository;
    private final ProductRepository productRepository;
    private final StockItemMapper mapper;

    @Override
    public StockItemResponseDTO get(UUID id) {
        StockItem stockItem = repository
                .findById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el stock solicitado"));
        return mapper.toResponse(stockItem);
    }

    @Override
    public List<StockItemResponseDTO> getAll(SearchStockItemRequestDTO requestDTO) {
        Specification<StockItem> specs = Specification.allOf(
                StockItemSpecs.hasProduct(requestDTO.product()),
                StockItemSpecs.hasWorkshop(requestDTO.workshop()));
        return repository.findAll(specs)
                .stream()
                .map(mapper::toResponse)
                .toList();
    }

    @Override
    public StockItemResponseDTO create(CreateStockItemRequestDTO requestDTO) {
        boolean alreadyExists = repository.existsByProductIdAndWorkshopId(
                requestDTO.product(), requestDTO.workshop());
        if (alreadyExists) {
            throw new EntityAlreadyExistsException(
                    "Ya existe un stock para ese producto en ese taller");
        }
        Workshop workshop = workshopRespository
                .findById(requestDTO.workshop())
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el taller especificado"));
        Product product = productRepository
                .findById(requestDTO.product())
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el producto especificado"));
        StockItem stockItem = mapper.toStockItem(requestDTO);
        stockItem.setWorkshop(workshop);
        stockItem.setProduct(product);
        stockItem = repository.save(stockItem);
        return mapper.toResponse(stockItem);
    }

    @Override
    public StockItemResponseDTO update(UUID id, UpdateStockItemRequestDTO requestDTO) {
        StockItem stockItem = repository
                .findById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el stock especificado"));
        UUID workshopId = requestDTO.workshop();
        if (workshopId != null && !workshopId.equals(stockItem.getWorkshop().getId())) {
            Workshop workshop = workshopRespository
                .findById(requestDTO.workshop())
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el taller especificado"));
            stockItem.setWorkshop(workshop);
        }
        UUID productId = requestDTO.product();
        if (productId != null && !productId.equals(stockItem.getProduct().getId())) {
            Product product = productRepository
                .findById(requestDTO.product())
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el producto especificado"));
            stockItem.setProduct(product);
        }
        boolean alreadyExists = repository.existsByProductIdAndWorkshopIdAndIdNot(
                stockItem.getProduct().getId(),
                stockItem.getWorkshop().getId(),
                id);
        if (alreadyExists) {
            throw new EntityAlreadyExistsException(
                    "Ya existe un stock para ese producto en ese taller");
        }
        mapper.updateStockItemFromDTO(requestDTO, stockItem);
        stockItem = repository.save(stockItem);
        return mapper.toResponse(stockItem);
    }

}
