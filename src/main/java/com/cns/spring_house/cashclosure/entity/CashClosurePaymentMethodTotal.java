package com.cns.spring_house.cashclosure.entity;

import java.math.BigDecimal;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.cns.spring_house.sale.entity.PaymentMethod;
import com.cns.spring_house.shared.audit.Auditable;

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
 * Lo cobrado en el periodo por UNA forma de pago.
 *
 * Una fila por metodo en vez de tres columnas fijas: el dia que el taller acepte
 * otra via de cobro, el cierre la recoge sin migrar el esquema ni tocar el
 * reporte. La suma de estas filas ES el total cobrado del periodo, y esa
 * igualdad es la que las pruebas comprueban.
 */
@Entity
/*
 * El nombre de la clase lleva `Total` para no chocar con el enum
 * `PaymentMethod` al leer el codigo, pero la TABLA se llama
 * `cash_closure_payment_method`, que es como la creo la migracion. Sin este
 * mapeo explicito, Hibernate derivaria `cash_closure_payment_method_total` y el
 * INSERT fallaria contra una tabla que no existe.
 */
@Table(name = "cash_closure_payment_method")
@Getter
@Setter
@SuperBuilder
@EqualsAndHashCode(of = "id", callSuper = false)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class CashClosurePaymentMethodTotal extends Auditable {

    @UuidGenerator
    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cash_closure_id")
    private CashClosure cashClosure;

    @Enumerated(EnumType.STRING)
    private PaymentMethod paymentMethod;

    private long paymentCount;

    private BigDecimal amount;

}
