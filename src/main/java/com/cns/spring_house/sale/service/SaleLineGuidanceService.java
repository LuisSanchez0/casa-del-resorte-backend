package com.cns.spring_house.sale.service;

import com.cns.spring_house.sale.dto.SaleLineGuidanceDTO;
import com.cns.spring_house.sale.dto.SaleLineGuidanceRequestDTO;

/**
 * Guia de la caja para una linea antes de agregarla: estado del precio con
 * referencias reales y su procedencia, y unidades terminadas libres.
 *
 * Solo lee. No fija precios, no reserva stock y no crea productos: lo que
 * cuenta se decide de nuevo, bajo cerrojo, al agregar la linea.
 */
public interface SaleLineGuidanceService {

    SaleLineGuidanceDTO guide(SaleLineGuidanceRequestDTO requestDTO);

}
