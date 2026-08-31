package com.cns.spring_house.refund.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.cns.spring_house.employee.entity.Employee;
import com.cns.spring_house.sale.entity.PaymentMethod;
import com.cns.spring_house.sale.entity.Sale;
import com.cns.spring_house.shared.audit.Auditable;
import com.cns.spring_house.workshop.entity.Workshop;

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
 * Dinero que SALIO del taller al devolverse una venta.
 *
 * Es el hecho que faltaba en el dominio. Revertir una venta deshacia
 * existencias, lotes y trabajos, y dejaba constancia de quien y por que, pero
 * no registraba ninguna salida de dinero: si el taller devolvia Q1,000 en
 * efectivo, ningun informe lo sabia.
 *
 * Lo que hace util a esta entidad es `effectiveAt`, y conviene decir por que:
 * la devolucion pertenece al periodo en que EL DINERO SALIO, no al periodo de
 * la venta que la origino. Una venta de agosto devuelta en septiembre es
 * dinero que salio en septiembre; agosto ya esta cerrado y no se toca. Sin
 * fecha propia habria que elegir entre reescribir el pasado o perder el hecho,
 * y las dos opciones son peores.
 *
 * NO se deduce de `Sale.status`. Revertir y devolver son cosas distintas: se
 * puede revertir sin devolver —el cliente se lleva otra cosa, o se le abona
 * mas tarde— y por eso la devolucion se pregunta y se registra, o no existe.
 */
@Entity
@Table(name = "sale_refund")
@Getter
@Setter
@SuperBuilder
@EqualsAndHashCode(of = "id", callSuper = false)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class SaleRefund extends Auditable {

    @UuidGenerator
    @Id
    private UUID id;

    /**
     * Siempre el taller autenticado. Se guarda aunque sea derivable de la
     * venta: el cierre filtra por el, y un JOIN mas en la consulta caliente del
     * dia no se paga por ahorrar una columna.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workshop_id", nullable = false)
    private Workshop workshop;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sale_id", nullable = false)
    private Sale sale;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SaleRefundType type;

    /** Positivo siempre: la direccion la da el tipo, no el signo. */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    /** El instante real de la devolucion. Lo pone el reloj del servidor. */
    @Column(name = "effective_at", nullable = false)
    private LocalDateTime effectiveAt;

    /**
     * COMO volvio el dinero, que no tiene por que ser como entro: una venta
     * cobrada con tarjeta puede devolverse por transferencia si asi ocurrio.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false, length = 20)
    private PaymentMethod paymentMethod;

    @Column(nullable = false, length = 500)
    private String reason;

    /** Resuelto del token, nunca enviado por el cliente. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "performed_by", nullable = false)
    private Employee performedBy;

}
