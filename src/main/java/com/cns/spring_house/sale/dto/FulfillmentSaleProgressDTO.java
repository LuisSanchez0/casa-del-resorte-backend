package com.cns.spring_house.sale.dto;

/**
 * Solicitudes de una venta que le tocan a un area —Control o el Operador— ya
 * enviadas al taller y no anuladas, y cuantas de ellas estan cumplidas.
 *
 * Es del area y no de la venta entera a proposito: cada cola dice cuanto le
 * falta a ELLA. Una venta con una entrega de Control y dos piezas por fabricar
 * es "0 de 1" para Control y "0 de 2" para el Operador, nunca "0 de 3" en las
 * dos.
 */
public record FulfillmentSaleProgressDTO(

    int total,
    int fulfilled

) {

}
