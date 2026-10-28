package com.cns.spring_house.sale.repository.projection;

import java.util.UUID;

/** Solicitudes de un area en una venta, y cuantas estan cumplidas. */
public record SaleAreaProgressRow(

    UUID saleId,
    long total,
    long fulfilled

) {

}
