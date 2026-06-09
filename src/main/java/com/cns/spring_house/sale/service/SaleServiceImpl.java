package com.cns.spring_house.sale.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cns.spring_house.customer.entity.Customer;
import com.cns.spring_house.customer.repository.CustomerRepository;
import com.cns.spring_house.employee.entity.Employee;
import com.cns.spring_house.employee.repository.EmployeeRepository;
import com.cns.spring_house.family.entity.FamilyCategory;
import com.cns.spring_house.product.entity.Product;
import com.cns.spring_house.product.repository.ProductRepository;
import com.cns.spring_house.sale.dto.AddSaleDetailRequestDTO;
import com.cns.spring_house.sale.dto.CreateSaleRequestDTO;
import com.cns.spring_house.sale.dto.SaleResponseDTO;
import com.cns.spring_house.sale.dto.SearchSaleRequestDTO;
import com.cns.spring_house.sale.entity.Sale;
import com.cns.spring_house.sale.entity.SaleDetail;
import com.cns.spring_house.sale.entity.SaleStatus;
import com.cns.spring_house.sale.handler.SaleDetailHandler;
import com.cns.spring_house.sale.handler.SaleDetailHandlerRegistry;
import com.cns.spring_house.sale.mapper.SaleMapper;
import com.cns.spring_house.sale.repository.SaleDetailRepository;
import com.cns.spring_house.sale.repository.SaleRepository;
import com.cns.spring_house.sale.repository.SaleSpecs;
import com.cns.spring_house.shared.exception.EntityNotFoundException;
import com.cns.spring_house.shared.exception.ValidationException;
import com.cns.spring_house.shared.security.SecurityUtils;
import com.cns.spring_house.workshop.entity.Workshop;
import com.cns.spring_house.workshop.repository.WorkshopRepository;

import lombok.RequiredArgsConstructor;

@Service
@Transactional
@RequiredArgsConstructor
public class SaleServiceImpl implements SaleService {
    
    private final SaleRepository repository;
    private final SaleMapper mapper;
    private final SaleDetailHandlerRegistry registry;
    private final WorkshopRepository workshopRepository;
    private final EmployeeRepository employeeRepository;
    private final CustomerRepository customerRepository;
    private final ProductRepository productRepository;
    private final SaleDetailRepository detailRepository;

    @Override
    public List<SaleResponseDTO> getAll(SearchSaleRequestDTO requestDTO) {
        Specification<Sale> specs = Specification.allOf(
                Specification.anyOf(
                        SaleSpecs.nameContains(requestDTO.customer()),
                        SaleSpecs.nitContains(requestDTO.customer())),
                SaleSpecs.hasStatus(requestDTO.status()),
                SaleSpecs.hasWorkshop(requestDTO.workshop()),
                SaleSpecs.hasEmployee(requestDTO.employee()),
                SaleSpecs.totalBetween(requestDTO.minTotal(), requestDTO.maxTotal()),
                SaleSpecs.completedAtBetween(requestDTO.minDate(), requestDTO.maxDate()));
        List<SaleResponseDTO> sales = repository.findAll(specs).stream()
                .map(mapper::toResponse)
                .toList();
        return sales;
    }

    @Override
    public SaleResponseDTO get(UUID id) {
        Sale sale = repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe la venta especificada"));
        return mapper.toResponse(sale);
    }

    @Override
    public SaleResponseDTO create(CreateSaleRequestDTO requestDTO) {
        Workshop workshop = workshopRepository.findById(SecurityUtils.getWorkshopId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el taller especificado"));
        Employee employee = employeeRepository.findById(SecurityUtils.getId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el empleado solicitado"));
        Customer customer = customerRepository.findById(requestDTO.customer())
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el cliente especificado"));
        Sale sale = Sale.builder()
                .workshop(workshop)
                .employee(employee)
                .customer(customer)
                .build();
        sale = repository.save(sale);
        return mapper.toResponse(sale);
    }

    @Override
    public SaleResponseDTO addDetail(UUID id, AddSaleDetailRequestDTO requestDTO) {
        Sale sale = repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe la venta especificada"));
        if (sale.getStatus() != SaleStatus.PENDING) {
            throw new ValidationException("La venta no se encuentra abierta");
        }
        Product product = productRepository.findById(requestDTO.product())
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el producto especificado"));
        FamilyCategory category = product.getFamily().getCategory();
        SaleDetailHandler handler = registry.get(category);
        handler.addDetail(sale, requestDTO);
        sale = repository.save(sale);
        return mapper.toResponse(sale);
    }

    @Override
    public SaleResponseDTO removeDetail(UUID saleDetailId) {
        SaleDetail saleDetail = detailRepository.findById(saleDetailId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el detalle especificado"));
        Sale sale = saleDetail.getSale();
        if (sale.getStatus() != SaleStatus.PENDING) {
            throw new ValidationException("La venta no se encuentra abierta");
        }
        FamilyCategory category = saleDetail.getProduct().getFamily().getCategory();
        SaleDetailHandler handler = registry.get(category);
        handler.removeDetail(sale, saleDetail);
        sale = repository.save(sale);
        return mapper.toResponse(sale);
    }

    @Override
    public SaleResponseDTO completeSale(UUID id) {
        Sale sale = repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe la venta especificada"));
        if (sale.getStatus() != SaleStatus.PENDING) {
            throw new ValidationException("La venta no se encuentra abierta");
        }
        sale.setCompletedAt(LocalDateTime.now());
        sale.setStatus(SaleStatus.COMPLETED);
        sale = repository.save(sale);
        return mapper.toResponse(sale);
    }

    @Override
    public void delete(UUID id) {
        Sale sale = repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe la venta especificada"));
        repository.delete(sale);
    }

}
