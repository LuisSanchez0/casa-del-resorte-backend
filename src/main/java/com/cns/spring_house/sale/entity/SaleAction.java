package com.cns.spring_house.sale.entity;

import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.cns.spring_house.employee.entity.Employee;
import com.cns.spring_house.shared.audit.Auditable;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * Registro auditable de por que una {@link Sale} termino sin llegar a su
 * destino normal: anulada estando abierta, o revertida despues de completarse.
 *
 * Es una entidad separada y no una columna mas en {@code sale} porque el
 * motivo, quien lo hizo y cuando son un hecho distinto de la venta misma; y es
 * uno solo por venta (relacion 1 a 1) porque, en este modelo, una Sale solo
 * puede vivir uno de los dos eventos terminales una vez.
 *
 * {@link Auditable#getCreatedAt()} ES la fecha de la accion: no existe un
 * timestamp aparte porque el registro se crea exactamente cuando ocurre.
 */
@Entity
@Getter
@Setter
@SuperBuilder
@EqualsAndHashCode(of = "id", callSuper = false)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class SaleAction extends Auditable {

    @UuidGenerator
    @Id
    private UUID id;
    @OneToOne
    @JoinColumn(name = "sale_id", unique = true)
    private Sale sale;
    @Enumerated(EnumType.STRING)
    private SaleActionType actionType;
    /** Ya validado como no vacio y recortado antes de llegar aqui. */
    private String reason;
    @ManyToOne
    @JoinColumn(name = "performed_by")
    private Employee performedBy;

}
