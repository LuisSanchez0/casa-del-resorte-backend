package com.cns.spring_house.sale.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.cns.spring_house.customer.entity.Customer;
import com.cns.spring_house.employee.entity.Employee;
import com.cns.spring_house.shared.audit.Auditable;
import com.cns.spring_house.workshop.entity.Workshop;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@Entity
@Getter
@Setter
@SuperBuilder
@EqualsAndHashCode(of = "id", callSuper = false)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Sale extends Auditable {
    
    @UuidGenerator
    @Id
    private UUID id;
    @ManyToOne
    @JoinColumn(name = "workshop_id")
    private Workshop workshop;
    @ManyToOne
    @JoinColumn(name = "employee_id")
    private Employee employee;
    @ManyToOne
    @JoinColumn(name = "customer_id")
    private Customer customer;
    /**
     * Placa del vehiculo como snapshot opcional. No es una FK a VehicleMovement a
     * proposito: la referencia es informativa y no toda venta nace de un ingreso
     * de vehiculo, asi que exigir un movimiento existente bloquearia ventas de
     * mostrador legitimas.
     */
    private String plate;
    private LocalDateTime completedAt;
    @Builder.Default
    @Enumerated(EnumType.STRING)
    private SaleStatus status = SaleStatus.PENDING;
    /** Documento emitido; se conoce solo al finalizar la venta. */
    @Enumerated(EnumType.STRING)
    private InvoiceType invoiceType;
    /** Dimension de cobro, independiente de {@link #status}. */
    @Builder.Default
    @Enumerated(EnumType.STRING)
    private PaymentStatus paymentStatus = PaymentStatus.PENDING;
    /** Null mientras el cobro siga pendiente. */
    @Enumerated(EnumType.STRING)
    private PaymentMethod paymentMethod;
    private LocalDateTime paidAt;
    @Builder.Default
    @OneToMany(mappedBy = "sale", fetch = FetchType.EAGER, orphanRemoval = true, cascade = CascadeType.ALL)
    List<SaleDetail> details = new ArrayList<>();
    @Builder.Default
    private BigDecimal total = BigDecimal.ZERO;
    /**
     * Motivo, autor y fecha de por que esta venta terminó cancelada o
     * revertida. Null mientras la venta siga PENDING o COMPLETED sin
     * incidentes; el historial de una y otra vive en {@link SaleAction}, no
     * sobrescribiendo esta fila.
     */
    @OneToOne(mappedBy = "sale", fetch = FetchType.LAZY,
            cascade = CascadeType.ALL, orphanRemoval = true)
    private SaleAction action;

    public void addDetail(SaleDetail detail) {
        detail.setSale(this);
        this.details.add(detail);
        this.total = this.total.add(detail.getSubTotal());
    }

    public void removeDetail(SaleDetail detail) {
        this.details.remove(detail);
        detail.setSale(null);
        this.total = this.total.subtract(detail.getSubTotal());
    }

}
