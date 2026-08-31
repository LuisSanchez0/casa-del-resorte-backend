package com.cns.spring_house.cashclosure.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.UuidGenerator;

import com.cns.spring_house.employee.entity.Employee;
import com.cns.spring_house.shared.audit.Auditable;
import com.cns.spring_house.workshop.entity.Workshop;

import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import lombok.Builder;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * Cierre de caja diario de un taller: el SNAPSHOT de lo que se facturo y se
 * cobro en una fecha operativa concreta.
 *
 * Es historico e inmutable. Una vez persistido, ninguna lectura vuelve a
 * recalcularlo contra {@code Sale}: cobrar manana una cambiaria emitida hoy
 * cambia la cartera pendiente de hoy en adelante, pero no puede reescribir el
 * documento que alguien firmo al cerrar. Por eso se guardan tambien los totales
 * derivables ({@link #totalInvoiced}, {@link #totalCollected}): el snapshot debe
 * reproducir exactamente las cifras confirmadas, no volver a deducirlas.
 *
 * Los ESCALARES de esta fila sirven para AGREGAR muchos cierres —el cierre
 * mensual los suma con una sola consulta—; las tres colecciones hijas sirven
 * para RECONSTRUIR un dia concreto: que se vendio, que documentos cambiarios
 * quedaron pendientes y como entro el dinero.
 *
 * No hay {@code closedAt}: {@code Auditable.createdAt} ES el instante del
 * cierre, porque la fila se inserta en ese momento y nunca se modifica. El DTO
 * expone la semantica {@code closedAt} leyendo ese campo.
 */
@Entity
@Getter
@Setter
@SuperBuilder
@EqualsAndHashCode(of = "id", callSuper = false)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class CashClosure extends Auditable {

    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "workshop_id")
    private Workshop workshop;

    /** Empleado que confirmo el cierre, resuelto desde la cuenta autenticada. */
    @ManyToOne
    @JoinColumn(name = "closed_by")
    private Employee closedBy;

    /** Fecha operativa cerrada, no la fecha en que se pulso el boton. */
    private LocalDate businessDate;

    // ── Facturacion del dia (por completedAt, fecha de EMISION) ───

    private long invoiceCount;
    private BigDecimal invoiceTotal;
    private long exchangeInvoiceCount;
    private BigDecimal exchangeInvoiceTotal;
    private BigDecimal totalInvoiced;

    // ── Cobros del dia (por paidAt, fecha del COBRO) ──────────────

    private long cashPaymentCount;
    private BigDecimal cashPayments;
    private long cardPaymentCount;
    private BigDecimal cardPayments;
    private long transferPaymentCount;
    private BigDecimal transferPayments;
    private BigDecimal totalCollected;

    // ── Cartera cambiaria ────────────────────────────────────────
    //
    // Lo GENERADO hoy como credito ya es `exchangeInvoiceTotal`; no se repite
    // aqui con otro nombre.

    private long exchangeCollectedCount;
    private BigDecimal exchangeCollectedAmount;

    /**
     * De lo cobrado hoy en cambiarias, la parte que se emitio en dias
     * anteriores. No es derivable de los demas campos del snapshot, y el PDF
     * historico la muestra igual que la previsualizacion.
     */
    private BigDecimal previousExchangeCollectedAmount;

    /**
     * De las cambiarias EMITIDAS en el periodo, lo que seguia sin cobrar al
     * cerrar.
     *
     * Es period-scoped a proposito. Antes se guardaba el saldo pendiente TOTAL
     * del taller, que mezclaba deuda de cualquier fecha con las cifras del dia e
     * invitaba a leerla como generada ese dia. Lo que el cierre responde ahora
     * es "de lo que factura hoy a credito, cuanto sigue debiendose".
     */
    private BigDecimal issuedExchangePendingAmount;

    /**
     * Suma de lo facturado en articulos. Permite comprobar que el desglose
     * cuadra sin recorrer las ventas.
     */
    private BigDecimal itemsBilledAmount;

    // ── Dinero que SALIO ─────────────────────────────────────────
    //
    // Se guardan como escalares por la misma razon que los cobros: el cierre
    // MENSUAL los suma con UNA consulta sobre `cash_closure`. El detalle
    // legible de cada devolucion vive en la coleccion hija.
    //
    // `totalRefunded` NO se resta de `totalCollected`. Son dos hechos
    // distintos —cuanto entro y cuanto se devolvio— y fundirlos daria un solo
    // numero que no responde ninguno de los dos. El neto se deriva al leer.

    // Los importes arrancan en CERO y no en null. Un cierre sin devoluciones
    // devolvio Q0.00, que es una cifra correcta y no un dato que falte; sin
    // este valor por defecto, cualquier cierre construido sin pasar por el
    // mapper insertaria NULL en columnas NOT NULL.
    private long refundCount;
    @Builder.Default
    private BigDecimal totalRefunded = BigDecimal.ZERO;
    private long refundCashCount;
    @Builder.Default
    private BigDecimal refundCash = BigDecimal.ZERO;
    private long refundCardCount;
    @Builder.Default
    private BigDecimal refundCard = BigDecimal.ZERO;
    private long refundTransferCount;
    @Builder.Default
    private BigDecimal refundTransfer = BigDecimal.ZERO;

    // ── Detalle del periodo ──────────────────────────────────────
    //
    // Se persisten en cascada con el cierre y se borran con el. Son la parte
    // que hace RECONSTRUIBLE un dia; sin ellas habria que volver a consultar
    // `sale_detail`, que es justo lo que un snapshot no puede hacer.

    /*
     * Las tres se acotan por LOTE y no se unen en la consulta del cierre.
     *
     * Unir tres colecciones a la vez es imposible —Hibernate lo rechaza con
     * MultipleBagFetchException— y aunque no lo fuera multiplicaria las filas:
     * un cierre con veinte articulos, cinco documentos y tres formas de pago
     * devolveria trescientas. Con el lote, cada coleccion se resuelve en UNA
     * consulta adicional por `cash_closure_id IN (...)`, de modo que leer un
     * cierre cuesta cuatro consultas fijas y leer treinta —el mes— sigue
     * costando cuatro, no noventa.
     *
     * Mismo criterio que `InventoryItem.containedItems`.
     */
    @Builder.Default
    @BatchSize(size = 64)
    @OneToMany(mappedBy = "cashClosure", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<CashClosureItem> items = new ArrayList<>();

    @Builder.Default
    @BatchSize(size = 64)
    @OneToMany(mappedBy = "cashClosure", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<CashClosureExchangeInvoice> exchangeInvoices = new ArrayList<>();

    @Builder.Default
    @BatchSize(size = 64)
    @OneToMany(mappedBy = "cashClosure", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<CashClosurePaymentMethodTotal> paymentMethods = new ArrayList<>();

    @Builder.Default
    @BatchSize(size = 64)
    @OneToMany(mappedBy = "cashClosure", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<CashClosureRefund> refunds = new ArrayList<>();

    /** Enlaza los dos lados: sin esto la FK del hijo viajaria nula. */
    public void addItem(CashClosureItem item) {
        this.items.add(item);
        item.setCashClosure(this);
    }

    public void addExchangeInvoice(CashClosureExchangeInvoice invoice) {
        this.exchangeInvoices.add(invoice);
        invoice.setCashClosure(this);
    }

    public void addPaymentMethod(CashClosurePaymentMethodTotal total) {
        this.paymentMethods.add(total);
        total.setCashClosure(this);
    }

    public void addRefund(CashClosureRefund refund) {
        this.refunds.add(refund);
        refund.setCashClosure(this);
    }

}
