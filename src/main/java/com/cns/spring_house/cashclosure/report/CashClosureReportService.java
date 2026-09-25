package com.cns.spring_house.cashclosure.report;

import com.cns.spring_house.cashclosure.dto.CashClosureProductSummaryDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureResponseDTO;

/**
 * Genera los PDF del cierre de caja.
 *
 * Recibe DTO ya calculados y no repositorios: el reporte no decide que entra en
 * un cierre, solo lo dibuja. Es lo que hace que el PDF historico sea fiel por
 * construccion —le llega el snapshot, no tiene con que recalcularlo— y que el
 * de previsualizacion y el confirmado compartan una unica plantilla, de modo
 * que lo que la persona revisa antes de cerrar es literalmente el documento que
 * va a quedar archivado.
 */
public interface CashClosureReportService {

    /**
     * Reporte de un dia.
     *
     * @param draft cuando es true el documento se marca como
     *        PREVISUALIZACION - CAJA NO CERRADA, para que un borrador impreso no
     *        pueda confundirse con un cierre confirmado.
     * @param responsible nombre a imprimir: el responsable del cierre en un
     *        documento confirmado, o quien pidio la previsualizacion.
     */
    byte[] renderDaily(
            CashClosureResponseDTO closure,
            ReportHeaderDTO header,
            boolean draft,
            String responsible);

    /**
     * Resumen de MERCADERIA vendida: una fila por grupo comercial —familia, mas
     * marca y modelo cuando aplican— con sus unidades y su importe.
     *
     * Es un SEGUNDO documento, no una version del anterior. El cierre de caja
     * responde cuanto dinero entro; este responde que se vendio, y las dos
     * preguntas se imprimen por separado porque se archivan y se usan por
     * separado.
     *
     * Aqui tampoco se agrupa nada: llega {@code summary} ya construido, por la
     * misma razon que {@link #renderDaily} recibe el cierre ya calculado. El
     * reporte dibuja; quien decide que es el mismo grupo es
     * {@code CashClosureProductGrouper}, y solo el.
     */
    byte[] renderProductSummary(
            CashClosureResponseDTO closure,
            CashClosureProductSummaryDTO summary,
            ReportHeaderDTO header,
            String responsible);

}
