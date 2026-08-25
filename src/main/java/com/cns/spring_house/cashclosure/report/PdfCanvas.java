package com.cns.spring_house.cashclosure.report;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

/**
 * Lo minimo para componer estos reportes sobre PDFBox: un cursor vertical,
 * paginacion automatica y las primitivas de dibujo que usan las plantillas.
 *
 * PDFBox trabaja en coordenadas absolutas con el origen abajo a la izquierda y
 * no tiene nocion de "flujo": si el contenido no cabe, lo escribe fuera de la
 * pagina sin avisar. Esta clase existe para que las plantillas puedan decir
 * "escribe esta fila" y no "dibuja en x=48, y=612", que es donde se cuelan los
 * textos cortados y las tablas desbordadas.
 *
 * Las fuentes son las Standard 14 (Helvetica), que no se incrustan y hacen el
 * archivo mucho mas pequeno. Su codificacion es WinAnsi, que cubre el espanol
 * completo —tildes, dieresis, ene y signos de apertura—, pero no cualquier
 * simbolo: por eso todo texto pasa por {@link #sanitize(String)} antes de
 * escribirse, y un caracter fuera de juego sale como '?' en vez de reventar la
 * generacion del documento con datos reales de un taller.
 */
final class PdfCanvas implements Closeable {

    static final PDRectangle PAGE_SIZE = PDRectangle.LETTER;
    static final float MARGIN = 46f;
    /**
     * Espacio reservado abajo para el pie. El filete del pie se dibuja a 56
     * puntos del borde inferior, asi que con esta reserva el contenido siempre
     * termina 16 puntos por encima de el: suficiente para que no se toquen y sin
     * regalar tanto alto como para partir en dos un cierre que cabe en una hoja.
     */
    static final float FOOTER_HEIGHT = 26f;

    static final PDFont REGULAR = new PDType1Font(FontName.HELVETICA);
    static final PDFont BOLD = new PDType1Font(FontName.HELVETICA_BOLD);

    /**
     * WinAnsi es, a efectos practicos, windows-1252. Se comprueba contra ese
     * juego en vez de contra una lista escrita a mano para no dejarse fuera
     * medio alfabeto acentuado.
     */
    private static final Charset WIN_ANSI = Charset.forName("windows-1252");

    private final PDDocument document = new PDDocument();
    private final List<PDPage> pages = new ArrayList<>();

    private PDPageContentStream stream;
    private float cursor;

    PdfCanvas() throws IOException {
        newPage();
    }

    // ── Pagina y cursor ──────────────────────────────────────────

    float left() {
        return MARGIN;
    }

    float right() {
        return PAGE_SIZE.getWidth() - MARGIN;
    }

    float contentWidth() {
        return PAGE_SIZE.getWidth() - 2 * MARGIN;
    }

    float cursor() {
        return cursor;
    }

    void moveDown(float amount) {
        cursor -= amount;
    }

    void newPage() throws IOException {
        if (stream != null) {
            stream.close();
        }
        PDPage page = new PDPage(PAGE_SIZE);
        document.addPage(page);
        pages.add(page);
        stream = new PDPageContentStream(document, page);
        cursor = PAGE_SIZE.getHeight() - MARGIN;
    }

    /**
     * Abre pagina nueva si lo que viene no cabe entero.
     *
     * @return true si hubo salto, para que quien dibuja una tabla pueda repetir
     *         su fila de encabezado.
     */
    boolean ensureSpace(float needed) throws IOException {
        if (cursor - needed >= MARGIN + FOOTER_HEIGHT) {
            return false;
        }
        newPage();
        return true;
    }

    // ── Primitivas ───────────────────────────────────────────────

    void text(String value, float x, float y, PDFont font, float size, Color color)
            throws IOException {
        stream.beginText();
        stream.setFont(font, size);
        stream.setNonStrokingColor(color);
        stream.newLineAtOffset(x, y);
        stream.showText(sanitize(value));
        stream.endText();
    }

    /** Alinea por el borde derecho, que es como se leen los importes. */
    void textRight(String value, float rightX, float y, PDFont font, float size, Color color)
            throws IOException {
        text(value, rightX - widthOf(value, font, size), y, font, size, color);
    }

    void fillRect(float x, float y, float width, float height, Color color)
            throws IOException {
        stream.setNonStrokingColor(color);
        stream.addRect(x, y, width, height);
        stream.fill();
    }

