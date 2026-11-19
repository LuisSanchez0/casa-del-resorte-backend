package com.cns.spring_house.sale.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Guia de la caja para una linea que todavia no se agrega: estado del precio,
 * referencias con su procedencia y disponibilidad.
 *
 * Todo es de solo lectura y se recalcula al agregar: el precio y el stock que
 * valen son los que el servidor lee bajo cerrojo en ese momento.
 *
 * @param priceStatus         ver {@link PriceStatus}
 * @param catalogPrice        precio de catalogo vigente del taller; null sin el
 * @param becomesCatalogPrice el precio que confirme el vendedor quedara ademas
 *                            como precio de catalogo del taller (la pieza tiene
 *                            inventario o servicio en el taller, pero sin
 *                            precio). False cuando no hay donde guardarlo
 * @param minimumPrice        el minimo que el servidor exige hoy (el costo del
 *                            inventario de la pieza); null si no hay ninguno
 * @param suggestion          la primera referencia utilizable; null si no hay
 * @param references          las referencias encontradas, la sugerencia
 *                            primero (como mucho tres)
 * @param availableUnits      unidades terminadas libres ahora mismo; null en
 *                            un servicio
 * @param productId           la pieza exacta del catalogo, o null si la
 *                            especificacion todavia no existe como producto
 * @param noCharge            si una linea podria ir "sin cargo", y si no por
 *                            que (ver {@link NoChargeEligibility})
 */
public record SaleLineGuidanceDTO(
    PriceStatus priceStatus,
    BigDecimal catalogPrice,
    boolean becomesCatalogPrice,
    BigDecimal minimumPrice,
    PriceReferenceDTO suggestion,
    List<PriceReferenceDTO> references,
    Integer availableUnits,
    UUID productId,
    NoChargeEligibility noCharge
) {
}
