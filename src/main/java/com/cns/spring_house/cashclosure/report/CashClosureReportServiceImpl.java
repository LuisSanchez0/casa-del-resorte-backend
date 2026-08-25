package com.cns.spring_house.cashclosure.report;

import java.awt.Color;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.stereotype.Service;

import com.cns.spring_house.cashclosure.dto.CashClosureCollectionsDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureDaySummaryDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureInvoicingDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureReceivablesDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureResponseDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureSummaryResponseDTO;
import com.cns.spring_house.cashclosure.dto.CashReconciliationDTO;
import com.cns.spring_house.cashclosure.entity.CashBalanceStatus;
import com.cns.spring_house.shared.exception.FileProcessingException;

import lombok.RequiredArgsConstructor;

/**
 * Plantillas de los reportes de caja.
 *
 * La identidad visual no se improvisa aqui: los colores salen de
 * {@link ReportPalette}, que transcribe las variables del frontend. El documento
 * es print-friendly a proposito —pagina blanca, tinta oscura, amarillo de marca
 * en encabezados, filetes, distintivos y totales— aunque la aplicacion tenga
 * modo oscuro: un PDF se imprime y se archiva, y no puede depender de como
 * tuviera el tema quien lo genero.
 */
@Service
@RequiredArgsConstructor
public class CashClosureReportServiceImpl implements CashClosureReportService {

    private static final float LOGO_WIDTH = 96f;
    private static final float SECTION_TITLE_HEIGHT = 18f;
    private static final float ROW_HEIGHT = 15.5f;
    private static final float TOTAL_ROW_HEIGHT = 20f;

    /** Columna donde terminan las cantidades; los importes terminan al margen. */
    private static final float COUNT_COLUMN_OFFSET = 132f;

    private final ReportAssets assets;
    private final Clock clock;

    @Override
    public byte[] renderDaily(
            CashClosureResponseDTO closure,
            ReportHeaderDTO header,
            boolean draft,
            String responsible) {

        LocalDateTime generatedAt = LocalDateTime.now(clock);
        try (PdfCanvas canvas = new PdfCanvas()) {
            documentHeader(canvas, header, "CIERRE DE CAJA",
                    ReportFormats.date(closure.businessDate()));

            statusBadge(canvas, draft);
            metadata(canvas, closure, draft, responsible, generatedAt);

            invoicingSection(canvas, closure.invoicing());
            collectionsSection(canvas, closure.collections());
            receivablesSection(canvas, closure.receivables());
            reconciliationSection(canvas, closure.cashReconciliation(),
                    closure.collections().cashAmount());
            notesSection(canvas, closure.notes());

            String footerRight = closure.id() == null
                    ? "Documento sin valor de cierre"
                    : "Cierre " + closure.id();
            return canvas.finish(
                    "Generado el " + ReportFormats.dateTime(generatedAt), footerRight);
        } catch (IOException exception) {
            throw new FileProcessingException(
                    "No se pudo generar el PDF del cierre de caja");
        }
    }

    @Override
    public byte[] renderSummary(
            CashClosureSummaryResponseDTO summary, ReportHeaderDTO header) {

        LocalDateTime generatedAt = LocalDateTime.now(clock);
        try (PdfCanvas canvas = new PdfCanvas()) {
            documentHeader(canvas, header, "RESUMEN DE CAJA",
                    ReportFormats.date(summary.from())
                            + "  a  " + ReportFormats.date(summary.to()));

            canvas.moveDown(6f);
            metadataRow(canvas, "Cierres realizados",
                    ReportFormats.count(summary.closureCount()));
            metadataRow(canvas, "Generado",
                    ReportFormats.dateTime(generatedAt));
            canvas.moveDown(8f);

            summaryInvoicing(canvas, summary.invoicing());
            summaryCollections(canvas, summary.collections());
            summaryReceivables(canvas, summary.receivables());
            summaryBalance(canvas, summary);
            dailyTable(canvas, summary);

            return canvas.finish(
                    "Generado el " + ReportFormats.dateTime(generatedAt),
                    "Resumen de caja");
        } catch (IOException exception) {
            throw new FileProcessingException(
                    "No se pudo generar el PDF del resumen de caja");
        }
    }

