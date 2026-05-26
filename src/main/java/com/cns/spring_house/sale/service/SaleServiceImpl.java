package com.cns.spring_house.sale.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collector;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cns.spring_house.inventory.entity.Inventory;
import com.cns.spring_house.inventory.repository.InventoryItemRepository;
import com.cns.spring_house.inventory.repository.InventoryRepository;
import com.cns.spring_house.job.entity.Job;
import com.cns.spring_house.job.repository.JobRepository;
import com.cns.spring_house.sale.dto.CreateSaleDetailRequestDTO;
import com.cns.spring_house.sale.entity.Sale;
import com.cns.spring_house.sale.entity.SaleDetail;
import com.cns.spring_house.sale.repository.SaleRepository;
import com.cns.spring_house.shared.exception.ValidationException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional
public class SaleServiceImpl implements SaleService {
    
    private SaleRepository saleRepository;
    private InventoryRepository inventoryRepository;
    private InventoryItemRepository inventoryItemRepository;
    private JobRepository jobRepository;

    @Override
    public void create(CreateSaleDetailRequestDTO requestDTO) {
        
    }

    private void addNormalProducts(Sale sale, Set<CreateSaleDetailRequestDTO> normalProducts) {
        Set<UUID> ids = normalProducts
                .stream()
                .map(CreateSaleDetailRequestDTO::id)
                .collect(Collectors.toSet());
        Map<UUID, Inventory> inventories = inventoryRepository.findAllById(ids)
                .stream()
                .collect(Collectors.toMap(Inventory::getId, Function.identity()));
        normalProducts.forEach((product) -> {
            Inventory inventory = inventories.get(product.id());
            if (product.quantity() == null) {
                throw new ValidationException(
                        "La cantidad a remover del producto con id: "
                        + inventory.getProduct().getId() + " es obligatoria");    
            }
            int quantity = product.quantity();
            BigDecimal price = Optional.ofNullable(product.price())
                    .orElse(inventory.getPrice());
            inventory.removeStock(quantity);
            SaleDetail detail = SaleDetail.builder()
                    .product(inventory.getProduct())
                    .quantity(quantity)
                    .price(price)
                    .subTotal(price.multiply(BigDecimal.valueOf(quantity)))
                    .build();
            sale.addDetail(detail);
        });
    }

    private void addJobProducts(Sale sale, Set<CreateSaleDetailRequestDTO> jobProducts) {
        Set<UUID> ids = jobProducts
                .stream()
                .map(CreateSaleDetailRequestDTO::id)
                .collect(Collectors.toSet());
        UUID workshopId = sale.getWorkshop().getId();
        Map<UUID, Job> jobs = jobRepository.findAllByIdAndWorkshopId(ids, workshopId)
                .stream()
                .collect(Collectors.toMap(Job::getId, Function.identity()));
        jobProducts.forEach((product) -> {
            Job job = jobs.get(product.id());
            BigDecimal price = Optional.ofNullable(product.price())
                    .orElse(job.getPrice());
            SaleDetail detail = SaleDetail.builder()
                    .product(job.getProduct())
                    .quantity(1)
                    .subTotal(price)
                    .price(price)
                    .build();
            sale.addDetail(detail);
        });
    }

}
