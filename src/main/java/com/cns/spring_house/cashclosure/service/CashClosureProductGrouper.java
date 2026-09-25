package com.cns.spring_house.cashclosure.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.cns.spring_house.cashclosure.dto.CashClosureProductGroupDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureProductSummaryDTO;
import com.cns.spring_house.cashclosure.entity.CashClosureItem;
import com.cns.spring_house.family.entity.FamilyCategory;

/**
 * Agrupa los articulos de un cierre por GRUPO COMERCIAL: familia, mas marca y
 * modelo cuando aplican.
 *
 * Es el UNICO sitio donde se decide que es "el mismo grupo". Existe una sola
 * vez y en el backend a proposito: una segunda implementacion en el navegador
 * significaria dos algoritmos que pueden separarse, y entonces el PDF y la
 * pantalla dirian cosas distintas del mismo dia.
 *
 * <h2>La clave</h2>
 *
 * <pre>
 * (familyId, brandId, modelId)     los tres pueden repetirse; brandId y modelId
 *                                  pueden ser nulos, y el nulo AGRUPA
 * </pre>
 *
 * Son IDENTIDADES, no textos. Dos familias homonimas del catalogo real son dos
 * grupos; fundirlas por nombre presentaria como una cosa lo que son dos. Los
 * nombres solo se imprimen.
 *
 * <h2>Lo que NO parte un grupo</h2>
 *
 * Ninguna medida ni atributo del producto: largo, largoIzquierdo,
 * largoDerecho, tieneOrificio, cantidadColochos, diametro. Tampoco
 * {@code product.id}, el sku ni el nombre.
 *
 * Tres hojas de resorte Toyota Hilux de 80, 90 y 100 son TRES productos
 * distintos en el catalogo y UNA sola fila aqui. Es justo la pregunta que el
 * cierre de caja no sabia responder: el desglose por producto ya existia y lo
 * que faltaba era el grupo.
 *
 * Tampoco parte la categoria de la familia. NORMAL, SPECIAL y CONTAINER se
 * agrupan por sus dimensiones y no por su tipo: una maleta y una hoja ya son
 * familias distintas, asi que la categoria no aporta ninguna separacion que la
 * familia no haya hecho antes.
 *
 * <h2>Lo que este agrupador NO responde</h2>
 *
 * COMO entro el dinero. Esa pregunta ya la responde el cierre de caja con su
 * desglose por forma de pago, que sale de `cash_closure_payment_method`, y el
 * PDF del resumen la imprime tal cual: es la MISMA seccion del MISMO snapshot.
 *
 * Atribuir aqui una forma de cobro a cada grupo seria una segunda lectura del
 * mismo dinero, y dos lecturas acaban contradiciendose: paso de verdad, con un
 * cierre cobrado integro en efectivo cuyo resumen declaraba "sin forma de cobro
 * registrada" sobre el mismo importe.
 *
 * <h2>Lo que se deja fuera</h2>
 *
 * Los servicios ({@code JOB}). No son mercaderia, no tienen marca ni modelo, y
 * agruparlos obligaria a inventarles dimensiones. Su dinero NO se pierde:
 * sigue contado en el cierre de caja de siempre, y este resumen publica el
 * importe excluido para que la diferencia sea explicable a simple vista.
 *
 * <h2>Sobre el origen de las filas</h2>
 *
 * Se agrupa sobre el SNAPSHOT ya cargado del cierre, no contra el catalogo
 * vivo. Las dimensiones se copiaron al confirmar, asi que renombrar una marca o
 * mover un producto de familia manana no puede reescribir el reporte de hoy. Y
 * no hay consulta por fila: las filas llegan enteras en la coleccion del cierre.
 *
 * <h2>Dos calidades de historia, y hay que distinguirlas</h2>
 *
 * <pre>
 * cierre confirmado DESPUES de V37
 *   -> dimensiones EXACTAS, congeladas en el instante de confirmar
 *
 * cierre confirmado ANTES de V37
 *   -> dimensiones reconstruidas por la migracion, de MEJOR ESFUERZO
 * </pre>
 *
 * La segunda no es historia exacta y no se presenta como tal. Cuando esos
 * cierres se confirmaron, las columnas de familia, marca y modelo no existian,
 * asi que V37 solo pudo copiar lo que el catalogo decia EN EL MOMENTO DE
 * MIGRAR. Si un producto ya se habia renombrado o movido de familia antes de la
 * migracion, la etiqueta que se guardo es la de entonces y no la que tenia el
 * dia de la venta: ese dato no quedo registrado en ninguna parte y no hay de
 * donde deducirlo.
 *
 * Y si el producto ya no existe en el catalogo, la fila llega SIN familia. No se
 * le inventa una: se agrupa aparte, se cuenta en
 * {@code CashClosureProductSummaryDTO.unclassifiedAmount} y el papel la nombra
 * como sin clasificar. Su importe sigue dentro del total, porque es dinero que
 * se facturo.
 */
@Component
public class CashClosureProductGrouper {

    /**
     * Ordena por texto como lo leeria una persona: sin distinguir mayusculas y
     * con las tildes en su sitio, no detras de la Z como haria una comparacion
     * por codigo de caracter.
     *
     * El locale se fija en vez de tomar el de la maquina: el mismo cierre
     * descargado desde dos despliegues tiene que ordenar igual.
     */
    private static final Locale SORT_LOCALE = Locale.forLanguageTag("es");