    // ── Encabezado ───────────────────────────────────────────────

    /**
     * Logo a la izquierda con su proporcion intacta —se escala por el ancho y
     * el alto es consecuencia— y a la derecha el titulo del documento.
     */
    private void documentHeader(
            PdfCanvas canvas, ReportHeaderDTO header, String title, String subtitle)
            throws IOException {

        byte[] logo = assets.logo();
        float logoHeight = canvas.scaledHeight(logo, LOGO_WIDTH);
        float top = canvas.cursor();

        canvas.image(logo, "logo", canvas.left(), top - logoHeight, LOGO_WIDTH, logoHeight);

        float textLeft = canvas.left() + LOGO_WIDTH + 14f;
        float textWidth = canvas.right() - textLeft;
        float line = top - 12f;

        canvas.text(
                canvas.fit(header.workshopName(), PdfCanvas.BOLD, 14f, textWidth),
                textLeft, line, PdfCanvas.BOLD, 14f, ReportPalette.TEXT);
        line -= 13f;

        if (header.address() != null && !header.address().isBlank()) {
            canvas.text(
                    canvas.fit(header.address(), PdfCanvas.REGULAR, 8.5f, textWidth),
                    textLeft, line, PdfCanvas.REGULAR, 8.5f, ReportPalette.TEXT_MUTED);
            line -= 12f;
        }

        canvas.textRight(title, canvas.right(), top - logoHeight + 14f,
                PdfCanvas.BOLD, 15f, ReportPalette.TEXT);
        canvas.textRight(subtitle, canvas.right(), top - logoHeight + 2f,
                PdfCanvas.REGULAR, 10f, ReportPalette.TEXT_MUTED);

        float headerBottom = Math.min(line, top - logoHeight) - 8f;
        canvas.fillRect(canvas.left(), headerBottom, canvas.contentWidth(), 2.5f,
                ReportPalette.PRIMARY);

        canvas.moveDown(top - headerBottom + 12f);
    }

    /**
     * Distintivo de estado. En un borrador es lo primero que se ve y dice sin
     * ambiguedad que la caja NO esta cerrada: sin esta marca, una impresion de
     * previsualizacion es indistinguible de un cierre confirmado.
     */
    private void statusBadge(PdfCanvas canvas, boolean draft) throws IOException {
        String label = draft
                ? "PREVISUALIZACION - CAJA NO CERRADA"
                : "CIERRE CONFIRMADO";
        Color background = draft ? ReportPalette.WARNING : ReportPalette.PRIMARY;

        float padding = 9f;
        float height = 19f;
        float width = canvas.widthOf(label, PdfCanvas.BOLD, 9.5f) + 2 * padding;
        float top = canvas.cursor();

        canvas.fillRect(canvas.left(), top - height, width, height, background);
        canvas.text(label, canvas.left() + padding, top - height + 6f,
                PdfCanvas.BOLD, 9.5f, ReportPalette.PRIMARY_TEXT);

        canvas.moveDown(height + 12f);
    }

    private void metadata(
            PdfCanvas canvas,
            CashClosureResponseDTO closure,
            boolean draft,
            String responsible,
            LocalDateTime generatedAt) throws IOException {

        metadataRow(canvas, "Fecha operativa",
                ReportFormats.date(closure.businessDate()));
        if (draft) {
            metadataRow(canvas, "Solicitado por",
                    responsible == null ? "-" : responsible);
        } else {
            metadataRow(canvas, "Responsable del cierre",
                    responsible == null ? "-" : responsible);
            metadataRow(canvas, "Cerrado el",
                    ReportFormats.dateTime(closure.closedAt()));
            metadataRow(canvas, "Identificador", String.valueOf(closure.id()));
        }
        metadataRow(canvas, "Generado", ReportFormats.dateTime(generatedAt));
        canvas.moveDown(10f);
    }

