package com.cns.spring_house.cashclosure.controller;

import java.time.LocalDate;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.cns.spring_house.cashclosure.dto.CashClosureDocumentDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureMonthlyResponseDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureResponseDTO;
import com.cns.spring_house.cashclosure.service.CashClosureService;

import lombok.RequiredArgsConstructor;

/**
 * Cierre de caja del taller autenticado.
 *
 * El taller NO viaja en ninguna ruta, ni como parametro ni en el cuerpo: lo
 * resuelve el servicio desde el token, tambien en las descargas de PDF.
 *
 * NINGUNA ruta acepta cifras. El cierre no recibe siquiera cuerpo: todo lo que
 * lo compone lo calcula el servidor contra la base de datos, y aceptar un solo
 * total del cliente permitiria cerrar una caja con los numeros que uno quisiera.
 *
 * Los segmentos literales (`/preview`, `/by-date`, `/monthly`) se resuelven
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
     * El dia en curso tal y como esta ahora. Consultarlo no cierra la caja, no
     * persiste nada y no bloquea ventas.
     *
     * Cuando el dia ya fue cerrado responde {@code alreadyClosed = true} con el
     * identificador del cierre, para que la pantalla ofrezca el snapshot en vez
     * de un segundo cierre.
     */
    @GetMapping("/preview")
    public ResponseEntity<CashClosureResponseDTO> getPreview() {
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(service.getPreview());
    }

    /** Confirma el cierre del dia y persiste el snapshot. Sin cuerpo. */
    @PostMapping
    public ResponseEntity<CashClosureResponseDTO> close() {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(service.close());
    }

    /** Cierre de una fecha concreta del taller autenticado. */
    @GetMapping("/by-date")
    public ResponseEntity<CashClosureResponseDTO> getByDate(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(service.getByDate(date));
    }

    /**
     * Cierre MENSUAL: la misma estructura contable que un dia, agregada sobre
     * los cierres diarios confirmados del mes.
     *
     * `year` y `month` en vez de un rango arbitrario: el negocio cierra por mes
     * natural, y un rango libre permitia pedir "del 3 al 17", que no es un
     * cierre de nada. El servicio los valida y responde 400 con mensaje de
     * negocio si estan fuera de rango.
     */
    @GetMapping("/monthly")
    public ResponseEntity<CashClosureMonthlyResponseDTO> getMonthly(
            @RequestParam int year,
            @RequestParam int month) {
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(service.getMonthly(year, month));
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
     * el servicio, que es quien conoce la fecha operativa.
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
