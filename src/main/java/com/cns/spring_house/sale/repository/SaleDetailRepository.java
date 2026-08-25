package com.cns.spring_house.sale.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.cns.spring_house.sale.entity.SaleDetail;

public interface SaleDetailRepository extends JpaRepository<SaleDetail, UUID> {

    /** El detalle solo es alcanzable si su venta pertenece al taller del token. */
    Optional<SaleDetail> findByIdAndSaleWorkshopId(UUID id, UUID workshopId);

    /**
     * Un WorkResult solo puede reservarse en un detalle. La base ya lo impide con
     * uk_sale_detail_work_result (V15); comprobarlo antes permite responder un
     * error de negocio en lugar de una violacion de integridad.
     */
    boolean existsByWorkResultId(UUID workResultId);

}
