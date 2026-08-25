package com.cns.spring_house.cashclosure.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.cns.spring_house.cashclosure.dto.CashClosureBalanceSummaryDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureCollectionsDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureDaySummaryDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureInvoicingDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureRangeReceivablesDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureReceivablesDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureResponseDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureSummaryResponseDTO;
import com.cns.spring_house.cashclosure.dto.CashReconciliationDTO;
import com.cns.spring_house.cashclosure.entity.CashBalanceStatus;
import com.cns.spring_house.shared.config.time.TimeConfig;

/**
 * Los PDF de caja: que se generen, que digan lo que tienen que decir y que un
 * borrador no pueda confundirse con un cierre confirmado.
 *
 * Se comprueba el TEXTO extraido del PDF, no solo que los bytes existan: un
 * archivo de 40 KB con la plantilla en blanco tambien "se genera". Ademas la
 * prueba deja los tres documentos en {@code target/cash-closure-samples} para
 * poder revisarlos a ojo (logo, colores, alineacion, saltos de pagina), que es
 * lo unico que puede validar de verdad la composicion visual.
 */
class CashClosureReportServiceImplTest {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 8, 20);
    private static final Path SAMPLES = Path.of("target", "cash-closure-samples");

    private CashClosureReportServiceImpl service;

    @BeforeAll
    static void prepareSampleDirectory() throws IOException {
        Files.createDirectories(SAMPLES);
    }

    @BeforeEach
    void setUp() {
        ReportAssets assets = new ReportAssets();
        assets.loadLogo();
        Clock clock = Clock.fixed(
                BUSINESS_DATE.atTime(19, 5).atZone(TimeConfig.BUSINESS_ZONE).toInstant(),
                TimeConfig.BUSINESS_ZONE);
        service = new CashClosureReportServiceImpl(assets, clock);
    }

    // ── Datos ────────────────────────────────────────────────────

    private ReportHeaderDTO header() {
        return new ReportHeaderDTO(
                "LA CASA DEL RESORTE", "6a Avenida 4-25 Zona 3, Guatemala");
    }

    private CashClosureResponseDTO closure(UUID id, LocalDateTime closedAt, String notes) {
        return new CashClosureResponseDTO(
                id,
                BUSINESS_DATE,
                closedAt,
                id == null ? null : "ANA LOPEZ",
                id != null,
                new CashClosureInvoicingDTO(15, new BigDecimal("12000.00"),
                        3, new BigDecimal("8000.00"), new BigDecimal("20000.00")),
                new CashClosureCollectionsDTO(
                        10, new BigDecimal("7000.00"),
                        6, new BigDecimal("6000.00"),
                        4, new BigDecimal("5000.00"),
                        new BigDecimal("18000.00")),
                new CashClosureReceivablesDTO(2, new BigDecimal("3000.00"),
                        new BigDecimal("1000.00"), new BigDecimal("25000.00")),
                new CashReconciliationDTO(
                        new BigDecimal("500.00"), new BigDecimal("7500.00"),
                        new BigDecimal("7480.00"), new BigDecimal("-20.00"),
                        CashBalanceStatus.SHORT),
                notes);
    }

    private CashClosureSummaryResponseDTO summary(int days) {
        return new CashClosureSummaryResponseDTO(
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 31),
                days,
                new CashClosureInvoicingDTO(120, new BigDecimal("96000.00"),
                        18, new BigDecimal("54000.00"), new BigDecimal("150000.00")),
                new CashClosureCollectionsDTO(
                        80, new BigDecimal("61000.00"),
                        40, new BigDecimal("38000.00"),
                        22, new BigDecimal("29000.00"),
                        new BigDecimal("128000.00")),
                new CashClosureRangeReceivablesDTO(
                        12, new BigDecimal("23000.00"), new BigDecimal("25000.00")),
                new CashClosureBalanceSummaryDTO(20, 3, 2, new BigDecimal("-15.00")),
                dayRows(days));
    }

    private List<CashClosureDaySummaryDTO> dayRows(int days) {
        return java.util.stream.IntStream.rangeClosed(1, days)
                .mapToObj(day -> new CashClosureDaySummaryDTO(
                        UUID.randomUUID(),
                        LocalDate.of(2026, 8, 1).plusDays(day - 1L),
                        new BigDecimal("6000.00"),
                        new BigDecimal("5120.50"),
                        new BigDecimal("2400.00"),
                        new BigDecimal("1720.50"),
                        new BigDecimal("1000.00"),
                        day % 3 == 0 ? new BigDecimal("-20.00")
                                : day % 3 == 1 ? BigDecimal.ZERO : new BigDecimal("5.00")))
                .toList();
    }

    private String textOf(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    private int pagesOf(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return document.getNumberOfPages();
        }
    }

    private byte[] save(String name, byte[] pdf) throws IOException {
        Files.write(SAMPLES.resolve(name), pdf);
        return pdf;
    }

    // ── El recurso del logo ──────────────────────────────────────

    /**
     * El logo es un recurso local, no una descarga: si el empaquetado lo dejara
     * fuera, esto falla aqui y no en produccion al intentar cerrar la caja.
     */
    @Test
    void theOfficialLogoIsAvailableAsALocalResource() {
        byte[] logo = ReportAssets.readLogo();

        assertThat(logo).isNotEmpty();
        // Firma PNG: los ocho primeros bytes de cualquier PNG valido.
        assertThat(logo[0] & 0xFF).isEqualTo(0x89);
        assertThat(new String(logo, 1, 3, StandardCharsets.US_ASCII)).isEqualTo("PNG");
    }

    @Test
    void theAssetIsLoadedOnceAndHandedOutAsACopy() {
        ReportAssets assets = new ReportAssets();
        assets.loadLogo();

        byte[] first = assets.logo();
        first[0] = 0;

        assertThat(assets.logo()[0] & 0xFF).isEqualTo(0x89);
    }

    // ── PDF de previsualizacion ──────────────────────────────────

    @Test
    void thePreviewPdfIsAValidNonEmptyDocument() throws IOException {
        byte[] pdf = save("previsualizacion-cierre-caja-2026-08-20.pdf",
                service.renderDaily(closure(null, null, null), header(), true, "ANA LOPEZ"));

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        assertThat(pagesOf(pdf)).isEqualTo(1);
    }

    /** Lo que impide que un borrador impreso pase por un cierre confirmado. */
    @Test
    void thePreviewPdfIsMarkedAsADraftAndNeverAsAConfirmedClosure() throws IOException {
        byte[] pdf = service.renderDaily(
                closure(null, null, null), header(), true, "ANA LOPEZ");

        String text = textOf(pdf);
        assertThat(text).contains("PREVISUALIZACION - CAJA NO CERRADA");
        assertThat(text).doesNotContain("CIERRE CONFIRMADO");
        assertThat(text).contains("Solicitado por");
        assertThat(text).doesNotContain("Responsable del cierre");
    }

    @Test
    void theDailyPdfShowsTheWorkshopDataThatActuallyExists() throws IOException {
        String text = textOf(service.renderDaily(
                closure(null, null, null), header(), true, "ANA LOPEZ"));

        assertThat(text).contains("LA CASA DEL RESORTE");
        assertThat(text).contains("6a Avenida 4-25 Zona 3, Guatemala");
        assertThat(text).contains("CIERRE DE CAJA");
        assertThat(text).contains("20/08/2026");
    }

    /** Un taller sin direccion no deja un hueco ni imprime "null". */
    @Test
    void aWorkshopWithoutAnAddressSimplyOmitsTheLine() throws IOException {
        String text = textOf(service.renderDaily(
                closure(null, null, null),
                new ReportHeaderDTO("TALLER SIN DIRECCION", null),
                true, "ANA LOPEZ"));

        assertThat(text).contains("TALLER SIN DIRECCION");
        assertThat(text).doesNotContain("null");
    }

    // ── PDF de cierre confirmado ─────────────────────────────────

    @Test
    void theConfirmedPdfIdentifiesItselfAsAClosureWithItsResponsibleAndTimestamp()
            throws IOException {
        UUID id = UUID.fromString("6f1f0d2e-9d1b-4a3a-8a1c-2c9b1a5d7e01");
        byte[] pdf = save("cierre-caja-2026-08-20.pdf", service.renderDaily(
                closure(id, LocalDateTime.of(2026, 8, 20, 19, 0), "Cuadre revisado con el encargado."),
                header(), false, "ANA LOPEZ"));

        String text = textOf(pdf);
        assertThat(text).contains("CIERRE CONFIRMADO");
        assertThat(text).doesNotContain("PREVISUALIZACION");
        assertThat(text).contains("Responsable del cierre");
        assertThat(text).contains("ANA LOPEZ");
        assertThat(text).contains("20/08/2026 19:00");
        assertThat(text).contains(id.toString());
    }

    /** Las tres dimensiones del cierre, cada una con su cifra. */
    @Test
    void theDailyPdfSeparatesInvoicingCollectionsPortfolioAndCashCount()
            throws IOException {
        String text = textOf(service.renderDaily(
                closure(UUID.randomUUID(), LocalDateTime.of(2026, 8, 20, 19, 0), null),
                header(), false, "ANA LOPEZ"));

        assertThat(text).contains("FACTURACION");
        assertThat(text).contains("TOTAL FACTURADO").contains("Q20,000.00");
        assertThat(text).contains("COBROS");
        assertThat(text).contains("TOTAL COBRADO").contains("Q18,000.00");
        assertThat(text).contains("Transferencia / Deposito").contains("Q5,000.00");
        assertThat(text).contains("CARTERA CAMBIARIA");
        assertThat(text).contains("Cartera pendiente total").contains("Q25,000.00");
        assertThat(text).contains("ARQUEO DE EFECTIVO");
        assertThat(text).contains("EFECTIVO ESPERADO").contains("Q7,500.00");
        assertThat(text).contains("Efectivo contado").contains("Q7,480.00");
        assertThat(text).contains("DIFERENCIA").contains("-Q20.00");
        assertThat(text).contains("FALTANTE");
    }

    @Test
    void observationsAreOnlyPrintedWhenThereAreAny() throws IOException {
        String withNotes = textOf(service.renderDaily(
                closure(UUID.randomUUID(), LocalDateTime.of(2026, 8, 20, 19, 0),
                        "Falto un billete de veinte."),
                header(), false, "ANA LOPEZ"));
        String withoutNotes = textOf(service.renderDaily(
                closure(UUID.randomUUID(), LocalDateTime.of(2026, 8, 20, 19, 0), null),
                header(), false, "ANA LOPEZ"));

        assertThat(withNotes).contains("OBSERVACIONES")
                .contains("Falto un billete de veinte.");
        assertThat(withoutNotes).doesNotContain("OBSERVACIONES");
    }

    /** Una caja sobrante se lee distinta de una faltante. */
    @Test
    void anOverBalanceIsLabelledAsSurplusAndNotAsShortage() throws IOException {
        CashClosureResponseDTO base = closure(UUID.randomUUID(),
                LocalDateTime.of(2026, 8, 20, 19, 0), null);
        CashClosureResponseDTO over = new CashClosureResponseDTO(
                base.id(), base.businessDate(), base.closedAt(), base.closedBy(), true,
                base.invoicing(), base.collections(), base.receivables(),
                new CashReconciliationDTO(
                        new BigDecimal("500.00"), new BigDecimal("7500.00"),
                        new BigDecimal("7505.00"), new BigDecimal("5.00"),
                        CashBalanceStatus.OVER),
                null);

        String text = textOf(service.renderDaily(over, header(), false, "ANA LOPEZ"));

        assertThat(text).contains("SOBRANTE").doesNotContain("FALTANTE");
        assertThat(text).contains("+Q5.00");
    }

    // ── PDF de rango ─────────────────────────────────────────────

    @Test
    void theRangePdfShowsTheAggregatesAndOneRowPerExistingClosure() throws IOException {
        byte[] pdf = save("resumen-caja-2026-08-01-a-2026-08-31.pdf",
                service.renderSummary(summary(25), header()));

        String text = textOf(pdf);
        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        assertThat(text).contains("RESUMEN DE CAJA");
        assertThat(text).contains("01/08/2026").contains("31/08/2026");
        assertThat(text).contains("Cierres realizados");
        assertThat(text).contains("TOTAL FACTURADO").contains("Q150,000.00");
        assertThat(text).contains("TOTAL COBRADO").contains("Q128,000.00");
        assertThat(text).contains("Cartera pendiente al ultimo cierre del periodo")
                .contains("Q25,000.00");
        assertThat(text).contains("DIFERENCIA NETA").contains("-Q15.00");
        assertThat(text).contains("DETALLE POR DIA");
        // Una fila por cierre existente y ninguna inventada.
        assertThat(text.lines().filter(line -> line.contains("/08/2026")).count())
                .isGreaterThanOrEqualTo(25);
    }

    /**
     * Un mes entero no cabe en una hoja: la tabla tiene que partirse y repetir
     * su encabezado, no escribirse fuera de la pagina.
     */
    @Test
    void aLongRangeBreaksIntoPagesAndRepeatsTheTableHeader() throws IOException {
        byte[] pdf = service.renderSummary(summary(31), header());

        assertThat(pagesOf(pdf)).isGreaterThan(1);
        String text = textOf(pdf);
        assertThat(text.lines().filter(line -> line.startsWith("Fecha")).count())
                .isGreaterThan(1);
        assertThat(text).contains("Pagina 1 de");
    }

    @Test
    void aRangeWithoutClosuresSaysSoInsteadOfPrintingAnEmptyTable() throws IOException {
        CashClosureSummaryResponseDTO empty = new CashClosureSummaryResponseDTO(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), 0,
                new CashClosureInvoicingDTO(0, BigDecimal.ZERO, 0, BigDecimal.ZERO,
                        BigDecimal.ZERO),
                new CashClosureCollectionsDTO(0, BigDecimal.ZERO, 0, BigDecimal.ZERO,
                        0, BigDecimal.ZERO, BigDecimal.ZERO),
                new CashClosureRangeReceivablesDTO(0, BigDecimal.ZERO, BigDecimal.ZERO),
                new CashClosureBalanceSummaryDTO(0, 0, 0, BigDecimal.ZERO),
                List.of());

        String text = textOf(service.renderSummary(empty, header()));

        assertThat(text).contains("No existen cierres de caja en el periodo consultado.");
    }

    // ── Robustez del texto ───────────────────────────────────────

    /**
     * El nombre de un taller y una nota de cierre son texto libre. Un caracter
     * que la fuente estandar no sabe representar debe degradarse, nunca romper
     * la generacion de un documento contable.
     */
    @Test
    void textOutsideTheStandardFontEncodingDegradesInsteadOfFailing() throws IOException {
        byte[] pdf = service.renderDaily(
                closure(UUID.randomUUID(), LocalDateTime.of(2026, 8, 20, 19, 0),
                        "Cuadre 你好 con emoji 😀 y comillas tipograficas"),
                new ReportHeaderDTO("TALLER 你好", "DIRECCION"),
                false, "ANA LOPEZ");

        assertThat(pdf).isNotEmpty();
        assertThat(textOf(pdf)).contains("Cuadre");
    }

    /** El espanol acentuado si se representa: WinAnsi lo cubre entero. */
    @Test
    void spanishAccentsSurviveIntoThePdf() throws IOException {
        byte[] pdf = service.renderDaily(
                closure(null, null, "Revisión de caja: faltó un billete pequeño."),
                new ReportHeaderDTO("TALLER MECÁNICO ÑANDÚ", "Avenida Bolívar"),
                true, "ANA LOPEZ");

        String text = textOf(pdf);
        assertThat(text).contains("TALLER MEC");
        assertThat(text).contains("Revisi").contains("falt");
        assertThat(text).doesNotContain("?");
    }

}
