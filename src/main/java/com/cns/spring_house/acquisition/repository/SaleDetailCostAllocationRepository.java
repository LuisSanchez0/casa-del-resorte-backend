package com.cns.spring_house.acquisition.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.cns.spring_house.acquisition.entity.SaleDetailCostAllocation;

public interface SaleDetailCostAllocationRepository
        extends JpaRepository<SaleDetailCostAllocation, UUID> {

    /**
     * Las asignaciones de una linea, con su lote ya resuelto en el MISMO viaje.
     *
     * El `JOIN FETCH` no es opcional: devolver el stock recorre estas filas y
     * toca el lote de cada una, y con la relacion perezosa una linea repartida
     * entre cinco lotes disparaba cinco consultas extra. Aqui son cero.
     */
    @Query("""
        SELECT allocation
        FROM SaleDetailCostAllocation allocation
        JOIN FETCH allocation.acquisition
        WHERE allocation.saleDetail.id = :saleDetailId
    """)
    List<SaleDetailCostAllocation> findBySaleDetailId(
            @Param("saleDetailId") UUID saleDetailId);

    void deleteBySaleDetailId(UUID saleDetailId);

}