    private void metadataRow(PdfCanvas canvas, String label, String value)
            throws IOException {
        canvas.ensureSpace(ROW_HEIGHT);
        float baseline = canvas.cursor() - 9f;
        canvas.text(label, canvas.left(), baseline,
                PdfCanvas.REGULAR, 8.5f, ReportPalette.TEXT_MUTED);
        canvas.text(value, canvas.left() + 130f, baseline,
                PdfCanvas.BOLD, 8.5f, ReportPalette.TEXT);
        canvas.moveDown(13f);
    }

    // ── Secciones del reporte diario ─────────────────────────────

    private void invoicingSection(PdfCanvas canvas, CashClosureInvoicingDTO invoicing)
            throws IOException {
        sectionTitle(canvas, "FACTURACION");
        amountRow(canvas, "Facturas",
                invoicing.invoiceCount(), invoicing.invoiceTotal(), false);
        amountRow(canvas, "Facturas cambiarias",
                invoicing.exchangeInvoiceCount(), invoicing.exchangeInvoiceTotal(), true);
        totalRow(canvas, "TOTAL FACTURADO", invoicing.totalInvoiced());
    }

    private void collectionsSection(
            PdfCanvas canvas, CashClosureCollectionsDTO collections) throws IOException {
        sectionTitle(canvas, "COBROS");
        amountRow(canvas, "Efectivo",
                collections.cashCount(), collections.cashAmount(), false);
        amountRow(canvas, "Tarjeta",
                collections.cardCount(), collections.cardAmount(), true);
        amountRow(canvas, "Transferencia / Deposito",
                collections.transferCount(), collections.transferAmount(), false);
        totalRow(canvas, "TOTAL COBRADO", collections.totalCollected());
    }

    private void receivablesSection(
            PdfCanvas canvas, CashClosureReceivablesDTO receivables) throws IOException {
        sectionTitle(canvas, "CARTERA CAMBIARIA");
        amountRow(canvas, "Cambiarias cobradas hoy",
                receivables.collectedCount(), receivables.collectedAmount(), false);
        labelledAmountRow(canvas, "   de cartera de dias anteriores",
                receivables.previousCollectedAmount(), true, ReportPalette.TEXT_MUTED);
        labelledAmountRow(canvas, "Cartera pendiente total",
                receivables.pendingAmount(), false, ReportPalette.TEXT);
        // Esta seccion no termina en un total, que es lo que en las demas deja
        // aire antes del siguiente titulo. Sin este respiro, la ultima fila
        // quedaba pegada a la cabecera de ARQUEO y parecian el mismo bloque.
        canvas.moveDown(12f);
    }

    /**
     * El arqueo repite el efectivo cobrado aunque ya aparezca en COBROS: es el
     * sumando que explica el efectivo esperado, y sin el la resta final parece
     * salir de la nada.
     */
    private void reconciliationSection(
            PdfCanvas canvas,
            CashReconciliationDTO reconciliation,
            BigDecimal cashCollected) throws IOException {

        sectionTitle(canvas, "ARQUEO DE EFECTIVO");
        labelledAmountRow(canvas, "Fondo inicial",
                reconciliation.openingCash(), false, ReportPalette.TEXT);
        labelledAmountRow(canvas, "Cobrado en efectivo",
                cashCollected, true, ReportPalette.TEXT);
        totalRow(canvas, "EFECTIVO ESPERADO", reconciliation.expectedCash());
        labelledAmountRow(canvas, "Efectivo contado",
                reconciliation.countedCash(), false, ReportPalette.TEXT);

        differenceRow(canvas, reconciliation);
    }

