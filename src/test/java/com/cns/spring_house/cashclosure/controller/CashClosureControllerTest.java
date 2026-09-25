package com.cns.spring_house.cashclosure.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.cns.spring_house.cashclosure.dto.CashClosureCollectionsDTO;
import com.cns.spring_house.cashclosure.dto.ClosureRefundMethodDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureRefundsDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureDocumentDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureExchangeDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureInvoicingDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureMonthStatus;
import com.cns.spring_house.cashclosure.dto.CashClosureMonthlyResponseDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureResponseDTO;
import com.cns.spring_house.cashclosure.dto.ClosureExchangeInvoiceDTO;
import com.cns.spring_house.cashclosure.dto.ClosurePaymentMethodDTO;
import com.cns.spring_house.cashclosure.dto.ClosureSoldItemDTO;
import com.cns.spring_house.cashclosure.service.CashClosureService;
import com.cns.spring_house.family.entity.FamilyCategory;
import com.cns.spring_house.sale.entity.PaymentMethod;
import com.cns.spring_house.shared.controller.GlobalExceptionHandler;
import com.cns.spring_house.shared.exception.ValidationException;

/**
 * El contrato HTTP del cierre de caja: que ruta responde, que llega al servicio
 * y —sobre todo— que NO se puede enviar.
 *
 * La comprobacion que mas importa aqui es negativa: ninguna ruta acepta cifras.
 * El cierre ni siquiera recibe cuerpo. Aceptar un total del cliente permitiria
 * cerrar una caja con los numeros que uno quisiera, y esa puerta se cierra en el
 * contrato, no en el formulario.
 */
