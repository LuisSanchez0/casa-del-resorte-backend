package com.cns.spring_house.cashclosure.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.cns.spring_house.cashclosure.dto.CashClosureBalanceSummaryDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureBasePreviewResponseDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureCollectionsDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureDocumentDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureInvoicingDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureRangeReceivablesDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureReceivablesDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureResponseDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureSummaryResponseDTO;
import com.cns.spring_house.cashclosure.dto.CashReconciliationDTO;
import com.cns.spring_house.cashclosure.entity.CashBalanceStatus;
import com.cns.spring_house.cashclosure.service.CashClosureService;
import com.cns.spring_house.shared.controller.GlobalExceptionHandler;
import com.cns.spring_house.shared.exception.EntityNotFoundException;
import com.cns.spring_house.shared.exception.ValidationException;

/**
 * El contrato HTTP del cierre de caja, comprobado donde lo consume el cliente:
 * que ruta responde, que llega al servicio y con que cabeceras se descargan los
 * PDF.
 *
 * El controlador no decide nada de negocio; aqui solo se comprueba que traduce
 * y delega. Que la previsualizacion no cierre la caja se verifica en las dos
 * capas: aqui, viendo que exportar el borrador nunca llama a `close`, y contra
 * la base de datos en las pruebas del servicio.
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

    // ── Datos ────────────────────────────────────────────────────

    private static final String VALID_BODY = """
        { "openingCash": 500.00, "countedCash": 7480.00, "notes": "  Sin novedad  " }
    """;

    private CashClosureBasePreviewResponseDTO basePreview(boolean closed, UUID closureId) {
        return new CashClosureBasePreviewResponseDTO(
                BUSINESS_DATE, invoicing(), collections(), receivables(),
                closed, closureId);
    }

    private CashClosureInvoicingDTO invoicing() {
        return new CashClosureInvoicingDTO(15, new BigDecimal("12000.00"),
                3, new BigDecimal("8000.00"), new BigDecimal("20000.00"));
    }

    private CashClosureCollectionsDTO collections() {
        return new CashClosureCollectionsDTO(
                10, new BigDecimal("7000.00"),
                6, new BigDecimal("6000.00"),
                4, new BigDecimal("5000.00"),
                new BigDecimal("18000.00"));
    }

    private CashClosureReceivablesDTO receivables() {
        return new CashClosureReceivablesDTO(2, new BigDecimal("3000.00"),
                new BigDecimal("1000.00"), new BigDecimal("25000.00"));
    }

    private CashClosureResponseDTO closure(UUID id) {
        return new CashClosureResponseDTO(
                id, BUSINESS_DATE,
                id == null ? null : LocalDateTime.of(2026, 8, 20, 19, 0),
                id == null ? null : "ANA LOPEZ",
                id != null,
                invoicing(), collections(), receivables(),
                new CashReconciliationDTO(
                        new BigDecimal("500.00"), new BigDecimal("7500.00"),
                        new BigDecimal("7480.00"), new BigDecimal("-20.00"),
                        CashBalanceStatus.SHORT),
                null);
    }

    private CashClosureDocumentDTO document(String fileName) {
        return new CashClosureDocumentDTO(
                fileName, "%PDF-1.6\nfake".getBytes(StandardCharsets.US_ASCII));
    }

    // ── GET preview ──────────────────────────────────────────────

    @Test
    void theBasePreviewIsServedWithoutAnyWorkshopParameter() throws Exception {
        when(service.getPreview()).thenReturn(basePreview(false, null));

        mockMvc.perform(get("/v1/cash-closures/preview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessDate").value("2026-08-20"))
                .andExpect(jsonPath("$.invoicing.totalInvoiced").value(20000.00))
                .andExpect(jsonPath("$.collections.totalCollected").value(18000.00))
                .andExpect(jsonPath("$.receivables.pendingAmount").value(25000.00))
                .andExpect(jsonPath("$.alreadyClosed").value(false))
                .andExpect(jsonPath("$.closureId").doesNotExist());
    }

    /** `/preview` es un segmento literal: nunca lo captura `/{id}`. */
    @Test
    void theLiteralPreviewSegmentWinsOverThePathVariable() throws Exception {
        when(service.getPreview()).thenReturn(basePreview(false, null));

        mockMvc.perform(get("/v1/cash-closures/preview")).andExpect(status().isOk());

        verify(service).getPreview();
        verify(service, never()).get(any());
    }

    @Test
    void anAlreadyClosedDayIsReportedWithItsClosureIdentifier() throws Exception {
        UUID closureId = UUID.randomUUID();
        when(service.getPreview()).thenReturn(basePreview(true, closureId));

        mockMvc.perform(get("/v1/cash-closures/preview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alreadyClosed").value(true))
                .andExpect(jsonPath("$.closureId").value(closureId.toString()));
    }

    // ── POST preview ─────────────────────────────────────────────

    @Test
    void theFinalPreviewReturnsTheDocumentWithoutIdentityOrClosureTimestamp()
            throws Exception {
        when(service.preview(any())).thenReturn(closure(null));

        mockMvc.perform(post("/v1/cash-closures/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.closedAt").doesNotExist())
                .andExpect(jsonPath("$.cashReconciliation.expectedCash").value(7500.00))
                .andExpect(jsonPath("$.cashReconciliation.difference").value(-20.00))
                .andExpect(jsonPath("$.cashReconciliation.status").value("SHORT"));

        verify(service, never()).close(any());
    }

    @Test
    void aNegativeOrMalformedArqueoIsRejectedBeforeReachingTheService()
            throws Exception {
        mockMvc.perform(post("/v1/cash-closures/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"openingCash\": -1, \"countedCash\": 100}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.openingCash").exists());

        mockMvc.perform(post("/v1/cash-closures/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"countedCash\": 100}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.openingCash").exists());

        mockMvc.perform(post("/v1/cash-closures/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"openingCash\": 1.234, \"countedCash\": 100}"))
                .andExpect(status().isBadRequest());

        verify(service, never()).preview(any());
    }

    /** El servidor no acepta totales del cliente: si los manda, se ignoran. */
    @Test
    void figuresSentByTheClientAreNotEvenPartOfTheContract() throws Exception {
        when(service.preview(any())).thenReturn(closure(null));

        mockMvc.perform(post("/v1/cash-closures/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                              "openingCash": 500.00,
                              "countedCash": 7480.00,
                              "totalCollected": 999999.00,
                              "expectedCash": 1.00,
                              "difference": 0.00,
                              "pendingExchangeAmount": 0.00
                            }
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.collections.totalCollected").value(18000.00))
                .andExpect(jsonPath("$.cashReconciliation.expectedCash").value(7500.00));
    }

    // ── PDF de previsualizacion ──────────────────────────────────

    @Test
    void thePreviewPdfIsDownloadedAsAnAttachmentAndNeverClosesTheRegister()
            throws Exception {
        when(service.previewDocument(any()))
                .thenReturn(document("previsualizacion-cierre-caja-2026-08-20.pdf"));

        MvcResult result = mockMvc.perform(post("/v1/cash-closures/preview/pdf")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"previsualizacion-cierre-caja-2026-08-20.pdf\""))
                .andReturn();

        byte[] body = result.getResponse().getContentAsByteArray();
        assertThat(body).isNotEmpty();
        assertThat(new String(body, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");

        verify(service).previewDocument(any());
        verify(service, never()).close(any());
    }

    // ── Cierre ───────────────────────────────────────────────────

    @Test
    void closingRespondsCreatedWithTheConfirmedSnapshot() throws Exception {
        UUID closureId = UUID.randomUUID();
        when(service.close(any())).thenReturn(closure(closureId));

        mockMvc.perform(post("/v1/cash-closures")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(closureId.toString()))
                .andExpect(jsonPath("$.closedAt").exists())
                .andExpect(jsonPath("$.closedBy").value("ANA LOPEZ"))
                .andExpect(jsonPath("$.alreadyClosed").value(true));
    }

    @Test
    void closingTwiceIsAnUnderstandableBadRequestAndNotAServerError()
            throws Exception {
        when(service.close(any())).thenThrow(new ValidationException(
                "La caja de este taller ya fue cerrada para la fecha 2026-08-20"));

        mockMvc.perform(post("/v1/cash-closures")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        "La caja de este taller ya fue cerrada para la fecha 2026-08-20"));
    }

    /** Las observaciones se recortan y una nota en blanco se guarda como null. */
    @Test
    void notesAreTrimmedAndBlankNotesBecomeNull() throws Exception {
        when(service.close(any())).thenReturn(closure(UUID.randomUUID()));

        var captor = org.mockito.ArgumentCaptor.forClass(
                com.cns.spring_house.cashclosure.dto.CreateCashClosureRequestDTO.class);

        mockMvc.perform(post("/v1/cash-closures")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/v1/cash-closures")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"openingCash\":0,\"countedCash\":0,\"notes\":\"   \"}"))
                .andExpect(status().isCreated());

        verify(service, org.mockito.Mockito.times(2)).close(captor.capture());
        assertThat(captor.getAllValues().get(0).notes()).isEqualTo("Sin novedad");
        assertThat(captor.getAllValues().get(1).notes()).isNull();
    }

    // ── Historico ────────────────────────────────────────────────

    @Test
    void aHistoricalClosureIsServedFromItsSnapshot() throws Exception {
        UUID closureId = UUID.randomUUID();
        when(service.get(closureId)).thenReturn(closure(closureId));

        mockMvc.perform(get("/v1/cash-closures/" + closureId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(closureId.toString()))
                .andExpect(jsonPath("$.invoicing.totalInvoiced").value(20000.00));
    }

    /** Un cierre de otro taller responde igual que uno inexistente. */
    @Test
    void aClosureFromAnotherWorkshopIsIndistinguishableFromOneThatDoesNotExist()
            throws Exception {
        UUID closureId = UUID.randomUUID();
        when(service.get(closureId)).thenThrow(
                new EntityNotFoundException("No existe el cierre de caja especificado"));

        mockMvc.perform(get("/v1/cash-closures/" + closureId))
                .andExpect(status().isNotFound());
    }

    @Test
    void theHistoricalPdfIsDownloadedWithItsBusinessDateInTheFileName()
            throws Exception {
        UUID closureId = UUID.randomUUID();
        when(service.document(closureId))
                .thenReturn(document("cierre-caja-2026-08-20.pdf"));

        mockMvc.perform(get("/v1/cash-closures/" + closureId + "/pdf"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"cierre-caja-2026-08-20.pdf\""));
    }

    @Test
    void aClosureCanBeLookedUpByItsBusinessDate() throws Exception {
        UUID closureId = UUID.randomUUID();
        when(service.getByDate(BUSINESS_DATE)).thenReturn(closure(closureId));

        mockMvc.perform(get("/v1/cash-closures/by-date").param("date", "2026-08-20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessDate").value("2026-08-20"));

        verify(service).getByDate(BUSINESS_DATE);
    }

    @Test
    void theRangeListingIsDelegatedWithBothEnds() throws Exception {
        when(service.getRange(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 3)))
                .thenReturn(List.of(closure(UUID.randomUUID())));

        mockMvc.perform(get("/v1/cash-closures")
                        .param("from", "2026-08-01")
                        .param("to", "2026-08-03"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        verify(service).getRange(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 3));
    }

    // ── Resumen ──────────────────────────────────────────────────

    @Test
    void theRangeSummaryReportsTheEndingPortfolioAndTheBalanceCounts()
            throws Exception {
        when(service.getSummary(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31)))
                .thenReturn(new CashClosureSummaryResponseDTO(
                        LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), 25,
                        invoicing(), collections(),
                        new CashClosureRangeReceivablesDTO(
                                12, new BigDecimal("23000.00"),
                                new BigDecimal("25000.00")),
                        new CashClosureBalanceSummaryDTO(
                                20, 3, 2, new BigDecimal("-15.00")),
                        List.of()));

        mockMvc.perform(get("/v1/cash-closures/summary")
                        .param("from", "2026-08-01")
                        .param("to", "2026-08-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.closureCount").value(25))
                .andExpect(jsonPath("$.receivables.endingPendingAmount").value(25000.00))
                .andExpect(jsonPath("$.balance.balancedClosures").value(20))
                .andExpect(jsonPath("$.balance.shortClosures").value(3))
                .andExpect(jsonPath("$.balance.overClosures").value(2))
                .andExpect(jsonPath("$.balance.netCashDifference").value(-15.00));
    }

    @Test
    void theRangeSummaryPdfIsDownloadedWithBothEndsInTheFileName() throws Exception {
        when(service.summaryDocument(
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31)))
                .thenReturn(document("resumen-caja-2026-08-01-a-2026-08-31.pdf"));

        mockMvc.perform(get("/v1/cash-closures/summary/pdf")
                        .param("from", "2026-08-01")
                        .param("to", "2026-08-31"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"resumen-caja-2026-08-01-a-2026-08-31.pdf\""));
    }

    @Test
    void anInvertedRangeIsAValidationErrorAndNotAServerError() throws Exception {
        when(service.getSummary(any(), any())).thenThrow(new ValidationException(
                "La fecha inicial no puede ser posterior a la final"));

        mockMvc.perform(get("/v1/cash-closures/summary")
                        .param("from", "2026-08-31")
                        .param("to", "2026-08-01"))
                .andExpect(status().isBadRequest());
    }

}
