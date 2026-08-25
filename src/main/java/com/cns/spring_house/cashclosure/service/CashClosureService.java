package com.cns.spring_house.cashclosure.service;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.cns.spring_house.cashclosure.dto.CashClosureBasePreviewResponseDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureDocumentDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureResponseDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureSummaryResponseDTO;
import com.cns.spring_house.cashclosure.dto.CreateCashClosureRequestDTO;

/**
 * Cierre de caja diario.
 *
 * El taller nunca es un parametro: sale del token en cada una de estas
 * operaciones, incluidas las de exportacion.
 *
 * Hay una frontera dura entre las tres primeras operaciones y el resto:
 * `getPreview`, `preview` y `previewDocument` calculan contra `sale` y NO
 * persisten ni bloquean nada; `close` persiste el snapshot, y a partir de ahi
 * `get`, `getByDate`, `getRange`, `getSummary` y los PDF historicos leen solo
 * ese snapshot.
 */
public interface CashClosureService {

    /** Cifras del dia tal y como estan ahora, sin arqueo. No persiste nada. */
    CashClosureBasePreviewResponseDTO getPreview();

    /**
     * Previsualizacion final: recalcula el dia y le aplica el arqueo declarado.
     * Devuelve exactamente el documento que se persistiria, sin persistirlo.
     */
    CashClosureResponseDTO preview(CreateCashClosureRequestDTO requestDTO);

    /** La previsualizacion final en PDF. Tampoco persiste ni bloquea ventas. */
    CashClosureDocumentDTO previewDocument(CreateCashClosureRequestDTO requestDTO);

    /**
     * Confirma el cierre del dia. RECALCULA todo desde cero: la previsualizacion
     * no es la fuente de verdad, porque entre verla y confirmar pudo cobrarse
     * una cambiaria o emitirse otra factura.
     */
    CashClosureResponseDTO close(CreateCashClosureRequestDTO requestDTO);

    CashClosureResponseDTO get(UUID id);

    CashClosureResponseDTO getByDate(LocalDate date);

    /** Cierres del rango, de mas reciente a mas antiguo. */
    List<CashClosureResponseDTO> getRange(LocalDate from, LocalDate to);

    /** Agregado del rango sobre los snapshots. Sirve igual para un mes. */
    CashClosureSummaryResponseDTO getSummary(LocalDate from, LocalDate to);

    /** PDF de un cierre confirmado, generado desde el snapshot. */
    CashClosureDocumentDTO document(UUID id);

    /** PDF del resumen del rango, generado desde los snapshots. */
    CashClosureDocumentDTO summaryDocument(LocalDate from, LocalDate to);

}
