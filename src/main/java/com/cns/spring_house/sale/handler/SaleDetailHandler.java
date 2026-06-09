package com.cns.spring_house.sale.handler;

import com.cns.spring_house.family.entity.FamilyCategory;
import com.cns.spring_house.sale.dto.AddSaleDetailRequestDTO;
import com.cns.spring_house.sale.entity.Sale;
import com.cns.spring_house.sale.entity.SaleDetail;

public interface SaleDetailHandler {
    
    FamilyCategory getType();

    void addDetail(Sale sale, AddSaleDetailRequestDTO requestDTO);

    void removeDetail(Sale sale, SaleDetail saleDetail);

}
