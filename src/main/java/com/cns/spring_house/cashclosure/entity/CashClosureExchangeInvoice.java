package com.cns.spring_house.cashclosure.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.cns.spring_house.sale.entity.PaymentMethod;
import com.cns.spring_house.shared.audit.Auditable;
import com.cns.spring_house.shared.constants.ColumnLimits;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
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
 * Una factura cambiaria dentro de un cierre, con su ESTADO AL CERRAR.
 *
 * Esta es la razon de ser de la tabla. Si el historico leyera el estado actual
 * de `sale`, cobrar el miercoles una cambiaria del lunes reescribiria el cierre
 * del lunes, que la registro —correctamente— como pendiente. El estado se copia
 * y no se vuelve a mirar.
 *
 * {@code saleId} se guarda sin FK a `sale` por la misma razon que en
 * {@link CashClosureItem}: es historia copiada, no una relacion viva. Sirve para
 * abrir el documento desde la pantalla, no para releerlo.
 *
 * {@code documentCode} es el codigo corto que la persona lee en el papel; el
 * UUID crudo no se ensena.
 */
@Entity
@Getter
@Setter
@SuperBuilder
@EqualsAndHashCode(of = "id", callSuper = false)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class CashClosureExchangeInvoice extends Auditable {

    @UuidGenerator
    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cash_closure_id")
    private CashClosure cashClosure;

    /** Emitida en el periodo o cobrada en el periodo. Ver {@link ExchangeInvoiceScope}. */
    @Enumerated(EnumType.STRING)
    private ExchangeInvoiceScope scope;

    private UUID saleId;

    @Column(length = 12)
    private String documentCode;

    /** Puede ser null: una venta de mostrador no lleva cliente asociado. */
    @Column(length = ColumnLimits.CUSTOMER_NAME)
    private String customerName;

    private LocalDateTime issuedAt;

    private BigDecimal amount;

    /** Estado AL CERRAR, no el de hoy. */
    private boolean collected;

    /** Null mientras siguiera pendiente en el momento del cierre. */
    private LocalDateTime paidAt;

    /** Null mientras siguiera pendiente en el momento del cierre. */
    @Enumerated(EnumType.STRING)
    private PaymentMethod paymentMethod;

}