    /**
     * El resumen agrupado de un cierre.
     *
     * @param items las filas del snapshot, ya cargadas. Nunca se releen ni se
     *              resuelven contra el catalogo.
     */
    public CashClosureProductSummaryDTO summarize(Collection<CashClosureItem> items) {
        Map<GroupKey, Accumulator> groups = new LinkedHashMap<>();
        BigDecimal excludedJobAmount = BigDecimal.ZERO;

        for (CashClosureItem item : items) {
            if (item.getItemType() == FamilyCategory.JOB) {
                excludedJobAmount = excludedJobAmount.add(amountOf(item));
                continue;
            }
            groups.computeIfAbsent(GroupKey.of(item), key -> new Accumulator(item))
                    .add(item);
        }

        List<CashClosureProductGroupDTO> rows = new ArrayList<>();
        long totalQuantity = 0L;
        BigDecimal totalAmount = BigDecimal.ZERO;
        BigDecimal unclassifiedAmount = BigDecimal.ZERO;
        for (Accumulator accumulator : groups.values()) {
            CashClosureProductGroupDTO row = accumulator.toRow();
            rows.add(row);
            totalQuantity += row.quantity();
            // El total se suma de las FILAS ya construidas y no por una via
            // paralela: si la agrupacion perdiera una, el total la perderia
            // tambien en vez de taparlo con un numero que cuadra solo.
            totalAmount = totalAmount.add(row.amount());
            if (row.familyId() == null) {
                // Se CUENTA aparte pero NO se saca del total: el dinero de una
                // fila sin etiqueta sigue siendo dinero facturado.
                unclassifiedAmount = unclassifiedAmount.add(row.amount());
            }
        }

        rows.sort(displayOrder());

        return new CashClosureProductSummaryDTO(
                List.copyOf(rows),
                totalQuantity,
                money(totalAmount),
                money(excludedJobAmount),
                money(unclassifiedAmount));
    }

    /**
     * Familia, marca y modelo ascendentes, que es como se busca una fila en un
     * papel impreso.
     *
     * Los nulos van al final, en las tres dimensiones y siempre igual: una fila
     * sin marca es la mas generica de su familia y cierra su bloque en vez de
     * abrirlo. La desempata el id para que el orden sea TOTAL: dos grupos con
     * los mismos nombres —el caso homonimo— alternarian entre descargas del
     * mismo documento, y un historico no puede salir distinto cada vez.
     */
    private static Comparator<CashClosureProductGroupDTO> displayOrder() {
        Collator collator = Collator.getInstance(SORT_LOCALE);
        collator.setStrength(Collator.SECONDARY);
        Comparator<String> byName = Comparator.nullsLast(collator::compare);
        Comparator<UUID> byId = Comparator.nullsLast(Comparator.naturalOrder());

        return Comparator
                .comparing(CashClosureProductGroupDTO::familyName, byName)
                .thenComparing(CashClosureProductGroupDTO::brandName, byName)
                .thenComparing(CashClosureProductGroupDTO::modelName, byName)
                .thenComparing(CashClosureProductGroupDTO::familyId, byId)
                .thenComparing(CashClosureProductGroupDTO::brandId, byId)
                .thenComparing(CashClosureProductGroupDTO::modelId, byId);
    }

    /**
     * La identidad del grupo. Solo ids: el nombre acompaña a la fila pero no
     * decide nada, porque renombrar no convierte una marca en otra.
     *
     * {@code record} y no una clase escrita a mano por su {@code equals}: los
     * tres campos, con nulos incluidos, y ni uno mas.
     */
    private record GroupKey(UUID familyId, UUID brandId, UUID modelId) {

        static GroupKey of(CashClosureItem item) {
            return new GroupKey(
                    item.getFamilyId(), item.getBrandId(), item.getModelId());
        }
    }

    /**
     * Suma las filas de un grupo.
     *
     * Los nombres se toman de la PRIMERA fila del grupo y ya no se tocan.
     * Cualquiera de ellas sirve: comparten los tres ids, asi que describen la
     * misma familia, la misma marca y el mismo modelo, y si dos filas del mismo
     * cierre trajeran textos distintos seria porque el catalogo cambio entre
     * dos dias del mes —y entonces las dos son ciertas—.
     */
    private static final class Accumulator {

        private final UUID familyId;
        private final String familyName;
        private final UUID brandId;
        private final String brandName;
        private final UUID modelId;
        private final String modelName;

        private long quantity;
        private BigDecimal amount = BigDecimal.ZERO;

        private Accumulator(CashClosureItem first) {
            this.familyId = first.getFamilyId();
            this.familyName = first.getFamilyName();
            this.brandId = first.getBrandId();
            this.brandName = first.getBrandName();
            this.modelId = first.getModelId();
            this.modelName = first.getModelName();
        }

        private void add(CashClosureItem item) {
            quantity += item.getQuantity();
            amount = amount.add(amountOf(item));
        }

        private CashClosureProductGroupDTO toRow() {
            return new CashClosureProductGroupDTO(
                    familyId, familyName,
                    brandId, brandName,
                    modelId, modelName,
                    quantity, money(amount));
        }
    }

    /**
     * El importe HISTORICO de la fila. Un nulo es cero y no una excepcion: una
     * fila sin importe no puede tumbar la descarga de un cierre entero.
     */
    private static BigDecimal amountOf(CashClosureItem item) {
        return Objects.requireNonNullElse(item.getBilledAmount(), BigDecimal.ZERO);
    }

    /** Dos decimales, como la columna NUMERIC y como el resto del cierre. */
    private static BigDecimal money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

}
