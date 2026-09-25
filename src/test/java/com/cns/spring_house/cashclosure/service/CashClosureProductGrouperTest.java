package com.cns.spring_house.cashclosure.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.cns.spring_house.cashclosure.dto.CashClosureProductGroupDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureProductSummaryDTO;
import com.cns.spring_house.cashclosure.entity.CashClosureItem;
import com.cns.spring_house.family.entity.FamilyCategory;

/**
 * Que es "el mismo grupo comercial".
 *
 * Es la regla de negocio entera del segundo reporte, asi que se prueba sola y
 * sin base de datos: la agrupacion no depende de JPA ni de PDFBox, y una prueba
 * que necesitara los dos para responder "estas tres hojas son una fila" tardaria
 * diez segundos en decir algo que se decide en memoria.
 *
 * El caso que da nombre a todo el reporte es {@link #collapsesMeasurements()}:
 * tres productos DISTINTOS del catalogo, con medidas distintas, que el taller
 * lee como una sola cosa.

 *
 * COMO se cobro no se decide aqui, y no es un olvido: el cierre ya lo guarda
 * una sola vez en `cash_closure_payment_method`, y el PDF del resumen imprime
 * ese mismo desglose. Ver {@code CashClosureReportServiceImplTest}.
 */
class CashClosureProductGrouperTest {

    private final CashClosureProductGrouper grouper = new CashClosureProductGrouper();

    // ── Identidades del catalogo ─────────────────────────────────

    private static final UUID HOJA = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID PERNO = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final UUID MALETA = UUID.fromString("00000000-0000-0000-0000-0000000000a3");

    private static final UUID TOYOTA = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID NISSAN = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    private static final UUID HILUX = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID TACOMA = UUID.fromString("00000000-0000-0000-0000-0000000000c2");

    // ── A. Mismas dimensiones, medidas distintas -> UNA fila ─────

    /**
     * El escenario del enunciado. Tres hojas Toyota Hilux de 80, 90 y 100 son
     * tres filas de `cash_closure_item` —tres `product.id` distintos— y una sola
     * linea del reporte.
     */
    @Test
    void collapsesMeasurements() {
        CashClosureProductSummaryDTO summary = grouper.summarize(List.of(
                item("HOJA DE RESORTE 80", HOJA, TOYOTA, HILUX, 1, "100.00"),
                item("HOJA DE RESORTE 90", HOJA, TOYOTA, HILUX, 2, "150.00"),
                item("HOJA DE RESORTE 100", HOJA, TOYOTA, HILUX, 3, "200.00")));

        assertThat(groups(summary)).hasSize(1);
        CashClosureProductGroupDTO group = groups(summary).get(0);
        assertThat(group.familyName()).isEqualTo("HOJA DE RESORTE");
        assertThat(group.brandName()).isEqualTo("Toyota");
        assertThat(group.modelName()).isEqualTo("Hilux");
        assertThat(group.quantity()).isEqualTo(6L);
        assertThat(group.amount()).isEqualByComparingTo("450.00");
    }

    // ── B. Marca distinta -> dos grupos ──────────────────────────

    @Test
    void splitsByBrand() {
        CashClosureProductSummaryDTO summary = grouper.summarize(List.of(
                item("HOJA TOYOTA", HOJA, TOYOTA, HILUX, 1, "100.00"),
                item("HOJA NISSAN", HOJA, NISSAN, HILUX, 1, "120.00")));

        assertThat(groups(summary)).hasSize(2);
        assertThat(groups(summary))
                .extracting(CashClosureProductGroupDTO::brandName)
                .containsExactly("Nissan", "Toyota");
    }

    // ── C. Modelo distinto -> dos grupos ─────────────────────────

    @Test
    void splitsByModel() {
        CashClosureProductSummaryDTO summary = grouper.summarize(List.of(
                item("HOJA HILUX", HOJA, TOYOTA, HILUX, 1, "100.00"),
                item("HOJA TACOMA", HOJA, TOYOTA, TACOMA, 1, "130.00")));

        assertThat(groups(summary)).hasSize(2);
        assertThat(groups(summary))
                .extracting(CashClosureProductGroupDTO::modelName)
                .containsExactly("Hilux", "Tacoma");
    }

    // ── D. Familia sin marca ni modelo ───────────────────────────