@ExtendWith(MockitoExtension.class)
class CashClosureControllerTest {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 8, 20);

    @Mock
    private CashClosureService service;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new CashClosureController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    // ── Previsualizacion ─────────────────────────────────────────

    @Test
    void previewReturnsTheLiveDayWithoutClosingAnything() throws Exception {
        when(service.getPreview()).thenReturn(closure(null));

        mockMvc.perform(get("/v1/cash-closures/preview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessDate").value("2026-08-20"))
                .andExpect(jsonPath("$.alreadyClosed").value(false))
                .andExpect(jsonPath("$.items[0].name").value("RESORTE"))
                .andExpect(jsonPath("$.collections.totalCollected").value(6000.00));

        verify(service).getPreview();
    }

    /**
     * La respuesta NO expone arqueo. Si alguien reintrodujera el fondo inicial o
     * el efectivo contado, esta prueba lo detecta.
     */
    @Test
    void theResponseCarriesNoCashCountFields() throws Exception {
        when(service.getPreview()).thenReturn(closure(null));

        mockMvc.perform(get("/v1/cash-closures/preview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cashReconciliation").doesNotExist())
                .andExpect(jsonPath("$.openingCash").doesNotExist())
                .andExpect(jsonPath("$.countedCash").doesNotExist())
                .andExpect(jsonPath("$.difference").doesNotExist())
                .andExpect(jsonPath("$.notes").doesNotExist());
    }

    // ── Cierre ───────────────────────────────────────────────────

    @Test
    void closeConfirmsTheDay() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.close()).thenReturn(closure(id));

        mockMvc.perform(post("/v1/cash-closures"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.alreadyClosed").value(true));

        verify(service).close();
    }

    /**
     * El cierre no recibe cuerpo. Aunque alguien mande uno con cifras, no hay
     * donde aterrizen: el metodo del servicio no toma parametros.
     */
    @Test
    void closeIgnoresAnyBodySentByTheClient() throws Exception {
        when(service.close()).thenReturn(closure(UUID.randomUUID()));

        mockMvc.perform(post("/v1/cash-closures")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"openingCash\":500,\"countedCash\":9999,"
                                + "\"totalCollected\":123456}"))
                .andExpect(status().isCreated());

        // La firma no admite argumentos: nada de lo enviado puede influir.
        verify(service).close();
    }

    /** Cerrar dos veces el mismo dia responde 400 de negocio, no 500. */
    @Test
    void closingTwiceRespondsWithABusinessError() throws Exception {
        when(service.close()).thenThrow(
                new ValidationException("La caja de este taller ya fue cerrada"));

        mockMvc.perform(post("/v1/cash-closures"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail")
                        .value("La caja de este taller ya fue cerrada"));
    }

    // ── Lecturas historicas ──────────────────────────────────────

    @Test
    void readsAClosureByDate() throws Exception {
        when(service.getByDate(BUSINESS_DATE)).thenReturn(closure(UUID.randomUUID()));

        mockMvc.perform(get("/v1/cash-closures/by-date").param("date", "2026-08-20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessDate").value("2026-08-20"));

        verify(service).getByDate(BUSINESS_DATE);
    }

    @Test
    void readsAClosureById() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.get(id)).thenReturn(closure(id));

        mockMvc.perform(get("/v1/cash-closures/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()));
    }

    // ── Cierre mensual ───────────────────────────────────────────

    @Test
    void readsTheMonthlyClosure() throws Exception {
        when(service.getMonthly(2026, 8)).thenReturn(monthly());

        mockMvc.perform(get("/v1/cash-closures/monthly")
                        .param("year", "2026")
                        .param("month", "8"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.year").value(2026))
                .andExpect(jsonPath("$.month").value(8))
                .andExpect(jsonPath("$.status").value("PARTIAL"))
                .andExpect(jsonPath("$.closedDays").value(12))
                .andExpect(jsonPath("$.pendingDays").value(8));

        verify(service).getMonthly(2026, 8);
    }

    /** Un mes fuera de rango responde 400 de negocio. */
    @Test
    void rejectsAnInvalidMonth() throws Exception {
        when(service.getMonthly(eq(2026), eq(13)))
                .thenThrow(new ValidationException("El mes debe estar entre 1 y 12"));

        mockMvc.perform(get("/v1/cash-closures/monthly")
                        .param("year", "2026")
                        .param("month", "13"))
                .andExpect(status().isBadRequest());
    }

    // ── Exportacion ──────────────────────────────────────────────

    /**
     * `attachment`: estos documentos se archivan e imprimen, y el nombre del
     * archivo forma parte de esa trazabilidad.
     */
    @Test
    void downloadsTheClosurePdfAsAnAttachment() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.document(id)).thenReturn(
                new CashClosureDocumentDTO("cierre-caja-2026-08-20.pdf",
                        new byte[] { 1, 2, 3 }));

        mockMvc.perform(get("/v1/cash-closures/{id}/pdf", id))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("attachment")))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("cierre-caja-2026-08-20.pdf")))
                .andExpect(header().string("Content-Type", "application/pdf"));
    }

    /**
     * El SEGUNDO documento vive en su propia ruta y con su propio nombre de
     * archivo.
     *
     * Ruta aparte y no `?formato=`: son dos papeles que se archivan por
     * separado, y un parametro los dejaria compartiendo nombre.
     */
    @Test
    void downloadsTheProductSummaryPdfAsASeparateAttachment() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.productSummaryDocument(id)).thenReturn(
                new CashClosureDocumentDTO(
                        "resumen-productos-cierre-2026-08-20.pdf",
                        new byte[] { 4, 5, 6 }));

        mockMvc.perform(get("/v1/cash-closures/{id}/products-pdf", id))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("attachment")))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString(
                                "resumen-productos-cierre-2026-08-20.pdf")))
                .andExpect(header().string("Content-Type", "application/pdf"));
    }

    /**
     * Los dos PDF son documentos DISTINTOS y ninguno llama al otro. Esta prueba
     * falla el dia que alguien "unifique" las dos rutas y el boton de cierre
     * empiece a bajar el resumen.
     */
    @Test
    void theTwoPdfRoutesAreIndependent() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.document(id)).thenReturn(
                new CashClosureDocumentDTO("cierre-caja-2026-08-20.pdf",
                        new byte[] { 1 }));

        mockMvc.perform(get("/v1/cash-closures/{id}/pdf", id))
                .andExpect(status().isOk());

        verify(service).document(id);
        verify(service, never()).productSummaryDocument(any());
    }

    // ── Aislamiento ──────────────────────────────────────────────

    /**
     * NINGUNA ruta acepta taller. No es una omision: es la garantia de
     * aislamiento, y el servicio lo resuelve desde el token. Esta prueba falla
     * el dia que alguien anada el parametro.
     */
    @Test
    void noRouteAcceptsAWorkshop() throws Exception {
        when(service.getPreview()).thenReturn(closure(null));

        // El parametro se manda y el servicio se llama igual, sin recibirlo:
        // no hay forma de pedir la caja de otro taller.
        mockMvc.perform(get("/v1/cash-closures/preview")
                        .param("workshop", UUID.randomUUID().toString())
                        .param("workshopId", UUID.randomUUID().toString()))
                .andExpect(status().isOk());

        verify(service).getPreview();
        verify(service, never()).getByDate(any());
    }

    // ── Datos ────────────────────────────────────────────────────

    private CashClosureResponseDTO closure(UUID id) {
        return new CashClosureResponseDTO(
                id,
                BUSINESS_DATE,
                id == null ? null : LocalDateTime.of(2026, 8, 20, 19, 0),
                id == null ? null : "ANA LOPEZ",
                id != null,
                List.of(new ClosureSoldItemDTO(
                        UUID.randomUUID(), "RESORTE", FamilyCategory.NORMAL,
                        4L, new BigDecimal("2000.00"))),
                new BigDecimal("2000.00"),
                new CashClosureInvoicingDTO(
                        2L, new BigDecimal("5000.00"),
                        1L, new BigDecimal("2000.00"),
                        new BigDecimal("7000.00")),
                new CashClosureExchangeDTO(
                        1L, new BigDecimal("2000.00"), new BigDecimal("2000.00"),
                        1L, new BigDecimal("1000.00"), new BigDecimal("1000.00"),
                        List.of(new ClosureExchangeInvoiceDTO(
                                UUID.randomUUID(), "A1B2C3D4", "TRANSPORTES",
                                LocalDateTime.of(2026, 8, 20, 11, 0),
                                new BigDecimal("2000.00"), false, null, null)),
                        List.of()),
                new CashClosureCollectionsDTO(
                        List.of(
                                new ClosurePaymentMethodDTO(
                                        PaymentMethod.CASH, 2L, new BigDecimal("6000.00")),
                                new ClosurePaymentMethodDTO(
                                        PaymentMethod.CARD, 0L, new BigDecimal("0.00")),
                                new ClosurePaymentMethodDTO(
                                        PaymentMethod.TRANSFER, 0L, new BigDecimal("0.00"))),
                        new BigDecimal("6000.00")),
                new CashClosureRefundsDTO(
                        List.of(
                                new ClosureRefundMethodDTO(
                                        PaymentMethod.CASH, 0L, new BigDecimal("0.00")),
                                new ClosureRefundMethodDTO(
                                        PaymentMethod.CARD, 0L, new BigDecimal("0.00")),
                                new ClosureRefundMethodDTO(
                                        PaymentMethod.TRANSFER, 0L, new BigDecimal("0.00"))),
                        0L, new BigDecimal("0.00"), List.of()),
                new BigDecimal("6000.00"));
    }

    private CashClosureMonthlyResponseDTO monthly() {
        CashClosureResponseDTO daily = closure(UUID.randomUUID());
        return new CashClosureMonthlyResponseDTO(
                2026, 8,
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 20),
                12L, 8L, CashClosureMonthStatus.PARTIAL,
                daily.items(), daily.itemsBilledAmount(),
                daily.invoicing(), daily.exchange(), daily.collections(),
                daily.refunds(), daily.netPeriod());
    }

}
