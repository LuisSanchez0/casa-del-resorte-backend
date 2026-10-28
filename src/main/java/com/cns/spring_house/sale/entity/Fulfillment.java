package com.cns.spring_house.sale.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.cns.spring_house.employee.entity.Employee;
import com.cns.spring_house.job.entity.JobLog;
import com.cns.spring_house.transformation.entity.Transformation;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Como se cumple una linea que el vendedor PIDIO al taller.
 *
 * Vive dentro de {@link SaleDetail} porque la solicitud y su cumplimiento son la
 * misma cosa vista por dos personas: el vendedor la ve como lo que el cliente
 * compra, Control o el Operador como lo que hay que entregar o fabricar. Una
 * linea sin cumplimiento (null) es una linea directa, como siempre.
 *
 * La unidad fisica entregada NO se guarda aqui: es {@link SaleDetail#getItem()},
 * el mismo campo que ya lee la factura, la reversion y el historial. Control
 * reserva una unidad al enviar la comanda y puede cambiarla por otra al
 * entregarla; el Operador la llena con la pieza que fabrica.
 */
@Embeddable
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Fulfillment {

    /** Null mientras una pieza especial no se ha enviado: la ruta se decide al enviar. */
    @Enumerated(EnumType.STRING)
    @Column(name = "fulfillment_type")
    private FulfillmentType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "fulfillment_status")
    private FulfillmentStatus status;

    /** La transformacion que fabrico la pieza, si la ruta fue TRANSFORMATION. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "fulfillment_transformation_id")
    private Transformation transformation;

    /** El servicio ejecutado, si la ruta fue SERVICE. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "fulfillment_job_log_id")
    private JobLog jobLog;

    /** Quien la tomo o la entrego. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "fulfillment_employee_id")
    private Employee employee;

    /** Texto del bloqueo que ve el vendedor (p. ej. "Sin materia prima"). */
    @Column(name = "fulfillment_note")
    private String note;

    /** Por que esta bloqueada; null si no lo esta. */
    @Enumerated(EnumType.STRING)
    @Column(name = "fulfillment_block_reason")
    private FulfillmentBlockReason blockReason;

    /**
     * En revision de precio, el minimo que el precio de la linea debe cubrir
     * para poder fabricarla: el costo real de la pieza de origen elegida.
     */
    @Column(name = "fulfillment_required_price")
    private BigDecimal requiredPrice;

    @Column(name = "fulfillment_dispatched_at")
    private LocalDateTime dispatchedAt;

    @Column(name = "fulfillment_started_at")
    private LocalDateTime startedAt;

    @Column(name = "fulfillment_completed_at")
    private LocalDateTime completedAt;

    /** Una solicitud recien agregada: sin enviar y, si es pieza, sin ruta. */
    public static Fulfillment pending(FulfillmentType type) {
        return Fulfillment.builder()
                .type(type)
                .status(FulfillmentStatus.PENDING)
                .build();
    }

    public FulfillmentArea area() {
        return type == null ? null : type.area();
    }

    public boolean isPending() {
        return status == FulfillmentStatus.PENDING;
    }

    public boolean isFulfilled() {
        return status == FulfillmentStatus.FULFILLED;
    }

    public boolean awaitsPriceReview() {
        return status == FulfillmentStatus.BLOCKED
                && blockReason == FulfillmentBlockReason.PRICE_REVIEW;
    }

    /** Bloquea con su motivo; un bloqueo nunca queda sin motivo. */
    public void block(FulfillmentBlockReason reason, String note, BigDecimal requiredPrice) {
        this.status = FulfillmentStatus.BLOCKED;
        this.blockReason = reason;
        this.note = note;
        this.requiredPrice = reason == FulfillmentBlockReason.PRICE_REVIEW ? requiredPrice : null;
    }

    /** Sale de cualquier bloqueo: el motivo deja de aplicar. */
    public void clearBlock() {
        this.blockReason = null;
        this.requiredPrice = null;
        this.note = null;
    }

}
