package com.cns.spring_house.sale.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.cns.spring_house.customer.dto.CustomerResponseDTO;
import com.cns.spring_house.sale.dto.ExchangeInvoiceSummaryDTO;
import com.cns.spring_house.sale.dto.RegisterPaymentRequestDTO;
import com.cns.spring_house.sale.dto.SaleResponseDTO;
import com.cns.spring_house.sale.entity.InvoiceType;
import com.cns.spring_house.sale.entity.PaymentMethod;
import com.cns.spring_house.sale.entity.PaymentStatus;
import com.cns.spring_house.sale.entity.SaleStatus;
import com.cns.spring_house.sale.service.SaleService;
import com.cns.spring_house.shared.controller.GlobalExceptionHandler;
import com.cns.spring_house.shared.exception.EntityNotFoundException;
import com.cns.spring_house.shared.exception.ValidationException;

/**
 * Contrato HTTP de la cartera cambiaria y de su cobro, comprobado donde el
 * cliente lo consume: que ruta responde, que llega al servicio y que codigo
 * devuelve cada rechazo.
 *
 * El controlador no decide nada de negocio: aqui solo se comprueba que traduce
 * la peticion y delega.
 */
@ExtendWith(MockitoExtension.class)
class SaleExchangeInvoiceControllerTest {

    @Mock
    private SaleService service;

