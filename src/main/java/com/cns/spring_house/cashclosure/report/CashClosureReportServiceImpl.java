package com.cns.spring_house.cashclosure.report;

import java.awt.Color;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;

import com.cns.spring_house.cashclosure.dto.CashClosureCollectionsDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureExchangeDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureInvoicingDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureProductGroupDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureProductSummaryDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureRefundsDTO;
import com.cns.spring_house.cashclosure.dto.ClosureRefundMethodDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureResponseDTO;
import com.cns.spring_house.cashclosure.dto.ClosureExchangeInvoiceDTO;
import com.cns.spring_house.cashclosure.dto.ClosurePaymentMethodDTO;
import com.cns.spring_house.cashclosure.dto.ClosureSoldItemDTO;
import com.cns.spring_house.sale.dto.SaleNames;
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

    /**
     * La familia de una fila que no se pudo clasificar.
     *
     * No es un guion: el guion significa "no aplica" —lo que se imprime en
     * marca y modelo de una familia que no trabaja con modelo— y la familia
     * aplica siempre. Esto otro significa "no se sabe", que es distinto y solo
     * le pasa a cierres anteriores a V37 con el producto ya borrado.
     */
    private static final String UNCLASSIFIED = "SIN CLASIFICAR";

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

            // El orden es el de las preguntas que el cierre responde: que se
            // vendio, cuanto se emitio, que quedo a credito y como entro el
            // dinero. El total cobrado cierra el documento porque es la cifra
            // que la persona busca.
            soldItemsSection(canvas, closure);
            financialSections(canvas, closure);

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

    /**
     * EL MISMO cierre de caja, con la mercaderia identificada de otra forma.
     *
     * No es un segundo reporte financiero: es una VARIANTE del primero. Lo unico
     * que cambia es la tabla de lo vendido —familia, marca y modelo en vez de
     * articulo y tipo— y a partir de ahi el documento vuelve a ser el cierre de
     * siempre, con sus mismas secciones y sus mismas cifras.
     *
     * Esa igualdad no se consigue repitiendo cuentas: {@code closure} es el
     * MISMO {@link CashClosureResponseDTO} que recibe {@link #renderDaily}, leido
     * del mismo snapshot, y las secciones financieras las dibuja el mismo
     * {@link #financialSections}. No hay dos caminos que puedan separarse.
     *
     * Antes si los habia, y se separaron: el resumen atribuia por su cuenta una
     * forma de cobro a cada grupo de producto y acabo imprimiendo "sin forma de
     * cobro registrada" sobre un cierre que el otro papel declaraba cobrado
     * integro en efectivo. Dos documentos del mismo dia no pueden contradecirse.
     */
    @Override
    public byte[] renderProductSummary(
            CashClosureResponseDTO closure,
            CashClosureProductSummaryDTO summary,
            ReportHeaderDTO header,
            String responsible) {

        LocalDateTime generatedAt = LocalDateTime.now(clock);
        try (PdfCanvas canvas = new PdfCanvas()) {
            documentHeader(canvas, header, "RESUMEN DE PRODUCTOS",
                    ReportFormats.date(closure.businessDate()));

            statusBadge(canvas, false);
            productSummaryMetadata(canvas, closure, responsible, generatedAt);

            // La UNICA diferencia con el cierre de caja.
            productGroupsSection(canvas, summary);
            // Y de aqui abajo, literalmente el mismo documento.
            financialSections(canvas, closure);

            return canvas.finish(
                    "Generado el " + ReportFormats.dateTime(generatedAt),
                    "Cierre " + closure.id());
        } catch (IOException exception) {
            throw new FileProcessingException(
                    "No se pudo generar el PDF del resumen de productos");
        }
    }

    /**
     * Las secciones FINANCIERAS del cierre, compartidas por los dos documentos.
     *
     * Existe para que la igualdad entre los dos PDF sea estructural y no una
     * coincidencia que haya que vigilar: no hay una segunda version de estas
     * secciones que pueda quedarse atras cuando la primera cambie.
     *
     * No calcula nada. Recibe el snapshot ya mapeado y lo dibuja; las cifras las
     * decidio el cierre al confirmarse, y en particular el desglose por forma de
     * pago sale de `cash_closure_payment_method` a traves de
     * {@code closure.collections()}, que es la UNICA fuente de esa verdad.
     */
    private void financialSections(PdfCanvas canvas, CashClosureResponseDTO closure)
            throws IOException {
        invoicingSection(canvas, closure.invoicing());
        exchangeSection(canvas, closure.exchange());
        collectionsSection(canvas, closure.collections());
        refundsSection(canvas, closure);
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

    /**
     * Que se vendio. Va primero porque es la pregunta con la que el taller
     * empieza a leer el cierre.
     *
     * Es actividad FACTURADA: una cambiaria emitida y aun sin cobrar aporta sus
     * articulos aqui, aunque su dinero no aparezca en el total cobrado.
     */
    private void soldItemsSection(PdfCanvas canvas, CashClosureResponseDTO closure)
            throws IOException {
        sectionTitle(canvas, "ARTICULOS VENDIDOS");

        if (closure.items().isEmpty()) {
            emptyRow(canvas, "No se facturaron articulos en el periodo.");
            return;
        }

        float[] widths = { 210f, 78f, 88f, 96f };
        String[] titles = { "Articulo", "Tipo", "Cantidad", "Facturado" };
        tableHeader(canvas, widths, titles);

        boolean shaded = false;
        for (ClosureSoldItemDTO item : closure.items()) {
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
            canvas.text(canvas.fit(item.name(), PdfCanvas.REGULAR, 8f, widths[0] - 8f),
                    x + 4f, baseline,
                    PdfCanvas.REGULAR, 8f, ReportPalette.TEXT);
            x += widths[0];
            canvas.text(ReportFormats.itemType(item.type()), x + 4f, baseline,
                    PdfCanvas.REGULAR, 8f, ReportPalette.TEXT_MUTED);
            x += widths[1];
            canvas.textRight(ReportFormats.count(item.quantity()),
                    x + widths[2] - 4f, baseline, PdfCanvas.REGULAR, 8f,
                    ReportPalette.TEXT);
            x += widths[2];
            canvas.textRight(ReportFormats.money(item.billedAmount()),
                    x + widths[3] - 4f, baseline, PdfCanvas.BOLD, 8f, ReportPalette.TEXT);

            canvas.moveDown(ROW_HEIGHT);
        }

        canvas.line(canvas.left(), canvas.cursor(), canvas.right(), canvas.cursor(),
                ReportPalette.BORDER, 0.6f);
        totalRow(canvas, "TOTAL FACTURADO EN ARTICULOS", closure.itemsBilledAmount());
    }

    private void invoicingSection(PdfCanvas canvas, CashClosureInvoicingDTO invoicing)
            throws IOException {
        sectionTitle(canvas, "FACTURACION EMITIDA");
        amountRow(canvas, "Facturas normales",
                invoicing.invoiceCount(), invoicing.invoiceTotal(), false);
        amountRow(canvas, "Facturas cambiarias",
                invoicing.exchangeInvoiceCount(), invoicing.exchangeInvoiceTotal(), true);
        totalRow(canvas, "TOTAL FACTURADO", invoicing.totalInvoiced());
    }

    /**
     * Las cambiarias en sus DOS papeles: las emitidas en el periodo, con su
     * estado al cerrar, y las cobradas en el periodo, se emitieran cuando se
     * emitieran.
     *
     * Que un documento salga en las dos listas no lo cuenta dos veces: el total
     * cobrado no se suma de aqui, sino del desglose por forma de pago.
     */
    private void exchangeSection(PdfCanvas canvas, CashClosureExchangeDTO exchange)
            throws IOException {
        sectionTitle(canvas, "FACTURAS CAMBIARIAS");

        amountRow(canvas, "Emitidas en el periodo",
                exchange.issuedCount(), exchange.issuedAmount(), false);
        labelledAmountRow(canvas, "   pendientes de cobro al cerrar",
                exchange.issuedPendingAmount(), true, ReportPalette.TEXT_MUTED);
        amountRow(canvas, "Cobradas en el periodo",
                exchange.collectedCount(), exchange.collectedAmount(), false);
        labelledAmountRow(canvas, "   emitidas en periodos anteriores",
                exchange.previousCollectedAmount(), true, ReportPalette.TEXT_MUTED);

        canvas.moveDown(8f);
        exchangeTable(canvas, "Emitidas en el periodo", exchange.issued());
        exchangeTable(canvas, "Cobradas en el periodo", exchange.collected());
    }

    private void exchangeTable(
            PdfCanvas canvas, String caption, List<ClosureExchangeInvoiceDTO> invoices)
            throws IOException {

        canvas.ensureSpace(ROW_HEIGHT * 2f);
        canvas.text(caption, canvas.left(), canvas.cursor() - 9f,
                PdfCanvas.BOLD, 8.5f, ReportPalette.TEXT_MUTED);
        canvas.moveDown(13f);

        if (invoices.isEmpty()) {
            emptyRow(canvas, "Sin documentos.");
            return;
        }

        float[] widths = { 74f, 150f, 84f, 82f, 82f };
        String[] titles = { "Documento", "Cliente", "Emitida", "Monto", "Estado" };
        tableHeader(canvas, widths, titles);

        boolean shaded = false;
        for (ClosureExchangeInvoiceDTO invoice : invoices) {
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
            canvas.text(invoice.documentCode(), x + 4f, baseline,
                    PdfCanvas.BOLD, 8f, ReportPalette.TEXT);
            x += widths[0];
            canvas.text(
                    canvas.fit(
                            // Sin nombre solo queda una venta a Consumidor
                            // Final (V40 clasifico asi la antigua venta de
                            // mostrador): se presenta igual que en la caja.
                            invoice.customerName() == null
                                    ? SaleNames.CONSUMIDOR_FINAL : invoice.customerName(),
                            PdfCanvas.REGULAR, 8f, widths[1] - 8f),
                    x + 4f, baseline, PdfCanvas.REGULAR, 8f, ReportPalette.TEXT);
            x += widths[1];
            canvas.text(ReportFormats.date(invoice.issuedAt().toLocalDate()),
                    x + 4f, baseline, PdfCanvas.REGULAR, 8f, ReportPalette.TEXT_MUTED);
            x += widths[2];
            canvas.textRight(ReportFormats.money(invoice.amount()),
                    x + widths[3] - 4f, baseline, PdfCanvas.REGULAR, 8f,
                    ReportPalette.TEXT);
            x += widths[3];
            // El estado NO se distingue solo por color: lleva su palabra, que es
            // lo unico que sobrevive a una impresion en blanco y negro.
            canvas.text(invoice.collected() ? "Cobrada" : "Pendiente",
                    x + 4f, baseline, PdfCanvas.BOLD, 8f,
                    invoice.collected() ? ReportPalette.SUCCESS : ReportPalette.TEXT_MUTED);

            canvas.moveDown(ROW_HEIGHT);
        }

        canvas.line(canvas.left(), canvas.cursor(), canvas.right(), canvas.cursor(),
                ReportPalette.BORDER, 0.6f);
        canvas.moveDown(10f);
    }

    /**
     * Como entro el dinero. La suma de las formas de pago ES el total cobrado, y
     * por eso el total se imprime justo debajo: si no cuadrara, se veria.
     */
    private void collectionsSection(
            PdfCanvas canvas, CashClosureCollectionsDTO collections) throws IOException {
        sectionTitle(canvas, "COBROS POR FORMA DE PAGO");

        boolean alternate = false;
        for (ClosurePaymentMethodDTO method : collections.methods()) {
            amountRow(canvas, ReportFormats.paymentMethod(method.method()),
                    method.count(), method.amount(), alternate);
            alternate = !alternate;
        }
        totalRow(canvas, "TOTAL COBRADO", collections.totalCollected());
    }

    /**
     * El dinero que SALIO, y el neto.
     *
     * Va despues de los cobros y nunca mezclado con ellos: el papel diria
     * "TOTAL COBRADO Q6,000" y quien lo lee tiene derecho a ver, en la misma
     * hoja, que se devolvieron Q1,500 y que el dia cerro en Q4,500. Un PDF que
     * enseñara solo el primer numero seria exacto y enganoso a la vez.
     */
    private void refundsSection(PdfCanvas canvas, CashClosureResponseDTO closure)
            throws IOException {
        CashClosureRefundsDTO refunds = closure.refunds();
        sectionTitle(canvas, "DEVOLUCIONES DEL PERIODO");

        boolean alternate = false;
        for (ClosureRefundMethodDTO method : refunds.methods()) {
            amountRow(canvas, ReportFormats.paymentMethod(method.method()),
                    method.count(), method.amount(), alternate);
            alternate = !alternate;
        }
        totalRow(canvas, "TOTAL DEVUELTO", refunds.totalRefunded());

        // El neto cierra el documento porque es la unica cifra que responde
        // "con cuanto me quedo": ni lo cobrado ni lo devuelto lo hacen solos.
        sectionTitle(canvas, "NETO DEL PERIODO");
        labelledAmountRow(canvas, "Total cobrado",
                closure.collections().totalCollected(), false, ReportPalette.TEXT);
        labelledAmountRow(canvas, "Total devuelto",
                refunds.totalRefunded(), true, ReportPalette.TEXT_MUTED);
        totalRow(canvas, "NETO DEL PERIODO", closure.netPeriod());
    }

    private void emptyRow(PdfCanvas canvas, String message) throws IOException {
        canvas.ensureSpace(ROW_HEIGHT);
        canvas.text(message, canvas.left() + 4f, canvas.cursor() - 9f,
                PdfCanvas.REGULAR, 9f, ReportPalette.TEXT_MUTED);
        canvas.moveDown(16f);
    }

    // ── Secciones del resumen de productos ───────────────────────

    /**
     * Los mismos datos de identidad que el cierre, porque este papel se archiva
     * junto a el y tiene que poder emparejarse sin abrir el otro.
     */
    private void productSummaryMetadata(
            PdfCanvas canvas,
            CashClosureResponseDTO closure,
            String responsible,
            LocalDateTime generatedAt) throws IOException {

        metadataRow(canvas, "Fecha operativa",
                ReportFormats.date(closure.businessDate()));
        metadataRow(canvas, "Responsable del cierre",
                responsible == null ? "-" : responsible);
        metadataRow(canvas, "Cerrado el", ReportFormats.dateTime(closure.closedAt()));
        metadataRow(canvas, "Identificador", String.valueOf(closure.id()));
        metadataRow(canvas, "Generado", ReportFormats.dateTime(generatedAt));
        canvas.moveDown(10f);
    }

    /**
     * La tabla del resumen: una fila por grupo comercial.
     *
     * Es la UNICA diferencia de contenido entre este documento y el cierre de
     * caja. Alli la misma mercaderia se lista como articulo y tipo; aqui como
     * familia, marca y modelo.
     *
     * Las medidas NO aparecen, y esa ausencia es el documento entero. Tres hojas
     * de resorte Toyota Hilux de 80, 90 y 100 son tres productos del catalogo y
     * UNA linea aqui: quien repone compra "hoja Hilux", no "hoja Hilux de 90".
     *
     * Lo que esta tabla NO hace es repartirse por forma de cobro. Como entro el
     * dinero lo dice la seccion de cobros, mas abajo y una sola vez, con las
     * cifras del cierre.
     */
    private void productGroupsSection(
            PdfCanvas canvas, CashClosureProductSummaryDTO summary) throws IOException {
        sectionTitle(canvas, "RESUMEN DE PRODUCTOS");

        if (summary.groups().isEmpty()) {
            emptyRow(canvas, "No se facturaron productos en el periodo.");
            totalRow(canvas, "TOTAL GENERADO EN PRODUCTOS", summary.totalAmount());
            excludedJobsNote(canvas, summary);
            unclassifiedNote(canvas, summary);
            return;
        }

        float[] widths = { 148f, 96f, 96f, 76f, 104f };
        String[] titles = { "Familia", "Marca", "Modelo", "Cantidad", "Monto generado" };
        tableHeader(canvas, widths, titles, 3);

        boolean shaded = false;
        for (CashClosureProductGroupDTO group : summary.groups()) {
            if (canvas.ensureSpace(ROW_HEIGHT)) {
                // La cabecera se repite en cada hoja: una tabla larga cuya
                // segunda pagina empieza en numeros sueltos no se puede leer.
                tableHeader(canvas, widths, titles, 3);
                shaded = false;
            }
            float top = canvas.cursor();
            if (shaded) {
                canvas.fillRect(canvas.left(), top - ROW_HEIGHT, canvas.contentWidth(),
                        ROW_HEIGHT, ReportPalette.SURFACE_MUTED);
            }
            shaded = !shaded;

            float baseline = top - ROW_HEIGHT + 4.5f;
            float x = canvas.left();
            // La familia NUNCA se imprime como guion: un guion aqui se leeria
            // como "no aplica", igual que en marca y modelo, y la familia
            // siempre aplica. Cuando falta es que no se supo, y eso se dice.
            boolean unclassified = group.familyId() == null;
            canvas.text(
                    canvas.fit(unclassified ? UNCLASSIFIED : dimension(group.familyName()),
                            PdfCanvas.BOLD, 8f, widths[0] - 8f),
                    x + 4f, baseline, PdfCanvas.BOLD, 8f,
                    unclassified ? ReportPalette.TEXT_MUTED : ReportPalette.TEXT);
            x += widths[0];
            canvas.text(
                    canvas.fit(dimension(group.brandName()),
                            PdfCanvas.REGULAR, 8f, widths[1] - 8f),
                    x + 4f, baseline, PdfCanvas.REGULAR, 8f, ReportPalette.TEXT);
            x += widths[1];
            canvas.text(
                    canvas.fit(dimension(group.modelName()),
                            PdfCanvas.REGULAR, 8f, widths[2] - 8f),
                    x + 4f, baseline, PdfCanvas.REGULAR, 8f, ReportPalette.TEXT);
            x += widths[2];
            canvas.textRight(ReportFormats.count(group.quantity()),
                    x + widths[3] - 4f, baseline, PdfCanvas.REGULAR, 8f,
                    ReportPalette.TEXT);
            x += widths[3];
            canvas.textRight(ReportFormats.money(group.amount()),
                    x + widths[4] - 4f, baseline, PdfCanvas.BOLD, 8f, ReportPalette.TEXT);

            canvas.moveDown(ROW_HEIGHT);
        }

        canvas.line(canvas.left(), canvas.cursor(), canvas.right(), canvas.cursor(),
                ReportPalette.BORDER, 0.6f);

        countRow(canvas, "TOTAL UNIDADES", summary.totalQuantity(), false);
        totalRow(canvas, "TOTAL GENERADO EN PRODUCTOS", summary.totalAmount());
        excludedJobsNote(canvas, summary);
        unclassifiedNote(canvas, summary);
    }

    /**
     * Por que este total no coincide con el del cierre.
     *
     * La diferencia son los servicios, que no son mercaderia y no tienen marca
     * ni modelo que agrupar. Decirlo en el papel evita que alguien compare las
     * dos hojas, vea dos cifras distintas y concluya que una esta mal.
     *
     * Solo se imprime cuando hubo servicios: una nota que explica una diferencia
     * de cero es ruido.
     */
    private void excludedJobsNote(
            PdfCanvas canvas, CashClosureProductSummaryDTO summary) throws IOException {
        if (summary.excludedJobAmount().signum() == 0) {
            return;
        }
        canvas.ensureSpace(ROW_HEIGHT * 2f);
        canvas.text(
                "No incluye servicios facturados por "
                        + ReportFormats.money(summary.excludedJobAmount())
                        + ", que se detallan en el cierre de caja.",
                canvas.left() + 4f, canvas.cursor() - 9f,
                PdfCanvas.REGULAR, 8f, ReportPalette.TEXT_MUTED);
        canvas.moveDown(14f);
    }

    /**
     * Por que hay una fila sin familia.
     *
     * Solo ocurre en cierres anteriores a la migracion que anadio las
     * dimensiones, y solo si ademas el producto ya no esta en el catalogo. Se
     * escribe en el papel porque un renglon "SIN CLASIFICAR" sin explicacion
     * parece un fallo del reporte, y no lo es: es un dato que nunca se
     * registro.
     *
     * Su importe SI esta en el total. Decir lo contrario, o descontarlo, seria
     * hacer desaparecer dinero facturado para que la tabla quedara bonita.
     */
    private void unclassifiedNote(
            PdfCanvas canvas, CashClosureProductSummaryDTO summary) throws IOException {
        if (summary.unclassifiedAmount().signum() == 0) {
            return;
        }
        canvas.ensureSpace(ROW_HEIGHT * 2f);
        canvas.text(
                "Incluye " + ReportFormats.money(summary.unclassifiedAmount())
                        + " sin clasificar: cierre anterior al registro de familia,"
                        + " marca y modelo, con productos ya retirados del catalogo.",
                canvas.left() + 4f, canvas.cursor() - 9f,
                PdfCanvas.REGULAR, 8f, ReportPalette.TEXT_MUTED);
        canvas.moveDown(14f);
    }

    /**
     * Una dimension que no aplica se imprime como guion largo.
     *
     * Es TINTA y nada mas: lo que agrupo fue el nulo, no este texto. Si el guion
     * fuera la clave, una familia sin marca y otra sin marca se fundirian por
     * parecerse en el papel.
     */
    private static String dimension(String name) {
        return name == null || name.isBlank() ? "\u2014" : name;
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


    /**
     * Cabecera de tabla en color de marca. La primera columna alinea a la
     * izquierda y el resto a la derecha, que es donde caen cantidades e
     * importes.
     */
    private void tableHeader(PdfCanvas canvas, float[] widths, String[] titles)
            throws IOException {
        tableHeader(canvas, widths, titles, 2);
    }

    /**
     * @param leftAligned cuantas columnas iniciales llevan texto. El resto son
     *        cantidades e importes y se alinean por la derecha, que es como se
     *        comparan de un vistazo dos numeros de distinto ancho.
     */
    private void tableHeader(
            PdfCanvas canvas, float[] widths, String[] titles, int leftAligned)
            throws IOException {
        canvas.ensureSpace(ROW_HEIGHT + 2f);
        float top = canvas.cursor();
        canvas.fillRect(canvas.left(), top - ROW_HEIGHT, canvas.contentWidth(),
                ROW_HEIGHT, ReportPalette.PRIMARY);

        float baseline = top - ROW_HEIGHT + 4.5f;
        float x = canvas.left();
        for (int index = 0; index < titles.length; index++) {
            if (index < leftAligned) {
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

}
