package com.cns.spring_house.sale.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Una referencia de precio con su procedencia, para que el vendedor sepa por
 * que se le propone esa cifra.
 *
 * @param source       de donde sale (ver {@link PriceReferenceSource})
 * @param price        el importe tal cual esta registrado; nunca calculado
 * @param recordedAt   cuando se cobro (LAST_SALE); null en una pieza del catalogo
 * @param productId    pieza de la que sale el importe
 * @param productName  su nombre en el catalogo
 * @param productSku   su SKU
 * @param differences  en que caracteristica difiere de la pedida (vacia en
 *                     LAST_SALE y EQUIVALENT_PIECE; una en SIMILAR_PIECE)
 * @param belowMinimum queda por debajo del minimo que el servidor exige hoy
 *                     para la pieza pedida (su costo): se muestra como
 *                     contexto, pero no se puede usar tal cual
 */
public record PriceReferenceDTO(
    PriceReferenceSource source,
    BigDecimal price,
    LocalDateTime recordedAt,
    UUID productId,
    String productName,
    String productSku,
    List<AttributeDifferenceDTO> differences,
    boolean belowMinimum
) {
}
