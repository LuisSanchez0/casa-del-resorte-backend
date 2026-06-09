package com.cns.spring_house.sale.handler;

import java.math.BigDecimal;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.cns.spring_house.family.entity.FamilyCategory;
import com.cns.spring_house.job.entity.Job;
import com.cns.spring_house.job.repository.JobRepository;
import com.cns.spring_house.sale.dto.AddSaleDetailRequestDTO;
import com.cns.spring_house.sale.entity.Sale;
import com.cns.spring_house.sale.entity.SaleDetail;
import com.cns.spring_house.shared.exception.EntityNotFoundException;
import com.cns.spring_house.shared.exception.ValidationException;
import com.cns.spring_house.shared.security.SecurityUtils;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class JobSaleDetailHandler implements SaleDetailHandler {
    
    private final JobRepository repository;

    @Override
    public FamilyCategory getType() {
        return FamilyCategory.JOB;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void addDetail(Sale sale, AddSaleDetailRequestDTO requestDTO) {
        Job job = repository
                .findByIdAndWorkshopId(
                        requestDTO.product(), SecurityUtils.getWorkshopId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el servicio especificado"));
        if (requestDTO.quantity() == null) {
            throw new ValidationException("La cantidad es obligatoria");
        }
        SaleDetail detail = createDetail(job, requestDTO);
        sale.addDetail(detail);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void removeDetail(Sale sale, SaleDetail saleDetail) {
        sale.removeDetail(saleDetail);
    }

    private SaleDetail createDetail(Job job, AddSaleDetailRequestDTO requestDTO) {
        BigDecimal price = Optional
                .ofNullable(requestDTO.price())
                .orElse(job.getPrice());
        BigDecimal subTotal = price.multiply(
                BigDecimal.valueOf(requestDTO.quantity()));
        return SaleDetail.builder()
                .quantity(requestDTO.quantity())
                .price(price)
                .product(job.getProduct())
                .subTotal(subTotal)
                .build();
    }

}
