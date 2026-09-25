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
import com.cns.spring_house.cashclosure.dto.CashClosureProductGroupDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureProductSummaryDTO;
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

    // ── SEGUNDO PDF: resumen de productos ────────────────────────
    //
    // Se comprueba el TEXTO extraido, no que el archivo pese: lo que el taller
    // va a leer son estas filas, y una plantilla en blanco tambien genera bytes.

    private static final UUID CLOSURE_ID =
            UUID.fromString("11111111-2222-3333-4444-555555555555");

    private CashClosureResponseDTO confirmedClosure() {
        return closure(CLOSURE_ID, LocalDateTime.of(2026, 8, 20, 19, 0));
    }

    /**
     * El resumen del enunciado: tres medidas de hoja Toyota Hilux en UNA fila,
     * una Toyota Tacoma aparte, y una familia sin marca ni modelo.
     */
    private CashClosureProductSummaryDTO summary() {
        return new CashClosureProductSummaryDTO(
                List.of(
                        new CashClosureProductGroupDTO(
                                UUID.randomUUID(), "HOJA DE RESORTE",
                                UUID.randomUUID(), "Toyota",
                                UUID.randomUUID(), "Hilux",
                                6L, new BigDecimal("450.00")),
                        new CashClosureProductGroupDTO(
                                UUID.randomUUID(), "HOJA DE RESORTE",
                                UUID.randomUUID(), "Toyota",
                                UUID.randomUUID(), "Tacoma",
                                2L, new BigDecimal("300.00")),
                        new CashClosureProductGroupDTO(
                                UUID.randomUUID(), "PERNO",
                                null, null, null, null,
                                10L, new BigDecimal("115.00"))),
                18L,
                new BigDecimal("865.00"),
                new BigDecimal("2000.00"),
                // Cierre posterior a V37: todo clasificado.
                new BigDecimal("0.00"));
    }

    @Test
    void theProductSummaryPdfIsAValidNonEmptyDocument() throws IOException {
        byte[] pdf = save("resumen-productos.pdf", service.renderProductSummary(
                confirmedClosure(), summary(), header(), "ANA LOPEZ"));

        assertThat(pdf).isNotEmpty();
        assertThat(pagesOf(pdf)).isPositive();
    }

    /** Las columnas del documento, con su titulo. */
    @Test
    void theProductSummaryPdfCarriesItsTitleAndColumns() throws IOException {
        String text = textOf(service.renderProductSummary(
                confirmedClosure(), summary(), header(), "ANA LOPEZ"));

        assertThat(text).contains("RESUMEN DE PRODUCTOS");
        assertThat(text).contains("Familia");
        assertThat(text).contains("Marca");
        assertThat(text).contains("Modelo");
        assertThat(text).contains("Cantidad");
        assertThat(text).contains("Monto generado");
    }

    /**
     * Las tres filas del enunciado, cada una con sus cifras. Es la comprobacion
     * que de verdad importa: que el papel diga lo que la agrupacion decidio.
     */
    @Test
    void theProductSummaryPdfPrintsEachGroupWithItsFigures() throws IOException {
        String text = textOf(service.renderProductSummary(
                confirmedClosure(), summary(), header(), "ANA LOPEZ"));

        assertThat(text).contains("HOJA DE RESORTE");
        assertThat(text).contains("Toyota");
        assertThat(text).contains("Hilux");
        assertThat(text).contains("Tacoma");
        assertThat(text).contains("PERNO");

        assertThat(text).contains("450.00");
        assertThat(text).contains("300.00");
        assertThat(text).contains("115.00");
    }

    // ══ PARIDAD ENTRE LOS DOS DOCUMENTOS ═════════════════════════
    //
    // La regla del segundo PDF: es el MISMO cierre de caja con la mercaderia
    // identificada de otra forma. Todo lo financiero tiene que decir lo mismo,
    // palabra por palabra, porque sale del mismo snapshot y lo dibuja el mismo
    // codigo.

    /**
     * Las cinco secciones financieras del cierre estan tambien en el resumen,
     * con el MISMO texto.
     *
     * Se compara el bloque entero extraido del PDF, no cifra a cifra: una
     * comprobacion campo a campo se queda corta en cuanto alguien anade una
     * linea a una seccion y se olvida del otro documento.
     */
    @Test
    void bothPdfsCarryTheSameFinancialSections() throws IOException {
        CashClosureResponseDTO closure = confirmedClosure();

        String daily = textOf(service.renderDaily(closure, header(), false, "ANA LOPEZ"));
        String productSummary = textOf(service.renderProductSummary(
                closure, summary(), header(), "ANA LOPEZ"));

        for (String section : List.of(
                "FACTURACION EMITIDA",
                "FACTURAS CAMBIARIAS",
                "COBROS POR FORMA DE PAGO",
                "DEVOLUCIONES DEL PERIODO",
                "NETO DEL PERIODO")) {
            assertThat(financialBlock(daily, section))
                    .as("seccion %s", section)
                    .isNotEmpty()
                    .isEqualTo(financialBlock(productSummary, section));
        }
    }

    /**
     * EL CASO QUE ESTA PRUEBA EXISTE PARA IMPEDIR.
     *
     * Un cierre cobrado INTEGRO en efectivo. El cierre de caja lo dice, y el
     * resumen de productos tiene que decir exactamente lo mismo: Q510 en
     * efectivo, cero en las otras dos vias.
     *
     * Antes no era asi. El resumen atribuia la forma de cobro por su cuenta,
     * fila a fila, y para un cierre confirmado antes de esa columna imprimia
     * "SIN FORMA DE COBRO REGISTRADA" sobre el mismo importe que el otro papel
     * declaraba cobrado. Dos documentos del mismo dia contradiciendose.
     */
    @Test
    void aCashOnlyClosureSaysEfectivoInBothPdfs() throws IOException {
        CashClosureResponseDTO closure = cashOnlyClosure();
        CashClosureProductSummaryDTO products = tundraSummary();

        String daily = textOf(save("q510-cierre.pdf",
                service.renderDaily(closure, header(), false, "ANA LOPEZ")));
        String productSummary = textOf(save("q510-resumen-productos.pdf",
                service.renderProductSummary(
                        closure, products, header(), "ANA LOPEZ")));

        for (String text : List.of(daily, productSummary)) {
            assertThat(text).contains("COBROS POR FORMA DE PAGO");
            assertThat(text).contains("Efectivo");
            assertThat(text).contains("Q510.00");
            assertThat(text).contains("TOTAL COBRADO");
        }

        assertThat(financialBlock(daily, "COBROS POR FORMA DE PAGO"))
                .isEqualTo(financialBlock(productSummary, "COBROS POR FORMA DE PAGO"));

        // Y el resumen NO puede inventarse que no se sabe como se cobro.
        assertThat(productSummary).doesNotContain("SIN FORMA DE COBRO REGISTRADA");
        assertThat(productSummary).doesNotContain("PENDIENTE DE COBRO AL CERRAR");

        // La mercaderia, agrupada por familia, marca y modelo.
        assertThat(productSummary).contains("HOJA DE RESORTE");
        assertThat(productSummary).contains("Toyota");
        assertThat(productSummary).contains("Tundra");
        assertThat(productSummary).contains("TOTAL GENERADO EN PRODUCTOS");
    }

    /** Lo mismo cobrado con TARJETA: las dos hojas dicen tarjeta. */
    @Test
    void aCardOnlyClosureSaysTarjetaInBothPdfs() throws IOException {
        CashClosureResponseDTO closure = collectedWith(PaymentMethod.CARD);

        assertThat(financialBlock(
                        textOf(service.renderDaily(closure, header(), false, "ANA LOPEZ")),
                        "COBROS POR FORMA DE PAGO"))
                .isEqualTo(financialBlock(
                        textOf(service.renderProductSummary(
                                closure, tundraSummary(), header(), "ANA LOPEZ")),
                        "COBROS POR FORMA DE PAGO"))
                .anySatisfy(line -> assertThat(line)
                        .contains("Tarjeta").contains("Q510.00"));
    }

    /** Y por TRANSFERENCIA / DEPOSITO. */
    @Test
    void aTransferOnlyClosureSaysDepositoInBothPdfs() throws IOException {
        CashClosureResponseDTO closure = collectedWith(PaymentMethod.TRANSFER);

        assertThat(financialBlock(
                        textOf(service.renderDaily(closure, header(), false, "ANA LOPEZ")),
                        "COBROS POR FORMA DE PAGO"))
                .isEqualTo(financialBlock(
                        textOf(service.renderProductSummary(
                                closure, tundraSummary(), header(), "ANA LOPEZ")),
                        "COBROS POR FORMA DE PAGO"))
                .anySatisfy(line -> assertThat(line)
                        .contains("Transferencia / Deposito").contains("Q510.00"));
    }

    /** Un dia con las tres vias a la vez: las tres filas coinciden. */
    @Test
    void aMixedClosureRepeatsEveryPaymentRowInBothPdfs() throws IOException {
        CashClosureResponseDTO closure = mixedClosure();

        List<String> daily = financialBlock(
                textOf(service.renderDaily(closure, header(), false, "ANA LOPEZ")),
                "COBROS POR FORMA DE PAGO");
        List<String> productSummary = financialBlock(
                textOf(service.renderProductSummary(
                        closure, summary(), header(), "ANA LOPEZ")),
                "COBROS POR FORMA DE PAGO");

        assertThat(daily).isEqualTo(productSummary);
        assertThat(String.join(" ", productSummary))
                .contains("Efectivo").contains("Q200.00")
                .contains("Tarjeta").contains("Q300.00")
                .contains("Transferencia / Deposito").contains("Q75.00")
                .contains("TOTAL COBRADO").contains("Q575.00");
    }

    /** Las devoluciones y el neto tambien se repiten intactos. */
    @Test
    void refundsAndNetAreIdenticalInBothPdfs() throws IOException {
        CashClosureResponseDTO closure = closureWithRefunds();

        String daily = textOf(service.renderDaily(closure, header(), false, "ANA LOPEZ"));
        String productSummary = textOf(service.renderProductSummary(
                closure, summary(), header(), "ANA LOPEZ"));

        assertThat(financialBlock(daily, "DEVOLUCIONES DEL PERIODO"))
                .isEqualTo(financialBlock(productSummary, "DEVOLUCIONES DEL PERIODO"));
        assertThat(financialBlock(daily, "NETO DEL PERIODO"))
                .isEqualTo(financialBlock(productSummary, "NETO DEL PERIODO"));
        assertThat(productSummary).contains("Q1,500.00");
        assertThat(productSummary).contains("Q4,500.00");
    }

    /**
     * La cartera cambiaria —emitida, pendiente y cobrada— se repite igual, con
     * sus documentos y sus estados.
     */
    @Test
    void theExchangeSectionIsIdenticalInBothPdfs() throws IOException {
        CashClosureResponseDTO closure = confirmedClosure();

        String daily = textOf(service.renderDaily(closure, header(), false, "ANA LOPEZ"));
        String productSummary = textOf(service.renderProductSummary(
                closure, summary(), header(), "ANA LOPEZ"));

        assertThat(financialBlock(daily, "FACTURAS CAMBIARIAS"))
                .isEqualTo(financialBlock(productSummary, "FACTURAS CAMBIARIAS"));
        assertThat(productSummary).contains("Pendiente");
        assertThat(productSummary).contains("TRANSPORTES DEL NORTE");
    }

    /**
     * La UNICA diferencia deliberada: como se identifica lo vendido.
     *
     * El cierre lista articulo y tipo; el resumen, familia, marca y modelo. Ni
     * uno de los dos lleva las columnas del otro.
     */
    @Test
    void onlyTheSoldProductTableDiffers() throws IOException {
        CashClosureResponseDTO closure = confirmedClosure();

        String daily = textOf(service.renderDaily(closure, header(), false, "ANA LOPEZ"));
        String productSummary = textOf(service.renderProductSummary(
                closure, summary(), header(), "ANA LOPEZ"));

        assertThat(daily).contains("ARTICULOS VENDIDOS");
        assertThat(daily).contains("Articulo");
        assertThat(daily).contains("RESORTE DELANTERO");
        assertThat(daily).doesNotContain("Monto generado");

        assertThat(productSummary).contains("RESUMEN DE PRODUCTOS");
        assertThat(productSummary).contains("Familia");
        assertThat(productSummary).contains("Marca");
        assertThat(productSummary).contains("Modelo");
        assertThat(productSummary).contains("Monto generado");
        assertThat(productSummary).doesNotContain("ARTICULOS VENDIDOS");
    }

    /** La cabecera, el distintivo y los metadatos son los del mismo cierre. */
    @Test
    void bothPdfsIdentifyTheSameClosureTheSameWay() throws IOException {
        CashClosureResponseDTO closure = confirmedClosure();

        String daily = textOf(service.renderDaily(closure, header(), false, "ANA LOPEZ"));
        String productSummary = textOf(service.renderProductSummary(
                closure, summary(), header(), "ANA LOPEZ"));

        for (String text : List.of(daily, productSummary)) {
            assertThat(text).contains("LA CASA DEL RESORTE");
            assertThat(text).contains("CIERRE CONFIRMADO");
            assertThat(text).contains("Fecha operativa");
            assertThat(text).contains("20/08/2026");
            assertThat(text).contains("Responsable del cierre");
            assertThat(text).contains("ANA LOPEZ");
            assertThat(text).contains("Cerrado el");
            assertThat(text).contains("Identificador");
            assertThat(text).contains(CLOSURE_ID.toString());
            assertThat(text).contains("Cierre " + CLOSURE_ID);
        }
    }

    // ── Apoyo de la paridad ──────────────────────────────────────

    /**
     * Las lineas de una seccion del PDF, desde su titulo hasta el siguiente.
     *
     * Compara el BLOQUE y no una cifra suelta: es lo unico que detecta que una
     * seccion haya perdido una linea en uno de los dos documentos. Se descartan
     * las lineas de pie —llevan la marca de generacion y el numero de pagina—
     * porque son del papel, no del cierre.
     */
    private List<String> financialBlock(String text, String title) {
        List<String> lines = new java.util.ArrayList<>();
        boolean inside = false;
        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("Generado el ")) {
                continue;
            }
            if (line.equals(title)) {
                inside = true;
                continue;
            }
            if (inside && SECTION_TITLES.contains(line)) {
                break;
            }
            if (inside) {
                lines.add(line);
            }
        }
        return lines;
    }

    /** Los titulos que cierran un bloque al empezar el siguiente. */
    private static final List<String> SECTION_TITLES = List.of(
            "ARTICULOS VENDIDOS",
            "RESUMEN DE PRODUCTOS",
            "FACTURACION EMITIDA",
            "FACTURAS CAMBIARIAS",
            "COBROS POR FORMA DE PAGO",
            "DEVOLUCIONES DEL PERIODO",
            "NETO DEL PERIODO");

    /**
     * El cierre real que destapo la contradiccion: dos unidades, Q510.00,
     * cobradas integras en efectivo.
     */
    private CashClosureResponseDTO cashOnlyClosure() {
        return collectedWith(PaymentMethod.CASH);
    }

    /** El mismo cierre de Q510, cobrado por la via que se le indique. */
    private CashClosureResponseDTO collectedWith(PaymentMethod method) {
        BigDecimal total = new BigDecimal("510.00");
        BigDecimal zero = new BigDecimal("0.00");

        List<ClosurePaymentMethodDTO> methods = new java.util.ArrayList<>();
        for (PaymentMethod each : PaymentMethod.values()) {
            boolean used = each == method;
            methods.add(new ClosurePaymentMethodDTO(
                    each, used ? 1L : 0L, used ? total : zero));
        }

        return new CashClosureResponseDTO(
                CLOSURE_ID,
                BUSINESS_DATE,
                LocalDateTime.of(2026, 8, 20, 19, 0),
                "ANA LOPEZ",
                true,
                List.of(new ClosureSoldItemDTO(UUID.randomUUID(), "Hoja con Agujeros",
                        FamilyCategory.SPECIAL, 2L, total)),
                total,
                new CashClosureInvoicingDTO(1L, total, 0L, zero, total),
                new CashClosureExchangeDTO(
                        0L, zero, zero, 0L, zero, zero, List.of(), List.of()),
                new CashClosureCollectionsDTO(List.copyOf(methods), total),
                new CashClosureRefundsDTO(zeroRefunds(), 0L, zero, List.of()),
                total);
    }

    /** Un dia repartido entre las tres vias: Q200 + Q300 + Q75. */
    private CashClosureResponseDTO mixedClosure() {
        BigDecimal zero = new BigDecimal("0.00");
        BigDecimal total = new BigDecimal("575.00");

        return new CashClosureResponseDTO(
                CLOSURE_ID,
                BUSINESS_DATE,
                LocalDateTime.of(2026, 8, 20, 19, 0),
                "ANA LOPEZ",
                true,
                List.of(new ClosureSoldItemDTO(UUID.randomUUID(), "HOJA 80",
                        FamilyCategory.SPECIAL, 9L, total)),
                total,
                new CashClosureInvoicingDTO(3L, total, 0L, zero, total),
                new CashClosureExchangeDTO(
                        0L, zero, zero, 0L, zero, zero, List.of(), List.of()),
                new CashClosureCollectionsDTO(
                        List.of(
                                new ClosurePaymentMethodDTO(PaymentMethod.CASH, 1L,
                                        new BigDecimal("200.00")),
                                new ClosurePaymentMethodDTO(PaymentMethod.CARD, 1L,
                                        new BigDecimal("300.00")),
                                new ClosurePaymentMethodDTO(PaymentMethod.TRANSFER, 1L,
                                        new BigDecimal("75.00"))),
                        total),
                new CashClosureRefundsDTO(zeroRefunds(), 0L, zero, List.of()),
                total);
    }

    private static List<ClosureRefundMethodDTO> zeroRefunds() {
        List<ClosureRefundMethodDTO> methods = new java.util.ArrayList<>();
        for (PaymentMethod each : PaymentMethod.values()) {
            methods.add(new ClosureRefundMethodDTO(each, 0L, new BigDecimal("0.00")));
        }
        return List.copyOf(methods);
    }

    /** La mercaderia del cierre de Q510, agrupada como la ve el taller. */
    private CashClosureProductSummaryDTO tundraSummary() {
        return new CashClosureProductSummaryDTO(
                List.of(new CashClosureProductGroupDTO(
                        UUID.randomUUID(), "HOJA DE RESORTE",
                        UUID.randomUUID(), "Toyota",
                        UUID.randomUUID(), "Tundra",
                        2L, new BigDecimal("510.00"))),
                2L,
                new BigDecimal("510.00"),
                new BigDecimal("0.00"),
                new BigDecimal("0.00"));
    }

    /** El gran total, en unidades y en quetzales. */
    @Test
    void theProductSummaryPdfClosesWithTheGrandTotal() throws IOException {
        String text = textOf(service.renderProductSummary(
                confirmedClosure(), summary(), header(), "ANA LOPEZ"));

        assertThat(text).contains("TOTAL UNIDADES");
        assertThat(text).contains("18");
        assertThat(text).contains("TOTAL GENERADO EN PRODUCTOS");
        assertThat(text).contains("Q865.00");
    }

    /**
     * Por que este total no es el del cierre.
     *
     * La diferencia son los servicios. Sin esta linea, quien tenga las dos hojas
     * delante ve Q865 aqui y Q7,000 alla y concluye que una esta mal.
     */
    @Test
    void theProductSummaryPdfExplainsTheServicesItLeavesOut() throws IOException {
        String text = textOf(service.renderProductSummary(
                confirmedClosure(), summary(), header(), "ANA LOPEZ"));

        assertThat(text).contains("No incluye servicios");
        assertThat(text).contains("2,000.00");
    }

    /** Sin servicios no hay nada que explicar, y la nota no aparece. */
    @Test
    void theProductSummaryPdfOmitsTheNoteWhenThereWereNoServices() throws IOException {
        CashClosureProductSummaryDTO withoutJobs = new CashClosureProductSummaryDTO(
                summary().groups(), 18L, new BigDecimal("865.00"),
                new BigDecimal("0.00"), new BigDecimal("0.00"));

        String text = textOf(service.renderProductSummary(
                confirmedClosure(), withoutJobs, header(), "ANA LOPEZ"));

        assertThat(text).doesNotContain("No incluye servicios");
    }

    /**
     * Una dimension que no aplica se imprime como guion, nunca como una marca
     * inventada ni como un hueco que parezca un fallo del documento.
     */
    @Test
    void theProductSummaryPdfDrawsMissingDimensionsAsADash() throws IOException {
        String text = textOf(service.renderProductSummary(
                confirmedClosure(), summary(), header(), "ANA LOPEZ"));

        assertThat(text).contains("\u2014");
    }

    // ── Filas heredadas sin clasificar (cierres anteriores a V37) ──

    /**
     * El resumen de un cierre ANTIGUO cuyo producto ya no esta en el catalogo.
     *
     * V37 no tuvo de donde sacarle la familia, asi que llega en nulo. Lo que NO
     * puede pasar es que se imprima como un guion: el guion significa "no
     * aplica" —lo que se pinta en marca y modelo de una familia sin modelo— y la
     * familia aplica siempre. Un guion ahi haria leer "producto sin familia"
     * donde lo cierto es "no sabemos cual era".
     */
    private CashClosureProductSummaryDTO summaryWithLegacyRow() {
        return new CashClosureProductSummaryDTO(
                List.of(
                        new CashClosureProductGroupDTO(
                                UUID.randomUUID(), "PERNO",
                                null, null, null, null,
                                10L, new BigDecimal("115.00")),
                        new CashClosureProductGroupDTO(
                                null, null, null, null, null, null,
                                4L, new BigDecimal("77.00"))),
                14L,
                new BigDecimal("192.00"),
                new BigDecimal("0.00"),
                new BigDecimal("77.00"));
    }

    @Test
    void theProductSummaryPdfNamesUnclassifiedLegacyRowsInsteadOfDashingThem()
            throws IOException {
        String text = textOf(save("resumen-productos-heredado.pdf",
                service.renderProductSummary(
                        confirmedClosure(), summaryWithLegacyRow(), header(),
                        "ANA LOPEZ")));

        assertThat(text).contains("SIN CLASIFICAR");
        // La familia real de la otra fila sigue imprimiendose tal cual.
        assertThat(text).contains("PERNO");
    }

    /**
     * Y se explica de donde sale, porque un renglon "SIN CLASIFICAR" a secas
     * parece un fallo del reporte.
     */
    @Test
    void theProductSummaryPdfExplainsWhyARowIsUnclassified() throws IOException {
        String text = textOf(service.renderProductSummary(
                confirmedClosure(), summaryWithLegacyRow(), header(), "ANA LOPEZ"));

        assertThat(text).contains("sin clasificar");
        assertThat(text).contains("77.00");
    }

    /**
     * EL punto que no se puede perder: el importe sin clasificar SIGUE DENTRO
     * del total. Lo que falta es la etiqueta, no el dinero.
     */
    @Test
    void theUnclassifiedAmountStaysInsideTheGrandTotal() throws IOException {
        CashClosureProductSummaryDTO summary = summaryWithLegacyRow();

        assertThat(summary.totalAmount())
                .isEqualByComparingTo(
                        summary.groups().stream()
                                .map(CashClosureProductGroupDTO::amount)
                                .reduce(BigDecimal.ZERO, BigDecimal::add));
        assertThat(summary.unclassifiedAmount())
                .isLessThan(summary.totalAmount());

        String text = textOf(service.renderProductSummary(
                confirmedClosure(), summary, header(), "ANA LOPEZ"));
        assertThat(text).contains("Q192.00");
    }

    /** Un cierre posterior a V37 no ensena la nota: no tiene nada sin clasificar. */
    @Test
    void theProductSummaryPdfOmitsTheUnclassifiedNoteForModernClosures()
            throws IOException {
        String text = textOf(service.renderProductSummary(
                confirmedClosure(), summary(), header(), "ANA LOPEZ"));

        assertThat(text).doesNotContain("SIN CLASIFICAR");
        assertThat(text).doesNotContain("sin clasificar");
    }

    /** Se identifica con el mismo cierre y el mismo responsable que el otro PDF. */
    @Test
    void theProductSummaryPdfIdentifiesItsClosure() throws IOException {
        String text = textOf(service.renderProductSummary(
                confirmedClosure(), summary(), header(), "ANA LOPEZ"));

        assertThat(text).contains("ANA LOPEZ");
        assertThat(text).contains(CLOSURE_ID.toString());
        assertThat(text).contains("20/08/2026");
        assertThat(text).doesNotContain("PREVISUALIZACION");
    }

    /** Un cierre sin mercaderia lo dice, y sigue cerrando con su total en cero. */
    @Test
    void theProductSummaryPdfHandlesAnEmptyReport() throws IOException {
        CashClosureProductSummaryDTO empty = new CashClosureProductSummaryDTO(
                List.of(), 0L, new BigDecimal("0.00"),
                new BigDecimal("0.00"), new BigDecimal("0.00"));

        String text = textOf(save("resumen-productos-vacio.pdf",
                service.renderProductSummary(
                        confirmedClosure(), empty, header(), "ANA LOPEZ")));

        assertThat(text).contains("No se facturaron productos en el periodo.");
        assertThat(text).contains("TOTAL GENERADO EN PRODUCTOS");
    }

    /**
     * Nombres largos y muchas filas: el texto se recorta al ancho de su columna
     * y la tabla salta de pagina repitiendo su cabecera.
     *
     * Sin la cabecera repetida, la segunda hoja de un mes cargado empieza en
     * numeros sueltos que nadie puede atribuir a una columna.
     */
    @Test
    void theProductSummaryPdfSurvivesLongNamesAndManyGroups() throws IOException {
        List<CashClosureProductGroupDTO> many = new java.util.ArrayList<>();
        for (int index = 0; index < 120; index++) {
            many.add(new CashClosureProductGroupDTO(
                    UUID.randomUUID(),
                    "FAMILIA CON UN NOMBRE DESCRIPTIVO Y LARGUISIMO " + index,
                    UUID.randomUUID(),
                    "MARCA DE VEHICULO CON NOMBRE MUY LARGO " + index,
                    UUID.randomUUID(),
                    "MODELO CON NOMBRE INTERMINABLE DE VERDAD " + index,
                    index + 1L,
                    new BigDecimal("1234.56")));
        }
        CashClosureProductSummaryDTO crowded = new CashClosureProductSummaryDTO(
                List.copyOf(many), 7260L, new BigDecimal("148147.20"),
                new BigDecimal("0.00"), new BigDecimal("0.00"));

        byte[] pdf = save("resumen-productos-largo.pdf",
                service.renderProductSummary(
                        confirmedClosure(), crowded, header(), "ANA LOPEZ"));

        assertThat(pagesOf(pdf)).isGreaterThan(1);

        String text = textOf(pdf);
        // La cabecera aparece una vez por hoja de tabla, no solo en la primera.
        assertThat(text.split("Monto generado", -1).length - 1)
                .isGreaterThanOrEqualTo(pagesOf(pdf) - 1);
        assertThat(text).contains("TOTAL GENERADO EN PRODUCTOS");
    }

    /**
     * El PDF de cierre de siempre NO cambia por existir el segundo: sigue
     * listando articulo a articulo, con sus servicios dentro.
     */
    @Test
    void theDailyPdfKeepsItsOwnItemBreakdown() throws IOException {
        String text = textOf(service.renderDaily(
                confirmedClosure(), header(), false, "ANA LOPEZ"));

        assertThat(text).contains("ARTICULOS VENDIDOS");
        assertThat(text).contains("RESORTE DELANTERO");
        assertThat(text).contains("INSTALACION");
        assertThat(text).doesNotContain("RESUMEN DE PRODUCTOS");
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
