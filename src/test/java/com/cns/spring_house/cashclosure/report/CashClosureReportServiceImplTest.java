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

import com.cns.spring_house.cashclosure.dto.CashClosureExchangeDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureCollectionsDTO;
import com.cns.spring_house.cashclosure.dto.ClosureExchangeInvoiceDTO;
import com.cns.spring_house.cashclosure.dto.ClosurePaymentMethodDTO;
import com.cns.spring_house.cashclosure.dto.ClosureSoldItemDTO;
import com.cns.spring_house.family.entity.FamilyCategory;
import com.cns.spring_house.sale.entity.PaymentMethod;
import com.cns.spring_house.cashclosure.dto.CashClosureInvoicingDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureResponseDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureRefundsDTO;
import com.cns.spring_house.cashclosure.dto.ClosureRefundMethodDTO;
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

    /**
     * El dia del ejemplo: Q5,000 en facturas normales, Q2,000 emitidos a
     * credito y sin cobrar, y Q1,000 de una cambiaria antigua cobrada hoy. El
     * total cobrado es Q6,000.
     */
    private CashClosureResponseDTO closure(UUID id, LocalDateTime closedAt) {
        return new CashClosureResponseDTO(
                id,
                BUSINESS_DATE,
                closedAt,
                id == null ? null : "ANA LOPEZ",
                id != null,
                List.of(
                        new ClosureSoldItemDTO(UUID.randomUUID(), "RESORTE DELANTERO",
                                FamilyCategory.NORMAL, 4L, new BigDecimal("5000.00")),
                        new ClosureSoldItemDTO(UUID.randomUUID(), "INSTALACION",
                                FamilyCategory.JOB, 2L, new BigDecimal("2000.00"))),
                new BigDecimal("7000.00"),
                new CashClosureInvoicingDTO(1L, new BigDecimal("5000.00"),
                        1L, new BigDecimal("2000.00"), new BigDecimal("7000.00")),
                new CashClosureExchangeDTO(
                        1L, new BigDecimal("2000.00"), new BigDecimal("2000.00"),
                        1L, new BigDecimal("1000.00"), new BigDecimal("1000.00"),
                        List.of(new ClosureExchangeInvoiceDTO(
                                UUID.randomUUID(), "A1B2C3D4", "TRANSPORTES DEL NORTE",
                                BUSINESS_DATE.atTime(11, 0), new BigDecimal("2000.00"),
                                false, null, null)),
                        List.of(new ClosureExchangeInvoiceDTO(
                                UUID.randomUUID(), "E5F6A7B8", "FERRETERIA CENTRAL",
                                BUSINESS_DATE.minusDays(3).atTime(9, 30),
                                new BigDecimal("1000.00"), true,
                                BUSINESS_DATE.atTime(15, 0), PaymentMethod.CASH))),
                new CashClosureCollectionsDTO(
                        List.of(
                                new ClosurePaymentMethodDTO(PaymentMethod.CASH, 2L,
                                        new BigDecimal("6000.00")),
                                new ClosurePaymentMethodDTO(PaymentMethod.CARD, 0L,
                                        new BigDecimal("0.00")),
                                new ClosurePaymentMethodDTO(PaymentMethod.TRANSFER, 0L,
                                        new BigDecimal("0.00"))),
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

    private String textOf(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    /**
     * El mismo dia, pero con Q1,500 devueltos: Q500 en efectivo, Q800 con
     * tarjeta y Q200 por transferencia. El neto es Q4,500.
     */
    private CashClosureResponseDTO closureWithRefunds() {
        CashClosureResponseDTO base = closure(UUID.randomUUID(), LocalDateTime.of(2026, 8, 20, 19, 0));
        return new CashClosureResponseDTO(
                base.id(), base.businessDate(), base.closedAt(), base.closedBy(),
                base.alreadyClosed(), base.items(), base.itemsBilledAmount(),
                base.invoicing(), base.exchange(), base.collections(),
                new CashClosureRefundsDTO(
                        List.of(
                                new ClosureRefundMethodDTO(
                                        PaymentMethod.CASH, 1L, new BigDecimal("500.00")),
                                new ClosureRefundMethodDTO(
                                        PaymentMethod.CARD, 1L, new BigDecimal("800.00")),
                                new ClosureRefundMethodDTO(
                                        PaymentMethod.TRANSFER, 1L, new BigDecimal("200.00"))),
                        3L,
                        new BigDecimal("1500.00"),
                        List.of()),
                new BigDecimal("4500.00"));
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

    /**
     * El PDF no puede decir "TOTAL COBRADO Q6,000" y callarse que se
     * devolvieron Q1,500.
     *
     * Seria exacto y enganoso a la vez: quien lo firma tiene que ver en la
     * misma hoja de que Q6,000 y que Q1,500 sale el Q4,500 con el que se
     * queda.
     */
    @Test
    void theDailyPdfShowsRefundsAndTheNetBesideWhatWasCollected() throws Exception {
        byte[] pdf = save("cierre-con-devoluciones.pdf", service.renderDaily(
                closureWithRefunds(), header(), false, "ANA LOPEZ"));

        String text = textOf(pdf);

        assertThat(text).contains("DEVOLUCIONES DEL PERIODO");
        assertThat(text).contains("TOTAL DEVUELTO");
        assertThat(text).contains("1,500.00");
        assertThat(text).contains("NETO DEL PERIODO");
        assertThat(text).contains("4,500.00");

        // Y lo cobrado sigue siendo lo cobrado, no el neto.
        assertThat(text).contains("TOTAL COBRADO");
        assertThat(text).contains("6,000.00");
    }

    /** Un dia sin devoluciones lo dice con ceros, no omitiendo la seccion. */
    @Test
    void theDailyPdfStillCarriesTheRefundSectionWhenThereWereNone() throws Exception {
        byte[] pdf = service.renderDaily(
                closure(UUID.randomUUID(), LocalDateTime.of(2026, 8, 20, 19, 0)),
                header(), false, "ANA LOPEZ");

        String text = textOf(pdf);

        assertThat(text).contains("DEVOLUCIONES DEL PERIODO");
        assertThat(text).contains("NETO DEL PERIODO");
    }

    // ── El recurso del logo ──────────────────────────────────────

    /**
     * El logo es un recurso local, no una descarga: si el empaquetado lo dejara
     * fuera, esto falla aqui y no en produccion al intentar cerrar la caja.
     */
    @Test
    void theOfficialLogoIsAvailableAsALocalResource() {
        assertThat(ReportAssets.readLogo()).isNotEmpty();
    }

    // ── PDF de previsualizacion ──────────────────────────────────

    @Test
    void thePreviewPdfIsAValidNonEmptyDocument() throws IOException {
        byte[] pdf = save("previsualizacion.pdf", service.renderDaily(
                closure(null, null), header(), true, "ANA LOPEZ"));

        assertThat(pdf).isNotEmpty();
        assertThat(pagesOf(pdf)).isPositive();
    }

    /**
     * Un borrador impreso no puede confundirse con un cierre confirmado: lleva
     * la marca, y no dice ser un cierre.
     */
    @Test
    void thePreviewPdfIsMarkedAsADraft() throws IOException {
        String text = textOf(service.renderDaily(
                closure(null, null), header(), true, "ANA LOPEZ"));

        assertThat(text).contains("PREVISUALIZACION");
        assertThat(text).doesNotContain("Cierre confirmado");
    }

    // ── PDF de cierre confirmado ─────────────────────────────────

    @Test
    void theConfirmedPdfIdentifiesItselfWithItsResponsible() throws IOException {
        String text = textOf(save("cierre-confirmado.pdf", service.renderDaily(
                closure(UUID.randomUUID(), LocalDateTime.of(2026, 8, 20, 19, 0)),
                header(), false, "ANA LOPEZ")));

        assertThat(text).contains("ANA LOPEZ");
        assertThat(text).doesNotContain("PREVISUALIZACION");
    }

    // ── Estructura contable del documento ────────────────────────

    /**
     * El documento responde las cuatro preguntas del cierre, en su orden: que se
     * vendio, cuanto se emitio, que quedo a credito y como entro el dinero.
     */
    @Test
    void theDailyPdfCarriesTheFourAccountingSections() throws IOException {
        String text = textOf(service.renderDaily(
                closure(UUID.randomUUID(), LocalDateTime.of(2026, 8, 20, 19, 0)),
                header(), false, "ANA LOPEZ"));

        assertThat(text).contains("ARTICULOS VENDIDOS");
        assertThat(text).contains("FACTURACION EMITIDA");
        assertThat(text).contains("FACTURAS CAMBIARIAS");
        assertThat(text).contains("COBROS POR FORMA DE PAGO");
        assertThat(text).contains("TOTAL COBRADO");
    }

    /** Los articulos salen con su nombre, su tipo y lo que generaron. */
    @Test
    void theDailyPdfListsTheSoldItems() throws IOException {
        String text = textOf(service.renderDaily(
                closure(UUID.randomUUID(), LocalDateTime.of(2026, 8, 20, 19, 0)),
                header(), false, "ANA LOPEZ"));

        assertThat(text).contains("RESORTE DELANTERO");
        assertThat(text).contains("INSTALACION");
        // Los servicios se distinguen de la mercaderia, pero no desaparecen.
        assertThat(text).contains("Servicio");
        assertThat(text).contains("Producto");
    }

    /**
     * El estado de cada cambiaria se imprime con su PALABRA, no solo con un
     * color: un PDF se imprime en blanco y negro y el color no sobrevive.
     */
    @Test
    void theDailyPdfShowsExchangeStatusAsText() throws IOException {
        String text = textOf(service.renderDaily(
                closure(UUID.randomUUID(), LocalDateTime.of(2026, 8, 20, 19, 0)),
                header(), false, "ANA LOPEZ"));

        assertThat(text).contains("Pendiente");
        assertThat(text).contains("Cobrada");
        // Y el documento se identifica por su codigo corto, no por el UUID.
        assertThat(text).contains("A1B2C3D4");
        assertThat(text).contains("TRANSPORTES DEL NORTE");
    }

    /**
     * El arqueo manual desaparecio del modelo. Si alguien lo reintrodujera en la
     * plantilla, esta prueba lo detecta.
     */
    @Test
    void theDailyPdfHasNoCashCountSection() throws IOException {
        String text = textOf(service.renderDaily(
                closure(UUID.randomUUID(), LocalDateTime.of(2026, 8, 20, 19, 0)),
                header(), false, "ANA LOPEZ"));

        assertThat(text).doesNotContain("ARQUEO");
        assertThat(text).doesNotContain("Fondo inicial");
        assertThat(text).doesNotContain("Efectivo contado");
        assertThat(text).doesNotContain("OBSERVACIONES");
        // Y tampoco la cartera GLOBAL del taller.
        assertThat(text).doesNotContain("Cartera pendiente total");
    }

    // ── Robustez del texto ───────────────────────────────────────

    /**
     * El nombre de un taller es texto libre. Un caracter que la fuente estandar
     * no sabe representar debe degradarse, nunca romper la generacion de un
     * documento contable.
     */
    @Test
    void textOutsideTheStandardFontEncodingDegradesInsteadOfFailing() throws IOException {
        byte[] pdf = service.renderDaily(
                closure(UUID.randomUUID(), LocalDateTime.of(2026, 8, 20, 19, 0)),
                new ReportHeaderDTO("TALLER \u4f60\u597d", "DIRECCION"),
                false, "ANA LOPEZ");

        assertThat(pdf).isNotEmpty();
        assertThat(textOf(pdf)).contains("TOTAL COBRADO");
    }

    /** El espanol acentuado si se representa: WinAnsi lo cubre entero. */
    @Test
    void spanishAccentsSurviveIntoThePdf() throws IOException {
        byte[] pdf = service.renderDaily(
                closure(null, null),
                new ReportHeaderDTO("TALLER MEC\u00c1NICO \u00d1AND\u00da", "Avenida Bol\u00edvar"),
                true, "ANA LOPEZ");

        String text = textOf(pdf);
        assertThat(text).contains("TALLER MEC");
        assertThat(text).doesNotContain("?");
    }

}