    /**
     * La diferencia y su lectura van juntas y en color: en un cierre impreso, el
     * dato que se busca primero es si la caja cuadro.
     */
    private void differenceRow(PdfCanvas canvas, CashReconciliationDTO reconciliation)
            throws IOException {
        canvas.ensureSpace(TOTAL_ROW_HEIGHT + 4f);
        CashBalanceStatus status = reconciliation.status();
        Color color = switch (status) {
            case SHORT -> ReportPalette.DANGER;
            case OVER, BALANCED -> ReportPalette.SUCCESS;
        };
        String label = switch (status) {
            case SHORT -> "FALTANTE";
            case OVER -> "SOBRANTE";
            case BALANCED -> "CUADRADA";
        };

        float top = canvas.cursor();
        float height = TOTAL_ROW_HEIGHT;
        canvas.line(canvas.left(), top, canvas.right(), top, ReportPalette.BORDER, 0.7f);

        float baseline = top - height + 6f;
        canvas.text("DIFERENCIA", canvas.left(), baseline,
                PdfCanvas.BOLD, 9.5f, ReportPalette.TEXT);
        canvas.textRight(ReportFormats.signedMoney(reconciliation.difference()),
                canvas.right(), baseline, PdfCanvas.BOLD, 11f, color);
        canvas.moveDown(height);

        canvas.ensureSpace(18f);
        float badgeTop = canvas.cursor();
        float badgeWidth = canvas.widthOf(label, PdfCanvas.BOLD, 9f) + 16f;
        canvas.fillRect(canvas.left(), badgeTop - 16f, badgeWidth, 16f, color);
        canvas.text(label, canvas.left() + 8f, badgeTop - 16f + 5f,
                PdfCanvas.BOLD, 9f, Color.WHITE);
        canvas.moveDown(16f + 14f);
    }

    private void notesSection(PdfCanvas canvas, String notes) throws IOException {
        // Sin observaciones no se imprime un apartado vacio: un titulo con nada
        // debajo hace dudar de si falto algo.
        if (notes == null || notes.isBlank()) {
            return;
        }
        sectionTitle(canvas, "OBSERVACIONES");
        for (String line : wrap(canvas, notes, canvas.contentWidth() - 8f)) {
            canvas.ensureSpace(ROW_HEIGHT);
            canvas.text(line, canvas.left() + 4f, canvas.cursor() - 9f,
                    PdfCanvas.REGULAR, 9f, ReportPalette.TEXT);
            canvas.moveDown(12f);
        }
        canvas.moveDown(6f);
    }

    // ── Secciones del resumen por rango ──────────────────────────

    private void summaryInvoicing(PdfCanvas canvas, CashClosureInvoicingDTO invoicing)
            throws IOException {
        sectionTitle(canvas, "FACTURACION DEL PERIODO");
        amountRow(canvas, "Facturas",
                invoicing.invoiceCount(), invoicing.invoiceTotal(), false);
        amountRow(canvas, "Facturas cambiarias",
                invoicing.exchangeInvoiceCount(), invoicing.exchangeInvoiceTotal(), true);
        totalRow(canvas, "TOTAL FACTURADO", invoicing.totalInvoiced());
    }

    private void summaryCollections(
            PdfCanvas canvas, CashClosureCollectionsDTO collections) throws IOException {
        sectionTitle(canvas, "COBROS DEL PERIODO");
        amountRow(canvas, "Efectivo",
                collections.cashCount(), collections.cashAmount(), false);
        amountRow(canvas, "Tarjeta",
                collections.cardCount(), collections.cardAmount(), true);
        amountRow(canvas, "Transferencia / Deposito",
                collections.transferCount(), collections.transferAmount(), false);
        totalRow(canvas, "TOTAL COBRADO", collections.totalCollected());
    }