    /**
     * Una familia con {@code requiresModel} en false no tiene marca ni modelo.
     * Sus productos —que se diferencian solo por medidas— caen en una fila con
     * las dos dimensiones nulas, y NO se les inventa ninguna.
     */
    @Test
    void groupsFamilyOnlyProducts() {
        CashClosureProductSummaryDTO summary = grouper.summarize(List.of(
                familyOnly("PERNO 1/2", PERNO, "PERNO", 4, "40.00"),
                familyOnly("PERNO 3/4", PERNO, "PERNO", 6, "75.00")));

        assertThat(groups(summary)).hasSize(1);
        CashClosureProductGroupDTO group = groups(summary).get(0);
        assertThat(group.familyName()).isEqualTo("PERNO");
        assertThat(group.brandId()).isNull();
        assertThat(group.brandName()).isNull();
        assertThat(group.modelId()).isNull();
        assertThat(group.modelName()).isNull();
        assertThat(group.quantity()).isEqualTo(10L);
        assertThat(group.amount()).isEqualByComparingTo("115.00");
    }

    // ── E/F. Dimensiones opcionales presentes solo en parte ──────

    /**
     * Una familia con marca y modelo y otra sin ninguno de los dos NO se funden,
     * aunque la segunda se imprima con guiones: lo que agrupa es el nulo, no el
     * texto.
     *
     * "Familia + marca sin modelo" y "familia + modelo sin marca" no se prueban
     * como casos separados porque el dominio no puede producirlos: la marca se
     * alcanza a traves del modelo (`product.model_id` -> `model.brand_id`, NOT
     * NULL), asi que o vienen las dos o no viene ninguna. Lo que si se prueba es
     * que la clave las trata por separado, para que siga siendo correcta si el
     * catalogo abre esa puerta.
     */
    @Test
    void keepsPartialDimensionsApart() {
        CashClosureProductSummaryDTO summary = grouper.summarize(List.of(
                item("HOJA HILUX", HOJA, TOYOTA, HILUX, 1, "100.00"),
                familyOnly("HOJA GENERICA", HOJA, "HOJA DE RESORTE", 1, "60.00"),
                brandOnly("HOJA TOYOTA", HOJA, TOYOTA, 1, "80.00")));

        assertThat(groups(summary)).hasSize(3);
        assertThat(groups(summary))
                .extracting(
                        CashClosureProductGroupDTO::brandName,
                        CashClosureProductGroupDTO::modelName)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("Toyota", "Hilux"),
                        org.assertj.core.groups.Tuple.tuple("Toyota", null),
                        org.assertj.core.groups.Tuple.tuple(null, null));
    }

    // ── G. product.id NO es dimension de negocio ─────────────────

    /**
     * Dos productos distintos con las mismas dimensiones son una fila; el mismo
     * producto repetido tambien. Que la clave ignore {@code productId} es lo que
     * hace ciertas las dos cosas a la vez.
     */
    @Test
    void ignoresProductIdentity() {
        UUID sameProduct = UUID.randomUUID();
        CashClosureItem first = item("HOJA", HOJA, TOYOTA, HILUX, 1, "50.00");
        first.setProductId(sameProduct);
        CashClosureItem second = item("HOJA", HOJA, TOYOTA, HILUX, 1, "50.00");
        second.setProductId(sameProduct);

        assertThat(groups(grouper.summarize(List.of(first, second)))).hasSize(1);
    }

    // ── H/I/J. Cantidades, importes y gran total ─────────────────

    /**
     * El gran total es EXACTAMENTE la suma de los importes de origen, comparada
     * con {@code compareTo} y no con {@code equals}: 450.00 y 450.0 son el mismo
     * dinero aunque sean BigDecimal distintos.
     */
    @Test
    void grandTotalReconcilesWithSource() {
        List<CashClosureItem> items = List.of(
                item("HOJA 80", HOJA, TOYOTA, HILUX, 1, "100.00"),
                item("HOJA 90", HOJA, TOYOTA, HILUX, 2, "150.55"),
                item("HOJA TACOMA", HOJA, TOYOTA, TACOMA, 3, "200.45"),
                familyOnly("PERNO", PERNO, "PERNO", 4, "33.33"));

        CashClosureProductSummaryDTO summary = grouper.summarize(items);

        BigDecimal source = items.stream()
                .map(CashClosureItem::getBilledAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal grouped = groups(summary).stream()
                .map(CashClosureProductGroupDTO::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(grouped).isEqualByComparingTo(source);
        assertThat(summary.totalAmount()).isEqualByComparingTo(source);
        assertThat(summary.totalQuantity()).isEqualTo(10L);
        assertThat(summary.totalQuantity()).isEqualTo(
                groups(summary).stream()
                        .mapToLong(CashClosureProductGroupDTO::quantity)
                        .sum());
    }

    /** Dos decimales siempre, tambien cuando la suma sale redonda. */
    @Test
    void keepsMoneyScale() {
        CashClosureProductSummaryDTO summary = grouper.summarize(List.of(
                item("HOJA", HOJA, TOYOTA, HILUX, 1, "100")));

        assertThat(summary.totalAmount().scale()).isEqualTo(2);
        assertThat(groups(summary).get(0).amount().scale()).isEqualTo(2);
    }

    // ── K. Servicios fuera, y dicho en voz alta ──────────────────

    /**
     * Un servicio no es mercaderia, no tiene marca ni modelo y no se le inventan.
     * Su dinero NO desaparece: sale en {@code excludedJobAmount} para que la
     * diferencia con el total de articulos del cierre sea explicable.
     */
    @Test
    void excludesJobsAndReportsTheirAmount() {
        CashClosureProductSummaryDTO summary = grouper.summarize(List.of(
                item("HOJA", HOJA, TOYOTA, HILUX, 1, "100.00"),
                job("INSTALACION", 2, "250.00")));

        assertThat(groups(summary)).hasSize(1);
        assertThat(groups(summary).get(0).familyName()).isEqualTo("HOJA DE RESORTE");
        assertThat(summary.totalQuantity()).isEqualTo(1L);
        assertThat(summary.totalAmount()).isEqualByComparingTo("100.00");
        assertThat(summary.excludedJobAmount()).isEqualByComparingTo("250.00");
    }

    /** NORMAL, SPECIAL y CONTAINER entran; la categoria no parte ningun grupo. */
    @Test
    void groupsEveryMerchandiseCategory() {
        CashClosureItem normal = familyOnly("PERNO", PERNO, "PERNO", 1, "10.00");
        normal.setItemType(FamilyCategory.NORMAL);
        CashClosureItem special = item("HOJA", HOJA, TOYOTA, HILUX, 1, "20.00");
        special.setItemType(FamilyCategory.SPECIAL);
        CashClosureItem container = item("MALETA", MALETA, TOYOTA, HILUX, 1, "30.00");
        container.setItemType(FamilyCategory.CONTAINER);

        CashClosureProductSummaryDTO summary =
                grouper.summarize(List.of(normal, special, container));

        assertThat(groups(summary)).hasSize(3);
        assertThat(summary.totalAmount()).isEqualByComparingTo("60.00");
        assertThat(summary.excludedJobAmount()).isEqualByComparingTo("0.00");
    }

    /**
     * Dos productos de la MISMA familia y marca/modelo pero de categorias
     * distintas siguen siendo un grupo: la categoria no es dimension comercial.
     */
    @Test
    void categoryDoesNotSplitGroups() {
        CashClosureItem special = item("HOJA A", HOJA, TOYOTA, HILUX, 1, "20.00");
        special.setItemType(FamilyCategory.SPECIAL);
        CashClosureItem normal = item("HOJA B", HOJA, TOYOTA, HILUX, 1, "25.00");
        normal.setItemType(FamilyCategory.NORMAL);

        assertThat(groups(grouper.summarize(List.of(special, normal)))).hasSize(1);
    }

    // ── Orden y casos vacios ─────────────────────────────────────

    /** Familia, marca y modelo ascendentes, sin distinguir mayusculas. */
    @Test
    void sortsByFamilyThenBrandThenModel() {
        CashClosureProductSummaryDTO summary = grouper.summarize(List.of(
                item("HOJA TACOMA", HOJA, TOYOTA, TACOMA, 1, "10.00"),
                familyOnly("PERNO", PERNO, "PERNO", 1, "10.00"),
                item("HOJA NISSAN", HOJA, NISSAN, HILUX, 1, "10.00"),
                item("HOJA HILUX", HOJA, TOYOTA, HILUX, 1, "10.00")));

        assertThat(groups(summary))
                .extracting(
                        CashClosureProductGroupDTO::familyName,
                        CashClosureProductGroupDTO::brandName,
                        CashClosureProductGroupDTO::modelName)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "HOJA DE RESORTE", "Nissan", "Hilux"),
                        org.assertj.core.groups.Tuple.tuple(
                                "HOJA DE RESORTE", "Toyota", "Hilux"),
                        org.assertj.core.groups.Tuple.tuple(
                                "HOJA DE RESORTE", "Toyota", "Tacoma"),
                        org.assertj.core.groups.Tuple.tuple("PERNO", null, null));
    }

    /** Sin marca al final de su familia: la fila mas generica cierra el bloque. */
    @Test
    void sortsMissingDimensionsLast() {
        CashClosureProductSummaryDTO summary = grouper.summarize(List.of(
                familyOnly("HOJA GENERICA", HOJA, "HOJA DE RESORTE", 1, "10.00"),
                item("HOJA HILUX", HOJA, TOYOTA, HILUX, 1, "10.00")));

        assertThat(groups(summary))
                .extracting(CashClosureProductGroupDTO::brandName)
                .containsExactly("Toyota", null);
    }

    /** Un cierre sin una sola venta no es un error: es un cierre en cero. */
    @Test
    void handlesEmptyClosure() {
        CashClosureProductSummaryDTO summary = grouper.summarize(List.of());

        assertThat(groups(summary)).isEmpty();
        assertThat(summary.totalQuantity()).isZero();
        assertThat(summary.totalAmount()).isEqualByComparingTo("0.00");
        assertThat(summary.excludedJobAmount()).isEqualByComparingTo("0.00");
    }

    /** Un cierre de solo servicios deja la tabla vacia y el total en cero. */
    @Test
    void handlesJobOnlyClosure() {
        CashClosureProductSummaryDTO summary = grouper.summarize(List.of(
                job("INSTALACION", 1, "300.00")));

        assertThat(groups(summary)).isEmpty();
        assertThat(summary.totalAmount()).isEqualByComparingTo("0.00");
        assertThat(summary.excludedJobAmount()).isEqualByComparingTo("300.00");
    }

    /**
     * Una fila de un cierre anterior a V37 cuyo producto ya no existe llega sin
     * dimensiones. Se agrupa aparte y con sus cifras intactas: el dinero de un
     * historico no se tira porque falte una etiqueta.
     */
    @Test
    void keepsRowsWithoutDimensions() {
        CashClosureItem orphan = base("PRODUCTO BORRADO", 2, "77.00");

        CashClosureProductSummaryDTO summary = grouper.summarize(List.of(
                orphan, item("HOJA", HOJA, TOYOTA, HILUX, 1, "23.00")));

        assertThat(groups(summary)).hasSize(2);
        assertThat(summary.totalAmount()).isEqualByComparingTo("100.00");
        assertThat(groups(summary))
                .filteredOn(group -> group.familyName() == null)
                .singleElement()
                .satisfies(group -> assertThat(group.quantity()).isEqualTo(2L));
    }

    // ── Filas heredadas: el dinero no se pierde por falta de etiqueta ──
    //
    // Solo pueden aparecer en cierres ANTERIORES a V37 cuyo producto ya no esta
    // en el catalogo: la migracion no tuvo de donde reconstruir sus dimensiones.

    /**
     * El importe sin clasificar se CUENTA aparte pero NO se saca del total.
     *
     * Sacarlo dejaria el resumen sin cuadrar con el cierre, y la diferencia
     * seria dinero facturado que desaparece de la vista sin explicacion.
     */
    @Test
    void reportsTheUnclassifiedAmountWithoutRemovingItFromTheTotal() {
        CashClosureProductSummaryDTO summary = grouper.summarize(List.of(
                item("HOJA", HOJA, TOYOTA, HILUX, 1, "23.00"),
                base("PRODUCTO BORRADO", 2, "77.00")));

        assertThat(summary.unclassifiedAmount()).isEqualByComparingTo("77.00");
        assertThat(summary.totalAmount()).isEqualByComparingTo("100.00");
        assertThat(summary.totalQuantity()).isEqualTo(3L);
    }

    /**
     * LA invariante del enunciado, con BigDecimal exacto:
     *
     * <pre>
     * agrupado + servicios excluidos = importe de origen
     * </pre>
     *
     * y se cumple aunque haya filas sin dimensiones. Que falte la etiqueta no
     * puede hacer que falte el dinero.
     */
    @Test
    void everyCentReconcilesEvenWhenLegacyRowsHaveNoDimensions() {
        List<CashClosureItem> items = List.of(
                item("HOJA 80", HOJA, TOYOTA, HILUX, 1, "100.55"),
                item("HOJA 90", HOJA, TOYOTA, HILUX, 2, "150.45"),
                familyOnly("PERNO", PERNO, "PERNO", 4, "33.33"),
                base("BORRADO A", 2, "77.11"),
                base("BORRADO B", 1, "12.89"),
                job("INSTALACION", 1, "250.00"));

        CashClosureProductSummaryDTO summary = grouper.summarize(items);

        BigDecimal source = items.stream()
                .map(CashClosureItem::getBilledAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(summary.totalAmount().add(summary.excludedJobAmount()))
                .isEqualByComparingTo(source);

        // Y lo sin clasificar es una PARTE de lo agrupado, no un tercer sumando.
        assertThat(summary.unclassifiedAmount()).isEqualByComparingTo("90.00");
        assertThat(summary.totalAmount()).isEqualByComparingTo("374.33");
        assertThat(summary.excludedJobAmount()).isEqualByComparingTo("250.00");
    }

    /** Un cierre posterior a V37 no tiene nada sin clasificar. */
    @Test
    void modernClosuresReportNothingUnclassified() {
        CashClosureProductSummaryDTO summary = grouper.summarize(List.of(
                item("HOJA", HOJA, TOYOTA, HILUX, 1, "100.00"),
                familyOnly("PERNO", PERNO, "PERNO", 1, "50.00")));

        assertThat(summary.unclassifiedAmount()).isEqualByComparingTo("0.00");
    }

    /**
     * Varias filas huerfanas caen en UN solo grupo sin clasificar, no en una
     * fila por producto: son todas "no se sabe", y repetir el renglon no anade
     * informacion.
     */
    @Test
    void collapsesEveryUnclassifiedRowIntoASingleGroup() {
        CashClosureProductSummaryDTO summary = grouper.summarize(List.of(
                base("BORRADO A", 1, "10.00"),
                base("BORRADO B", 1, "20.00"),
                base("BORRADO C", 1, "30.00")));

        assertThat(groups(summary)).hasSize(1);
        assertThat(groups(summary).get(0).familyId()).isNull();
        assertThat(groups(summary).get(0).quantity()).isEqualTo(3L);
        assertThat(summary.unclassifiedAmount()).isEqualByComparingTo("60.00");
        assertThat(summary.totalAmount()).isEqualByComparingTo("60.00");
    }

    /** Dos familias que se llaman igual son DOS grupos: agrupa el id. */
    @Test
    void doesNotMergeHomonymousFamilies() {
        CashClosureItem first = familyOnly("A", UUID.randomUUID(), "REPUESTO", 1, "10.00");
        CashClosureItem second = familyOnly("B", UUID.randomUUID(), "REPUESTO", 1, "10.00");

        assertThat(groups(grouper.summarize(List.of(first, second)))).hasSize(2);
    }

    // ── Constructores de filas ───────────────────────────────────

    private static CashClosureItem base(String name, long quantity, String amount) {
        return CashClosureItem.builder()
                .productId(UUID.randomUUID())
                .itemName(name)
                .itemType(FamilyCategory.SPECIAL)
                .quantity(quantity)
                .billedAmount(new BigDecimal(amount))
                .build();
    }

    /**
     * Las filas del resumen.
     *
     * El agrupador NO divide por forma de cobro: como entro el dinero lo dice
     * el desglose de cobros del cierre, que el PDF imprime tal cual, y este
     * agregado responde otra pregunta.
     */
    private static List<CashClosureProductGroupDTO> groups(
            CashClosureProductSummaryDTO summary) {
        return summary.groups();
    }

    /** Familia + marca + modelo: el caso completo. */
    private static CashClosureItem item(
            String name, UUID familyId, UUID brandId, UUID modelId,
            long quantity, String amount) {
        CashClosureItem item = base(name, quantity, amount);
        item.setFamilyId(familyId);
        item.setFamilyName(familyId == HOJA ? "HOJA DE RESORTE" : "MALETA");
        item.setBrandId(brandId);
        item.setBrandName(brandId == TOYOTA ? "Toyota" : "Nissan");
        item.setModelId(modelId);
        item.setModelName(modelId == HILUX ? "Hilux" : "Tacoma");
        return item;
    }

    /** Solo familia: una familia que no trabaja con modelo. */
    private static CashClosureItem familyOnly(
            String name, UUID familyId, String familyName,
            long quantity, String amount) {
        CashClosureItem item = base(name, quantity, amount);
        item.setFamilyId(familyId);
        item.setFamilyName(familyName);
        return item;
    }

    /** Familia + marca, sin modelo. Hoy el dominio no lo produce; la clave si. */
    private static CashClosureItem brandOnly(
            String name, UUID familyId, UUID brandId, long quantity, String amount) {
        CashClosureItem item = base(name, quantity, amount);
        item.setFamilyId(familyId);
        item.setFamilyName("HOJA DE RESORTE");
        item.setBrandId(brandId);
        item.setBrandName("Toyota");
        return item;
    }

    private static CashClosureItem job(String name, long quantity, String amount) {
        CashClosureItem item = base(name, quantity, amount);
        item.setItemType(FamilyCategory.JOB);
        item.setFamilyId(UUID.randomUUID());
        item.setFamilyName("SERVICIOS");
        return item;
    }

}