    void line(float fromX, float fromY, float toX, float toY, Color color, float width)
            throws IOException {
        stream.setStrokingColor(color);
        stream.setLineWidth(width);
        stream.moveTo(fromX, fromY);
        stream.lineTo(toX, toY);
        stream.stroke();
    }

    void image(byte[] bytes, String name, float x, float y, float width, float height)
            throws IOException {
        PDImageXObject image = PDImageXObject.createFromByteArray(document, bytes, name);
        stream.drawImage(image, x, y, width, height);
    }

    /**
     * Alto que ocupara la imagen respetando su proporcion. El logo no se
     * deforma para encajar en una caja: se escala por el ancho y el alto es
     * consecuencia.
     */
    float scaledHeight(byte[] bytes, float width) throws IOException {
        PDImageXObject image = PDImageXObject.createFromByteArray(document, bytes, "measure");
        return width * image.getHeight() / (float) image.getWidth();
    }

    float widthOf(String value, PDFont font, float size) throws IOException {
        return font.getStringWidth(sanitize(value)) / 1000f * size;
    }

    /** Recorta por caracteres hasta que el texto cabe en el ancho dado. */
    String fit(String value, PDFont font, float size, float maxWidth) throws IOException {
        if (value == null || value.isEmpty() || widthOf(value, font, size) <= maxWidth) {
            return value;
        }
        String ellipsis = "...";
        StringBuilder shortened = new StringBuilder(value);
        while (shortened.length() > 1
                && widthOf(shortened + ellipsis, font, size) > maxWidth) {
            shortened.setLength(shortened.length() - 1);
        }
        return shortened + ellipsis;
    }

    // ── Cierre ───────────────────────────────────────────────────

    /**
     * Estampa el pie en TODAS las paginas y devuelve el documento.
     *
     * Se hace al final y no sobre la marcha porque "Pagina 2 de 5" no se puede
     * escribir hasta saber que hay cinco: mientras se dibuja el contenido, el
     * total todavia no existe.
     */
    byte[] finish(String footerLeft, String footerRight) throws IOException {
        stream.close();
        stream = null;

        int total = pages.size();
        for (int index = 0; index < total; index++) {
            PDPage page = pages.get(index);
            try (PDPageContentStream footer = new PDPageContentStream(
                    document, page, AppendMode.APPEND, true, true)) {
                float baseline = MARGIN - 4f;
                footer.setStrokingColor(ReportPalette.BORDER);
                footer.setLineWidth(0.6f);
                footer.moveTo(MARGIN, baseline + 14f);
                footer.lineTo(PAGE_SIZE.getWidth() - MARGIN, baseline + 14f);
                footer.stroke();

                writeFooterText(footer, footerLeft, MARGIN, baseline);

                // El numero de pagina solo aporta cuando hay mas de una; en un
                // documento de una hoja es ruido.
                String right = total > 1
                        ? footerRight + "   |   Pagina " + (index + 1) + " de " + total
                        : footerRight;
                float width = widthOf(right, REGULAR, 7.5f);
                writeFooterText(
                        footer, right, PAGE_SIZE.getWidth() - MARGIN - width, baseline);
            }
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        document.save(output);
        return output.toByteArray();
    }

    private void writeFooterText(
            PDPageContentStream footer, String value, float x, float y)
            throws IOException {
        footer.beginText();
        footer.setFont(REGULAR, 7.5f);
        footer.setNonStrokingColor(ReportPalette.TEXT_MUTED);
        footer.newLineAtOffset(x, y);
        footer.showText(sanitize(value));
        footer.endText();
    }

    @Override
    public void close() throws IOException {
        if (stream != null) {
            stream.close();
        }
        document.close();
    }

    /**
     * Sustituye por '?' lo que la codificacion WinAnsi de las fuentes estandar
     * no sabe representar.
     *
     * No es cosmetico: el nombre de un taller, el de un empleado o una nota de
     * cierre son texto libre escrito por personas, y un solo caracter fuera de
     * juego —una comilla tipografica pegada de Word, un emoji— haria que
     * PDFBox lanzara al escribirlo y que el cierre no se pudiera exportar.
     */
    static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        CharsetEncoder encoder = WIN_ANSI.newEncoder();
        StringBuilder safe = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '\n' || character == '\r' || character == '\t') {
                safe.append(' ');
            } else if (encoder.canEncode(character)) {
                safe.append(character);
            } else {
                safe.append('?');
            }
        }
        return safe.toString();
    }

}