    private void summaryReceivables(
            PdfCanvas canvas,
            com.cns.spring_house.cashclosure.dto.CashClosureRangeReceivablesDTO receivables)
            throws IOException {
        sectionTitle(canvas, "CARTERA CAMBIARIA");
        amountRow(canvas, "Cambiarias cobradas en el periodo",
                receivables.collectedCount(), receivables.collectedAmount(), false);
        labelledAmountRow(canvas, "Cartera pendiente al ultimo cierre del periodo",
                receivables.endingPendingAmount(), true, ReportPalette.TEXT);
        canvas.moveDown(12f);
    }

    private void summaryBalance(PdfCanvas canvas, CashClosureSummaryResponseDTO summary)
            throws IOException {
        sectionTitle(canvas, "CUADRES DE CAJA");
        countRow(canvas, "Cajas cuadradas", summary.balance().balancedClosures(), false);
        countRow(canvas, "Cajas con faltante", summary.balance().shortClosures(), true);
        countRow(canvas, "Cajas con sobrante", summary.balance().overClosures(), false);

        canvas.ensureSpace(TOTAL_ROW_HEIGHT);
        float top = canvas.cursor();
        canvas.fillRect(canvas.left(), top - TOTAL_ROW_HEIGHT, canvas.contentWidth(),
                TOTAL_ROW_HEIGHT, ReportPalette.SURFACE_MUTED);
        canvas.fillRect(canvas.left(), top - TOTAL_ROW_HEIGHT, 3f,
                TOTAL_ROW_HEIGHT, ReportPalette.PRIMARY);
        float baseline = top - TOTAL_ROW_HEIGHT + 6.5f;
        canvas.text("DIFERENCIA NETA", canvas.left() + 10f, baseline,
                PdfCanvas.BOLD, 9.5f, ReportPalette.TEXT);
        BigDecimal net = summary.balance().netCashDifference();
        canvas.textRight(ReportFormats.signedMoney(net), canvas.right() - 6f, baseline,
                PdfCanvas.BOLD, 10.5f,
                net.signum() < 0 ? ReportPalette.DANGER : ReportPalette.SUCCESS);
        canvas.moveDown(TOTAL_ROW_HEIGHT + 14f);
    }

    /**
     * Tabla dia a dia. Solo hay fila para los dias con cierre: un dia sin cerrar
     * no aparece como una linea de ceros, porque no consta que se cuadrara.
     */
    private void dailyTable(PdfCanvas canvas, CashClosureSummaryResponseDTO summary)
            throws IOException {

        sectionTitle(canvas, "DETALLE POR DIA");

        if (summary.days().isEmpty()) {
            canvas.ensureSpace(ROW_HEIGHT);
            canvas.text("No existen cierres de caja en el periodo consultado.",
                    canvas.left() + 4f, canvas.cursor() - 9f,
                    PdfCanvas.REGULAR, 9f, ReportPalette.TEXT_MUTED);
            canvas.moveDown(14f);
            return;
        }

        float[] widths = { 66f, 80f, 80f, 74f, 70f, 74f, 76f };
        String[] titles = { "Fecha", "Facturado", "Cobrado", "Efectivo",
                "Tarjeta", "Transferencia", "Diferencia" };

        tableHeader(canvas, widths, titles);

        boolean shaded = false;
        for (CashClosureDaySummaryDTO day : summary.days()) {
            if (canvas.ensureSpace(ROW_HEIGHT)) {
                tableHeader(canvas, widths, titles);
            }
            float top = canvas.cursor();
            if (shaded) {
                canvas.fillRect(canvas.left(), top - ROW_HEIGHT, canvas.contentWidth(),
                        ROW_HEIGHT, ReportPalette.SURFACE_MUTED);
            }
            shaded = !shaded;

            float baseline = top - ROW_HEIGHT + 4.5f;
            float x = canvas.left();
            canvas.text(ReportFormats.date(day.businessDate()), x + 4f, baseline,
                    PdfCanvas.REGULAR, 8f, ReportPalette.TEXT);
            x += widths[0];

            x = money(canvas, day.totalInvoiced(), x, widths[1], baseline, ReportPalette.TEXT);
            x = money(canvas, day.totalCollected(), x, widths[2], baseline, ReportPalette.TEXT);
            x = money(canvas, day.cashPayments(), x, widths[3], baseline, ReportPalette.TEXT);
            x = money(canvas, day.cardPayments(), x, widths[4], baseline, ReportPalette.TEXT);
            x = money(canvas, day.transferPayments(), x, widths[5], baseline,
                    ReportPalette.TEXT);

            Color color = day.difference().signum() < 0
                    ? ReportPalette.DANGER
                    : day.difference().signum() > 0
                            ? ReportPalette.SUCCESS
                            : ReportPalette.TEXT_MUTED;
            canvas.textRight(ReportFormats.signedMoney(day.difference()),
                    x + widths[6] - 4f, baseline, PdfCanvas.BOLD, 8f, color);

            canvas.moveDown(ROW_HEIGHT);
        }
        canvas.line(canvas.left(), canvas.cursor(), canvas.right(), canvas.cursor(),
                ReportPalette.BORDER, 0.6f);
        canvas.moveDown(10f);
    }

