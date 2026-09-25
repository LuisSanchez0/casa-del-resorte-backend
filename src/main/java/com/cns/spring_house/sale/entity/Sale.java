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
import com.cns.spring_house.shared.constants.ColumnLimits;
import com.cns.spring_house.workshop.entity.Workshop;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
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
    /**
     * Solo en {@link TaxMode#NIT}; null en CF y en UNRESOLVED. Mientras la venta
     * esta abierta es la identidad viva; una vez cerrada, la identidad
     * historica es {@link #customerName} + {@link #customerNit}.
     */
    @ManyToOne
    @JoinColumn(name = "customer_id")
    private Customer customer;
    /**
     * Como se factura la venta. El valor por defecto es el significado que tuvo
     * siempre una venta abierta con cliente; el servicio lo fija EXPLICITAMENTE
     * en cada alta y nunca lo deduce de un NIT ausente.
     */
    @Builder.Default
    @Enumerated(EnumType.STRING)
    private TaxMode taxMode = TaxMode.NIT;
    /**
     * Nombre escrito en la propia venta, que no cambia aunque cambie el maestro
     * de clientes.
     *
     * UNRESOLVED: nombre de referencia de la venta sin NIT; opcional mientras es
     * un borrador y obligatorio al guardarla para despues. CF: nombre final del
     * documento, opcional ("Consumidor Final" si falta). NIT: null mientras esta
     * abierta; al emitirla o anularla, el nombre del cliente CONGELADO en ese
     * momento. La base lo impone con ck_sale_tax_identity.
     */
    @Column(length = ColumnLimits.CUSTOMER_NAME)
    private String customerName;
    /**
     * NIT congelado de una venta con NIT ya cerrada. Null en cualquier otro caso:
     * una venta a Consumidor Final no tiene NIT y jamas se guarda un "CF"
     * literal aqui.
     */
    @Column(length = ColumnLimits.CUSTOMER_NIT)
    private String customerNit;
    /**
     * El vendedor la guardo para despues a proposito. Distingue la venta
     * retomable del carrito en curso —o abandonado—, que tambien esta PENDING
     * porque sus lineas se persisten al agregarse.
     */
    @Builder.Default
    private boolean savedForLater = false;
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

    /**
     * Congela la identidad fiscal de una venta con NIT en cuanto deja de estar
     * abierta. Solo escribe si todavia no esta congelada, asi que una edicion
     * posterior del cliente nunca puede reescribir el historial.
     *
     * El servicio la llama al emitir y al anular, antes de mapear la respuesta.
     * Los ganchos de persistencia la repiten como red de seguridad para
     * cualquier otro camino que cierre una venta: la base rechaza una venta con
     * NIT cerrada sin su nombre y NIT congelados.
     */
    @PrePersist
    @PreUpdate
    public void freezeFiscalIdentity() {
        if (taxMode == TaxMode.NIT
                && status != SaleStatus.PENDING
                && customer != null
                && customerNit == null) {
            customerName = customer.getName();
            customerNit = customer.getNit();
        }
    }

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
