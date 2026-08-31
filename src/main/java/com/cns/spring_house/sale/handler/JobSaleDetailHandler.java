package com.cns.spring_house.sale.handler;

import java.math.BigDecimal;

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

    /**
     * `requestDTO.product()` es Product.id, no Job.id. Antes se buscaba el Job por
     * ese UUID mientras el servicio lo resolvia como producto, de modo que ningun
     * valor podia satisfacer a los dos y la venta de servicios nunca completaba.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public SaleDetail addDetail(Sale sale, AddSaleDetailRequestDTO requestDTO) {
        // Con cerrojo: el precio del servicio puede fijarse en esta misma venta
        // (ver `createDetail`), asi que la fila se lee bloqueada desde el
        // principio, igual que hace el inventario con sus existencias.
        Job job = repository
                .findByProductIdAndWorkshopIdForUpdate(
                        requestDTO.product(), SecurityUtils.getWorkshopId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el servicio especificado"));
        if (requestDTO.quantity() == null) {
            throw new ValidationException("La cantidad es obligatoria");
        }
        SaleDetail detail = createDetail(job, requestDTO);
        sale.addDetail(detail);
        return detail;
    }

    /** Un servicio no reserva inventario: no hay nada que devolver. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void restore(SaleDetail saleDetail) {
        // Intencionadamente vacio.
    }

    /**
     * Un servicio no tiene costo de adquisicion en el modelo: `Job.price` es
     * precio de catalogo, no costo, y no existe ninguna entidad que registre el
     * costo real de ejecutarlo. Por eso se pasa null como costo y solo se impide
     * el precio negativo; confundir catalogo con costo bloquearia descuentos
     * comerciales legitimos sobre mano de obra.
     */
    private SaleDetail createDetail(Job job, AddSaleDetailRequestDTO requestDTO) {
        PricingOutcome pricing = resolvePricing(
                requestDTO.price(), job.getPrice(), null);
        BigDecimal price = pricing.price();

        // `job.price` es el precio de catalogo del servicio y tiene el MISMO
        // esquema que el del inventario —`NUMERIC(10,2) NOT NULL CHECK (>= 0)`,
        // sin nulo posible—, asi que el cero significa aqui lo mismo: sin precio
        // asignado. Un taller no regala mano de obra. El servicio esta
        // bloqueado y gestionado, de modo que el precio queda en la misma
        // transaccion que la linea.
        if (pricing.becomesCatalogPrice()) {
            job.setPrice(pricing.price());
        }

        BigDecimal subTotal = price.multiply(
                BigDecimal.valueOf(requestDTO.quantity()));
        return SaleDetail.builder()
                .quantity(requestDTO.quantity())
                .price(price)
                .product(job.getProduct())
                .subTotal(subTotal)
                .invoiceDescription(requestDTO.invoiceDescription())
                .build();
    }

}