    @Mock
    private com.cns.spring_house.sale.service.SaleDashboardService dashboardService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new SaleController(service, dashboardService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private SaleResponseDTO exchangeInvoice(
            UUID id, PaymentStatus paymentStatus, PaymentMethod paymentMethod) {
        return new SaleResponseDTO(
                id, null, null, null, "P123ABC", List.of(),
                SaleStatus.COMPLETED, InvoiceType.EXCHANGE_INVOICE,
                paymentStatus, paymentMethod, new BigDecimal("1250.00"),
                null, null, null, null);
    }

    private ExchangeInvoiceSummaryDTO summary(
            UUID id, PaymentStatus paymentStatus, PaymentMethod paymentMethod) {
        return new ExchangeInvoiceSummaryDTO(
                id,
                new CustomerResponseDTO(UUID.randomUUID(), "12345678", "CLIENTE"),
                "P123ABC", InvoiceType.EXCHANGE_INVOICE,
                paymentStatus, paymentMethod, new BigDecimal("1250.00"),
                LocalDateTime.of(2026, 8, 1, 10, 30),
                paymentStatus == PaymentStatus.PAID
                        ? LocalDateTime.of(2026, 8, 15, 9, 0)
                        : null);
    }

    private Page<ExchangeInvoiceSummaryDTO> page(
            List<ExchangeInvoiceSummaryDTO> content, int page, int size, long total) {
        return new PageImpl<>(content, PageRequest.of(page, size), total);
    }

    /** Devuelve {paymentStatus, page, size} tal y como llegaron al servicio. */
    private Object[] captureRequest() {
        ArgumentCaptor<PaymentStatus> filter =
                ArgumentCaptor.forClass(PaymentStatus.class);
        ArgumentCaptor<Integer> page = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> size = ArgumentCaptor.forClass(Integer.class);
        verify(service).getExchangeInvoices(
                filter.capture(), page.capture(), size.capture());
        return new Object[] { filter.getValue(), page.getValue(), size.getValue() };
    }

    // ── Listado ──────────────────────────────────────────────────

    /** Sin parametro: toda la cartera cambiaria, cobrada y pendiente. */
    @Test
    void theWholePortfolioIsReturnedWithoutAFilter() throws Exception {
        UUID pending = UUID.randomUUID();
        UUID paid = UUID.randomUUID();
        when(service.getExchangeInvoices(null, 0, 20)).thenReturn(page(
                List.of(summary(pending, PaymentStatus.PENDING, null),
                        summary(paid, PaymentStatus.PAID, PaymentMethod.CASH)),
                0, 20, 2));

        mockMvc.perform(get("/v1/sales/exchange-invoices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].id").value(pending.toString()))
                .andExpect(jsonPath("$.content[0].invoiceType")
                        .value("EXCHANGE_INVOICE"))
                .andExpect(jsonPath("$.content[0].paymentStatus").value("PENDING"))
                .andExpect(jsonPath("$.content[0].paymentMethod").doesNotExist())
                .andExpect(jsonPath("$.content[0].customer.name").value("CLIENTE"))
                .andExpect(jsonPath("$.content[1].paymentStatus").value("PAID"))
                .andExpect(jsonPath("$.content[1].paymentMethod").value("CASH"));

        // El listado por defecto pide la primera pagina.
        assertThat(captureRequest()).containsExactly(null, 0, 20);
    }

    /**
     * La cartera es un listado: no puede arrastrar las lineas de cada documento,
     * que es justo lo que obligaba a cargar SaleResponseDTO.
     */
    @Test
    void theSummaryNeverCarriesTheSaleDetails() throws Exception {
        when(service.getExchangeInvoices(null, 0, 20)).thenReturn(page(
                List.of(summary(UUID.randomUUID(), PaymentStatus.PENDING, null)),
                0, 20, 1));

        mockMvc.perform(get("/v1/sales/exchange-invoices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].details").doesNotExist())
                .andExpect(jsonPath("$.content[0].employee").doesNotExist())
                .andExpect(jsonPath("$.content[0].workshop").doesNotExist())
                .andExpect(jsonPath("$.content[0].total").value(1250.00))
                .andExpect(jsonPath("$.content[0].plate").value("P123ABC"));
    }

    @Test
    void thePendingPortfolioIsRequestedAsPending() throws Exception {
        when(service.getExchangeInvoices(PaymentStatus.PENDING, 0, 20))
                .thenReturn(page(List.of(summary(
                        UUID.randomUUID(), PaymentStatus.PENDING, null)),
                        0, 20, 1));

        mockMvc.perform(get("/v1/sales/exchange-invoices")
                        .param("paymentStatus", "PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].paymentStatus").value("PENDING"));

        assertThat(captureRequest()[0]).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void theCollectedPortfolioIsRequestedAsPaid() throws Exception {
        when(service.getExchangeInvoices(PaymentStatus.PAID, 0, 20))
                .thenReturn(page(List.of(summary(
                        UUID.randomUUID(), PaymentStatus.PAID,
                        PaymentMethod.TRANSFER)), 0, 20, 1));

        mockMvc.perform(get("/v1/sales/exchange-invoices")
                        .param("paymentStatus", "PAID"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].paymentStatus").value("PAID"))
                .andExpect(jsonPath("$.content[0].paidAt").exists());

        assertThat(captureRequest()[0]).isEqualTo(PaymentStatus.PAID);
    }

    /** page y size viajan al servicio tal cual los pidio el cliente. */
    @Test
    void thePageAndSizeReachTheService() throws Exception {
        when(service.getExchangeInvoices(PaymentStatus.PENDING, 2, 5))
                .thenReturn(page(List.of(), 2, 5, 11));

        mockMvc.perform(get("/v1/sales/exchange-invoices")
                        .param("paymentStatus", "PENDING")
                        .param("page", "2")
                        .param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(11))
                .andExpect(jsonPath("$.totalPages").value(3));

        assertThat(captureRequest())
                .containsExactly(PaymentStatus.PENDING, 2, 5);
    }

    /** Un tamaño de pagina absurdo lo rechaza el servicio, y viaja como 400. */
    @Test
    void anAbsurdPageSizeIsABadRequest() throws Exception {
        when(service.getExchangeInvoices(null, 0, 5000))
                .thenThrow(new ValidationException(
                        "El tamaño de pagina debe estar entre 1 y 100"));

        mockMvc.perform(get("/v1/sales/exchange-invoices")
                        .param("size", "5000"))
                .andExpect(status().isBadRequest());
    }

    /** Una cartera vacia es una respuesta valida, no un error. */
    @Test
    void anEmptyPortfolioIsAnEmptyPage() throws Exception {
        when(service.getExchangeInvoices(PaymentStatus.PENDING, 0, 20))
                .thenReturn(page(List.of(), 0, 20, 0));

        mockMvc.perform(get("/v1/sales/exchange-invoices")
                        .param("paymentStatus", "PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void anUnknownPaymentStatusIsRejected() throws Exception {
        mockMvc.perform(get("/v1/sales/exchange-invoices")
                        .param("paymentStatus", "MEDIO_PAGADA"))
                .andExpect(status().isBadRequest());

        verify(service, never()).getExchangeInvoices(any(), anyInt(), anyInt());
    }

    /**
     * La ruta literal no puede quedar capturada por `GET /v1/sales/{id}`: si lo
     * hiciera, "exchange-invoices" se intentaria leer como UUID.
     */
    @Test
    void thePortfolioRouteIsNotSwallowedByTheSaleByIdRoute() throws Exception {
        when(service.getExchangeInvoices(null, 0, 20))
                .thenReturn(page(List.of(), 0, 20, 0));

        mockMvc.perform(get("/v1/sales/exchange-invoices"))
                .andExpect(status().isOk());

        verify(service, never()).get(any());
    }

    // ── Cancelacion ──────────────────────────────────────────────

    @Test
    void cancellingASaleReturnsItAsCancelled() throws Exception {
        UUID saleId = UUID.randomUUID();
        when(service.cancel(any(), any())).thenReturn(new SaleResponseDTO(
                saleId, null, null, null, "P123ABC", List.of(),
                SaleStatus.CANCELLED, null, PaymentStatus.PENDING, null,
                new BigDecimal("150.00"), null, null, null, null));

        mockMvc.perform(post("/v1/sales/" + saleId + "/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Cliente desistio\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.id").value(saleId.toString()));
    }

    @Test
    void cancellingWithoutAReasonIsABadRequest() throws Exception {
        mockMvc.perform(post("/v1/sales/" + UUID.randomUUID() + "/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        verify(service, never()).cancel(any(), any());
    }

    @Test
    void cancellingWithABlankReasonIsABadRequest() throws Exception {
        mockMvc.perform(post("/v1/sales/" + UUID.randomUUID() + "/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"   \"}"))
                .andExpect(status().isBadRequest());

        verify(service, never()).cancel(any(), any());
    }

    @Test
    void cancellingAnIssuedSaleIsABadRequest() throws Exception {
        when(service.cancel(any(), any())).thenThrow(new ValidationException(
                "Solo se puede cancelar una venta abierta"));

        mockMvc.perform(post("/v1/sales/" + UUID.randomUUID() + "/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Motivo\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        "Solo se puede cancelar una venta abierta"));
    }

    @Test
    void cancellingASaleFromAnotherWorkshopIsNotFound() throws Exception {
        when(service.cancel(any(), any())).thenThrow(new EntityNotFoundException(
                "No existe la venta especificada"));

        mockMvc.perform(post("/v1/sales/" + UUID.randomUUID() + "/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Motivo\"}"))
                .andExpect(status().isNotFound());
    }

    // ── Reversion ────────────────────────────────────────────────

    @Test
    void reversingACompletedSaleReturnsItAsReversed() throws Exception {
        UUID saleId = UUID.randomUUID();
        when(service.reverse(any(), any())).thenReturn(new SaleResponseDTO(
                saleId, null, null, null, "P123ABC", List.of(),
                SaleStatus.REVERSED, InvoiceType.INVOICE, PaymentStatus.PAID,
                PaymentMethod.CASH, new BigDecimal("150.00"),
                null, null, null, null));

        mockMvc.perform(post("/v1/sales/" + saleId + "/reverse")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Se cargo un producto equivocado\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVERSED"))
                .andExpect(jsonPath("$.id").value(saleId.toString()));
    }

    @Test
    void reversingWithoutAReasonIsABadRequest() throws Exception {
        mockMvc.perform(post("/v1/sales/" + UUID.randomUUID() + "/reverse")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        verify(service, never()).reverse(any(), any());
    }

    @Test
    void reversingAPendingSaleIsABadRequest() throws Exception {
        when(service.reverse(any(), any())).thenThrow(new ValidationException(
                "Una venta abierta se anula, no se revierte"));

        mockMvc.perform(post("/v1/sales/" + UUID.randomUUID() + "/reverse")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Motivo\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        "Una venta abierta se anula, no se revierte"));
    }

    @Test
    void reversingASaleFromAnotherWorkshopIsNotFound() throws Exception {
        when(service.reverse(any(), any())).thenThrow(new EntityNotFoundException(
                "No existe la venta especificada"));

        mockMvc.perform(post("/v1/sales/" + UUID.randomUUID() + "/reverse")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Motivo\"}"))
                .andExpect(status().isNotFound());
    }

    // ── Cobro ────────────────────────────────────────────────────

    @Test
    void registeringThePaymentReturnsTheCollectedInvoice() throws Exception {
        UUID saleId = UUID.randomUUID();
        when(service.registerPayment(
                saleId, new RegisterPaymentRequestDTO(PaymentMethod.TRANSFER)))
                .thenReturn(exchangeInvoice(
                        saleId, PaymentStatus.PAID, PaymentMethod.TRANSFER));

        mockMvc.perform(post("/v1/sales/" + saleId + "/register-payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentMethod\":\"TRANSFER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.invoiceType").value("EXCHANGE_INVOICE"))
                .andExpect(jsonPath("$.paymentStatus").value("PAID"))
                .andExpect(jsonPath("$.paymentMethod").value("TRANSFER"));
    }

    @Test
    void thePaymentMethodIsMandatory() throws Exception {
        mockMvc.perform(post("/v1/sales/" + UUID.randomUUID() + "/register-payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        verify(service, never()).registerPayment(any(), any());
    }

    /** Un rechazo de negocio viaja como 400 con su mensaje. */
    @Test
    void aRejectedPaymentIsABadRequestWithItsReason() throws Exception {
        UUID saleId = UUID.randomUUID();
        when(service.registerPayment(any(), any()))
                .thenThrow(new ValidationException(
                        "Solo las facturas cambiarias pueden registrar un pago posterior"));

        mockMvc.perform(post("/v1/sales/" + saleId + "/register-payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentMethod\":\"CASH\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        "Solo las facturas cambiarias pueden registrar un pago posterior"));
    }

    /** Una venta de otro taller responde como inexistente, nunca como prohibida. */
    @Test
    void aSaleFromAnotherWorkshopIsNotFound() throws Exception {
        when(service.registerPayment(any(), any()))
                .thenThrow(new EntityNotFoundException(
                        "No existe la venta especificada"));

        mockMvc.perform(post("/v1/sales/" + UUID.randomUUID() + "/register-payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentMethod\":\"CASH\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value(
                        "No existe la venta especificada"));
    }

}
