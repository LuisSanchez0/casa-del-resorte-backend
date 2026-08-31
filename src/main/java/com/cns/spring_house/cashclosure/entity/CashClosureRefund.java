package com.cns.spring_house.cashclosure.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.cns.spring_house.sale.entity.PaymentMethod;
import com.cns.spring_house.shared.audit.Auditable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * Una devolucion COPIADA dentro de un cierre confirmado.
 *
 * No es una referencia a `sale_refund` sino su fotografia. `refundId` queda
 * para poder rastrear el evento vivo, pero el cierre no lo relee: el importe,
 * la forma, el motivo y el NOMBRE de quien devolvio son los que habia al
 * cerrar. Renombrar despues a un empleado no puede cambiar lo que el documento
 * de aquel dia dijo, que es la misma regla que ya cumplen los articulos y las
 * cambiarias del snapshot.
 */
@Entity
@Table(name = "cash_closure_refund")
@Getter
@Setter
@SuperBuilder
@EqualsAndHashCode(of = "id", callSuper = false)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class CashClosureRefund extends Auditable {

    @UuidGenerator
    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cash_closure_id")
    private CashClosure cashClosure;

    @Column(name = "refund_id", nullable = false)
    private UUID refundId;

    @Column(name = "sale_id", nullable = false)
    private UUID saleId;

    @Column(name = "document_code", nullable = false, length = 12)
    private String documentCode;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(name = "effective_at", nullable = false)
    private LocalDateTime effectiveAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false, length = 20)
    private PaymentMethod paymentMethod;

    @Column(name = "performed_by_name", nullable = false, length = 120)
    private String performedByName;

    @Column(nullable = false, length = 500)
    private String reason;

}
