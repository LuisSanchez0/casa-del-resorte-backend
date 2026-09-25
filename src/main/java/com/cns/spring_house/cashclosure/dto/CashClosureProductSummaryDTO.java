package com.cns.spring_house.cashclosure.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * El resumen de MERCADERIA vendida de un cierre: los grupos comerciales con sus
 * totales.
 *
 * <h2>Que entra</h2>
 *
 * Solo las lineas respaldadas por producto de mercaderia: {@code NORMAL},
 * {@code SPECIAL} y {@code CONTAINER}. Los servicios ({@code JOB}) quedan
 * fuera: preguntan por otra cosa —mano de obra, no piezas— y no tienen marca ni
 * modelo que agrupar. Siguen contados, con su importe integro, en el cierre de
 * caja de siempre, que es donde el dinero tiene que cuadrar.
 *
 * Por eso {@code totalAmount} NO tiene por que coincidir con
 * {@code itemsBilledAmount} del cierre: la diferencia son exactamente los
 * servicios, y el documento lo dice en su cara en vez de dejar un descuadre sin
 * explicacion.
 *
 * <h2>Que si cuadra</h2>
 *
 * <pre>
 * totalQuantity == SUM(grupo.quantity)
 * totalAmount   == SUM(grupo.amount)  exacto, con BigDecimal
 * </pre>
 *
 * Los dos se suman de los grupos ya construidos y no por una via paralela: si
 * la agrupacion perdiera una fila, el total la perderia tambien en vez de
 * taparlo.
 *
 * <h2>Lo que NO lleva</h2>
 *
 * Nada sobre COMO se cobro. El PDF del resumen imprime el desglose por forma de
 * pago del cierre —{@code CashClosureResponseDTO.collections()}, leido de
 * `cash_closure_payment_method`— sin pasar por aqui: este agregado responde que
 * mercaderia se vendio, y el dinero lo responde el cierre, una sola vez.
 */
public record CashClosureProductSummaryDTO(

    List<CashClosureProductGroupDTO> groups,
    long totalQuantity,
    BigDecimal totalAmount,

    /**
     * Lo facturado en servicios en el mismo periodo, que este resumen NO agrupa.
     * Viaja para que el PDF pueda explicar la diferencia con el total de
     * articulos del cierre en vez de dejar al lector restando.
     */
    BigDecimal excludedJobAmount,

    /**
     * La parte de {@link #totalAmount} que NO se pudo clasificar por familia.
     *
     * Esta DENTRO de {@code totalAmount}, no aparte: el dinero de una fila sin
     * etiqueta sigue siendo dinero facturado, y sacarlo del total para
     * "limpiarlo" haria que el resumen dejara de cuadrar con el cierre. Viaja
     * como cifra propia unicamente para poder decirlo en el papel.
     *
     * Solo puede ser distinto de cero en cierres ANTERIORES a V37 cuyo producto
     * ya no esta en el catalogo: cuando esos cierres se confirmaron, las
     * columnas de familia, marca y modelo no existian, y el relleno de la
     * migracion no tuvo de donde reconstruirlas. Un cierre confirmado despues de
     * V37 congela sus dimensiones al confirmar y siempre vale cero aqui.
     */
    BigDecimal unclassifiedAmount

) { }
