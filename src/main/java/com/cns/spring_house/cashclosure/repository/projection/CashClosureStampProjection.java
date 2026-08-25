package com.cns.spring_house.cashclosure.repository.projection;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Lo minimo que hay que saber de un cierre para responder "esta caja ya se
 * cerro": su identificador y el instante en que se confirmo.
 *
 * Existe para no cargar la entidad entera solo por eso. {@code CashClosure}
 * tiene dos {@code @ManyToOne} EAGER —taller y empleado que cerro—, de modo que
 * un {@code findByWorkshopIdAndBusinessDate} paga tres SELECT y trae veintitantas
 * columnas de arqueo que el estado del dashboard no muestra.
 *
 * {@code closedAt} es {@code CashClosure.createdAt}: la fila se inserta en el
 * momento del cierre y nunca se modifica, asi que la auditoria ES el instante
 * del cierre. Ver {@code CashClosure}.
 */
public interface CashClosureStampProjection {

    UUID getId();

    LocalDateTime getClosedAt();

}