    private float money(
            PdfCanvas canvas, BigDecimal amount, float x, float width,
            float baseline, Color color) throws IOException {
        canvas.textRight(ReportFormats.money(amount), x + width - 4f, baseline,
                PdfCanvas.REGULAR, 8f, color);
        return x + width;
    }

    private void tableHeader(PdfCanvas canvas, float[] widths, String[] titles)
            throws IOException {
        canvas.ensureSpace(ROW_HEIGHT + 2f);
        float top = canvas.cursor();
        canvas.fillRect(canvas.left(), top - ROW_HEIGHT, canvas.contentWidth(),
                ROW_HEIGHT, ReportPalette.PRIMARY);

        float baseline = top - ROW_HEIGHT + 4.5f;
        float x = canvas.left();
        for (int index = 0; index < titles.length; index++) {
            if (index == 0) {
                canvas.text(titles[index], x + 4f, baseline,
                        PdfCanvas.BOLD, 8f, ReportPalette.PRIMARY_TEXT);
            } else {
                canvas.textRight(titles[index], x + widths[index] - 4f, baseline,
                        PdfCanvas.BOLD, 8f, ReportPalette.PRIMARY_TEXT);
            }
            x += widths[index];
        }
        canvas.moveDown(ROW_HEIGHT);
    }

    // ── Primitivas de seccion ────────────────────────────────────

    private void sectionTitle(PdfCanvas canvas, String title) throws IOException {
        canvas.ensureSpace(SECTION_TITLE_HEIGHT + ROW_HEIGHT);
        float top = canvas.cursor();
        canvas.fillRect(canvas.left(), top - SECTION_TITLE_HEIGHT, canvas.contentWidth(),
                SECTION_TITLE_HEIGHT, ReportPalette.SURFACE_MUTED);
        canvas.fillRect(canvas.left(), top - SECTION_TITLE_HEIGHT, 3f,
                SECTION_TITLE_HEIGHT, ReportPalette.PRIMARY);
        canvas.text(title, canvas.left() + 10f, top - SECTION_TITLE_HEIGHT + 5.5f,
                PdfCanvas.BOLD, 9f, ReportPalette.TEXT);
        canvas.moveDown(SECTION_TITLE_HEIGHT + 4f);
    }

