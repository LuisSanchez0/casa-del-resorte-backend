package com.cns.spring_house.sale.dto;

import java.util.Map;
import java.util.UUID;

/** La pieza o el servicio pedido, con su especificacion estructurada. */
public record FulfillmentProductDTO(

    UUID id,
    String name,
    String sku,
    UUID familyId,
    String familyName,
    UUID modelId,
    String modelName,
    UUID brandId,
    String brandName,
    /** Caracteristicas pedidas: son la especificacion que manda. */
    Map<String, Object> attributes

) {

}
