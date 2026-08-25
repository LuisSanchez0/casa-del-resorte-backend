package com.cns.spring_house.cashclosure.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.cns.spring_house.employee.entity.Employee;
import com.cns.spring_house.shared.audit.Auditable;
import com.cns.spring_house.shared.constants.ColumnLimits;
import com.cns.spring_house.workshop.entity.Workshop;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * Cierre de caja diario de un taller: el SNAPSHOT de lo que se facturo, se
 * cobro y se conto en efectivo una fecha operativa concreta.
 *
 * Es historico e inmutable. Una vez persistido, ninguna lectura vuelve a
 * recalcularlo contra {@code Sale}: cobrar manana una cambiaria emitida hoy
 * cambia la cartera pendiente de hoy en adelante, pero no puede reescribir el
 * documento que alguien firmo al cerrar. Por eso se guardan tambien los totales
 * derivables ({@link #totalInvoiced}, {@link #totalCollected},
 * {@link #expectedCash}, {@link #difference}): el snapshot debe reproducir
 * exactamente las cifras confirmadas, no volver a deducirlas.
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

    // ── Arqueo declarado por la persona ──────────────────────────

    private BigDecimal openingCash;

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

    /** Saldo cambiario pendiente TOTAL del taller al cerrar, no solo el de hoy. */
    private BigDecimal pendingExchangeAmount;

    // ── Arqueo calculado por el servidor ─────────────────────────

    private BigDecimal expectedCash;
    private BigDecimal countedCash;
    /** Negativa cuando falta efectivo; positiva cuando sobra. */
    private BigDecimal difference;

    @Column(length = ColumnLimits.CASH_CLOSURE_NOTES)
    private String notes;

}