    /** Fila con cantidad de documentos e importe. */
    private void amountRow(
            PdfCanvas canvas, String label, long count, BigDecimal amount, boolean shaded)
            throws IOException {
        canvas.ensureSpace(ROW_HEIGHT);
        float top = canvas.cursor();
        if (shaded) {
            canvas.fillRect(canvas.left(), top - ROW_HEIGHT, canvas.contentWidth(),
                    ROW_HEIGHT, ReportPalette.SURFACE_MUTED);
        }
        float baseline = top - ROW_HEIGHT + 4.5f;
        canvas.text(label, canvas.left() + 4f, baseline,
                PdfCanvas.REGULAR, 9f, ReportPalette.TEXT);
        canvas.textRight(ReportFormats.count(count),
                canvas.right() - COUNT_COLUMN_OFFSET, baseline,
                PdfCanvas.REGULAR, 9f, ReportPalette.TEXT_MUTED);
        canvas.textRight(ReportFormats.money(amount), canvas.right() - 4f, baseline,
                PdfCanvas.REGULAR, 9f, ReportPalette.TEXT);
        canvas.moveDown(ROW_HEIGHT);
    }

    /** Fila de solo importe, sin columna de cantidad. */
    private void labelledAmountRow(
            PdfCanvas canvas, String label, BigDecimal amount, boolean shaded, Color color)
            throws IOException {
        canvas.ensureSpace(ROW_HEIGHT);
        float top = canvas.cursor();
        if (shaded) {
            canvas.fillRect(canvas.left(), top - ROW_HEIGHT, canvas.contentWidth(),
                    ROW_HEIGHT, ReportPalette.SURFACE_MUTED);
        }
        float baseline = top - ROW_HEIGHT + 4.5f;
        canvas.text(label, canvas.left() + 4f, baseline, PdfCanvas.REGULAR, 9f, color);
        canvas.textRight(ReportFormats.money(amount), canvas.right() - 4f, baseline,
                PdfCanvas.REGULAR, 9f, color);
        canvas.moveDown(ROW_HEIGHT);
    }

    private void countRow(PdfCanvas canvas, String label, long count, boolean shaded)
            throws IOException {
        canvas.ensureSpace(ROW_HEIGHT);
        float top = canvas.cursor();
        if (shaded) {
            canvas.fillRect(canvas.left(), top - ROW_HEIGHT, canvas.contentWidth(),
                    ROW_HEIGHT, ReportPalette.SURFACE_MUTED);
        }
        float baseline = top - ROW_HEIGHT + 4.5f;
        canvas.text(label, canvas.left() + 4f, baseline,
                PdfCanvas.REGULAR, 9f, ReportPalette.TEXT);
        canvas.textRight(ReportFormats.count(count), canvas.right() - 4f, baseline,
                PdfCanvas.BOLD, 9f, ReportPalette.TEXT);
        canvas.moveDown(ROW_HEIGHT);
    }

    private void totalRow(PdfCanvas canvas, String label, BigDecimal amount)
            throws IOException {
        canvas.ensureSpace(TOTAL_ROW_HEIGHT + 6f);
        float top = canvas.cursor();
        canvas.line(canvas.left(), top, canvas.right(), top, ReportPalette.PRIMARY, 1.2f);
        float baseline = top - TOTAL_ROW_HEIGHT + 6f;
        canvas.text(label, canvas.left() + 4f, baseline,
                PdfCanvas.BOLD, 9.5f, ReportPalette.TEXT);
        canvas.textRight(ReportFormats.money(amount), canvas.right() - 4f, baseline,
                PdfCanvas.BOLD, 11f, ReportPalette.TEXT);
        canvas.moveDown(TOTAL_ROW_HEIGHT + 12f);
    }

    /** Corta el texto por palabras al ancho disponible. */
    private java.util.List<String> wrap(PdfCanvas canvas, String text, float maxWidth)
            throws IOException {
        java.util.List<String> lines = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : PdfCanvas.sanitize(text).trim().split("\\s+")) {
            String candidate = current.isEmpty() ? word : current + " " + word;
            if (canvas.widthOf(candidate, PdfCanvas.REGULAR, 9f) > maxWidth
                    && !current.isEmpty()) {
                lines.add(current.toString());
                current = new StringBuilder(word);
            } else {
                current = new StringBuilder(candidate);
            }
        }
        if (!current.isEmpty()) {
            lines.add(current.toString());
        }
        return lines;
    }

}
