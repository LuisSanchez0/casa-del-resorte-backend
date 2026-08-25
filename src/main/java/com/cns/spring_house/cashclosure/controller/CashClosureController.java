package com.cns.spring_house.cashclosure.controller;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.cns.spring_house.cashclosure.dto.CashClosureBasePreviewResponseDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureDocumentDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureResponseDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureSummaryResponseDTO;
import com.cns.spring_house.cashclosure.dto.CreateCashClosureRequestDTO;
import com.cns.spring_house.cashclosure.service.CashClosureService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Cierre de caja del taller autenticado.
 *
 * El taller NO viaja en ninguna ruta, ni como parametro ni en el cuerpo: lo
 * resuelve el servicio desde el token, tambien en las descargas de PDF.
 *
 * Los segmentos literales (`/preview`, `/by-date`, `/summary`) se resuelven
 * antes que `/{id}` porque el comparador de patrones de Spring prefiere el mas
 * especifico, igual que hacen `/dashboard` y `/exchange-invoices` en
 * {@code SaleController}.
 *
 * No existen PATCH ni DELETE a proposito: un cierre confirmado es historico e
 * inmutable, y reabrirlo seria otro flujo, auditado, que hoy no esta pedido.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/cash-closures")
public class CashClosureController {

    private final CashClosureService service;

    /**
     * Cifras del dia en curso, sin arqueo. Consultarla no cierra la caja, no
     * persiste nada y no bloquea ventas.
     *
     * Cuando el dia ya fue cerrado responde {@code alreadyClosed = true} con el
     * identificador del cierre, para que la pantalla ofrezca el snapshot en vez
     * de un segundo cierre.
     */
    @GetMapping("/preview")
    public ResponseEntity<CashClosureBasePreviewResponseDTO> getPreview() {
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(service.getPreview());
    }

    /**
     * Previsualizacion final: las cifras recalculadas mas el arqueo declarado.
     * Devuelve el documento que se persistiria, sin persistirlo.
     */
    @PostMapping("/preview")
    public ResponseEntity<CashClosureResponseDTO> preview(
            @RequestBody @Valid CreateCashClosureRequestDTO requestDTO) {
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(service.preview(requestDTO));
    }

    /**
     * La previsualizacion final en PDF, para revisarla o firmarla antes de
     * cerrar. NO crea el cierre y NO bloquea ventas; el documento sale marcado
     * como PREVISUALIZACION - CAJA NO CERRADA.
     */
    @PostMapping("/preview/pdf")
    public ResponseEntity<byte[]> previewDocument(
            @RequestBody @Valid CreateCashClosureRequestDTO requestDTO) {
        return download(service.previewDocument(requestDTO));
    }

    /** Confirma el cierre del dia y persiste el snapshot. */
    @PostMapping
    public ResponseEntity<CashClosureResponseDTO> close(
            @RequestBody @Valid CreateCashClosureRequestDTO requestDTO) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(service.close(requestDTO));
    }

    /** Cierre de una fecha concreta del taller autenticado. */
    @GetMapping("/by-date")
    public ResponseEntity<CashClosureResponseDTO> getByDate(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(service.getByDate(date));
    }

    /** Agregado del rango sobre los snapshots. Un mes es un rango mas. */
    @GetMapping("/summary")
    public ResponseEntity<CashClosureSummaryResponseDTO> getSummary(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(service.getSummary(from, to));
    }

    @GetMapping("/summary/pdf")
    public ResponseEntity<byte[]> getSummaryDocument(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return download(service.summaryDocument(from, to));
    }

    @GetMapping
    public ResponseEntity<List<CashClosureResponseDTO>> getRange(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(service.getRange(from, to));
    }

    /** Snapshot historico. Un cierre de otro taller responde 404. */
    @GetMapping("/{id}")
    public ResponseEntity<CashClosureResponseDTO> get(@PathVariable UUID id) {
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(service.get(id));
    }

    /** PDF del cierre confirmado, generado exclusivamente desde el snapshot. */
    @GetMapping("/{id}/pdf")
    public ResponseEntity<byte[]> getDocument(@PathVariable UUID id) {
        return download(service.document(id));
    }

    /**
     * `attachment` y no `inline`: estos documentos se archivan y se imprimen, y
     * el nombre del archivo forma parte de esa trazabilidad. El nombre lo decide
     * el servicio, que es quien conoce la fecha operativa o los extremos del
     * rango.
     */
    private ResponseEntity<byte[]> download(CashClosureDocumentDTO document) {
        ContentDisposition disposition = ContentDisposition
                .attachment()
                .filename(document.fileName())
                .build();
        return ResponseEntity
                .status(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(document.content().length)
                .body(document.content());
    }

}
