package com.cns.spring_house.cashclosure.repository.projection;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Un articulo del mes, agregado sobre las filas de detalle de los cierres
 * diarios.
 *
 * Se agrupa por {@code productId} y no por nombre, igual que el diario: dos
 * productos homonimos son dos articulos, y fundirlos por texto presentaria como
 * uno lo que son dos.
 *
 * El nombre se toma con {@code MAX(...)}, es decir el de alguno de los cierres
 * del mes. Es deliberado: si el producto se renombro a mitad de mes, cualquiera
 * de los dos nombres describe la misma pieza, y lo que identifica de verdad la
 * fila es el id.
 */
public interface CashClosureItemProjection {

    UUID getProductId();

    String getItemName();

    String getItemType();

    long getQuantity();

    BigDecimal getBilledAmount();

}
