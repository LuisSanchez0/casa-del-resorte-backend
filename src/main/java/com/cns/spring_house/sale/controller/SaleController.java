package com.cns.spring_house.sale.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cns.spring_house.sale.dto.AddSaleDetailRequestDTO;
import com.cns.spring_house.sale.dto.CancelSaleRequestDTO;
import com.cns.spring_house.sale.dto.CompleteSaleRequestDTO;
import com.cns.spring_house.sale.dto.CreateSaleRequestDTO;
import com.cns.spring_house.sale.dto.ExchangeInvoiceSummaryDTO;
import com.cns.spring_house.sale.dto.RegisterPaymentRequestDTO;
import com.cns.spring_house.sale.dto.ReverseSaleRequestDTO;
import com.cns.spring_house.sale.dto.SaleDashboardResponseDTO;
import com.cns.spring_house.sale.dto.SaleResponseDTO;
import com.cns.spring_house.sale.dto.SaleSummaryDTO;
import com.cns.spring_house.sale.dto.SearchSaleRequestDTO;
import com.cns.spring_house.sale.entity.PaymentStatus;
import com.cns.spring_house.sale.service.SaleDashboardService;
import com.cns.spring_house.sale.service.SaleService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;



@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/sales")
public class SaleController {

    private final SaleService service;
    private final SaleDashboardService dashboardService;

    /**
     * Estadisticas agregadas del taller autenticado, en una sola peticion.
     *
     * El segmento literal `/dashboard` se resuelve antes que `/{id}`: el
     * comparador de patrones de Spring prefiere el mas especifico, igual que
     * `/exchange-invoices`.
     *
     * `days` es opcional (7, 30 o 90; por defecto 30). Ni el taller ni el
     * empleado del ambito personal son parametros: los resuelve el servicio
     * desde el contexto de seguridad.
     */
    @GetMapping("/dashboard")
    public ResponseEntity<SaleDashboardResponseDTO> getDashboard(
            @RequestParam(required = false) Integer days) {
        SaleDashboardResponseDTO dashboard = dashboardService.getDashboard(days);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(dashboard);
    }

    @GetMapping
    public ResponseEntity<List<SaleResponseDTO>> getAll(
            SearchSaleRequestDTO requestDTO) {
        List<SaleResponseDTO> sales = service.getAll(requestDTO);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(sales);
    }

    /**
     * Cartera cambiaria paginada, en resumen. El taller sale del token, nunca de
     * la peticion; el filtro opcional de cobro distingue lo pendiente de lo ya
     * cobrado y sin el devuelve la cartera completa.
     */
    @GetMapping("/exchange-invoices")
    public ResponseEntity<Page<ExchangeInvoiceSummaryDTO>> getExchangeInvoices(
            @RequestParam(required = false) PaymentStatus paymentStatus,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<ExchangeInvoiceSummaryDTO> sales =
                service.getExchangeInvoices(paymentStatus, page, size);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(sales);
    }

    /**
     * Buscador de facturas ya emitidas para la bitacora: devuelve un puñado de
     * ventas COMPLETED que coinciden con `q`, de la mas reciente a la mas
     * antigua.
     *
     * El segmento literal `/completed` se resuelve antes que `/{id}`, igual que
     * `/dashboard` y `/exchange-invoices`. El taller sale del token; el estado lo
     * fija el servicio y no es negociable por el cliente.
     */
    @GetMapping("/completed")
    public ResponseEntity<List<SaleSummaryDTO>> searchCompleted(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer limit) {
        List<SaleSummaryDTO> sales = service.searchCompletedSales(q, limit);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(sales);
    }

    @GetMapping("/{id}")
    public ResponseEntity<SaleResponseDTO> get(
            @PathVariable UUID id) {
        SaleResponseDTO sale = service.get(id);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(sale);
    }

    @PostMapping
    public ResponseEntity<SaleResponseDTO> create(
            @RequestBody @Valid CreateSaleRequestDTO requestDTO) {
        SaleResponseDTO sale = service.create(requestDTO);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(sale);
    }

    @PostMapping("/{id}/add-detail")
    public ResponseEntity<SaleResponseDTO> addDetail(
            @PathVariable UUID id,
            @RequestBody @Valid AddSaleDetailRequestDTO requestDTO) {
        SaleResponseDTO sale = service.addDetail(id, requestDTO);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(sale);
    }

    @PostMapping("/{saleDetailId}/remove-detail")
    public ResponseEntity<SaleResponseDTO> removeDetail(
            @PathVariable UUID saleDetailId) {
        SaleResponseDTO sale = service.removeDetail(saleDetailId);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(sale);
    }
    
    @PostMapping("/{id}/complete")
    public ResponseEntity<SaleResponseDTO> complete(
            @PathVariable UUID id,
            @RequestBody @Valid CompleteSaleRequestDTO requestDTO) {
        SaleResponseDTO sale = service.completeSale(id, requestDTO);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(sale);
    }

    @PostMapping("/{id}/register-payment")
    public ResponseEntity<SaleResponseDTO> registerPayment(
            @PathVariable UUID id,
            @RequestBody @Valid RegisterPaymentRequestDTO requestDTO) {
        SaleResponseDTO sale = service.registerPayment(id, requestDTO);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(sale);
    }
    
    
    /**
     * Anula una venta abierta conservandola como historial. Distinto de DELETE,
     * que hace desaparecer el borrador. El motivo es obligatorio.
     */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<SaleResponseDTO> cancel(
            @PathVariable UUID id,
            @RequestBody @Valid CancelSaleRequestDTO requestDTO) {
        SaleResponseDTO sale = service.cancel(id, requestDTO);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(sale);
    }

    /**
     * Revierte una venta ya completada, distinto de {@code cancel}: esa venta
     * se emitio y hay que deshacerla, no una que nunca llego a completarse. El
     * motivo es obligatorio.
     */
    /*
     * SOLO EL VENDEDOR.
     *
     * Revertir dejo de ser una correccion de inventario en el momento en que
     * puede llevar una devolucion de dinero: quien revierte puede sacar
     * efectivo del cajon. Por eso este metodo —y solo este de todo el
     * controlador— esta restringido por rol.
     *
     * SELLER es quien atiende la caja: su modulo tiene la pantalla `Caja`, es
     * quien completa las ventas, quien cobra las cambiarias y quien, por tanto,
     * devuelve el dinero cuando hay que devolverlo. La Secretaria CIERRA la
     * caja —cuadra y firma el dia— pero no la opera.
     *
     * NO se admite ADMIN ni ningun otro rol como alternativa: un permiso de
     * administracion no es la responsabilidad sobre el dinero del dia, y
     * anadirlo dejaria a dos personas capaces de mover la caja sin que ninguna
     * responda por ella.
     */
    @PreAuthorize("hasRole('SELLER')")
    @PostMapping("/{id}/reverse")
    public ResponseEntity<SaleResponseDTO> reverse(
            @PathVariable UUID id,
            @RequestBody @Valid ReverseSaleRequestDTO requestDTO) {
        SaleResponseDTO sale = service.reverse(id, requestDTO);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(sale);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity
                .status(HttpStatus.OK)
                .build();
    }
    
}
