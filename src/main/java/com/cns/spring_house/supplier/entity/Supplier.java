package com.cns.spring_house.supplier.entity;

import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.cns.spring_house.shared.audit.Auditable;
import com.cns.spring_house.shared.constants.ColumnLimits;
import com.cns.spring_house.workshop.entity.Workshop;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * De quien compra el taller.
 *
 * Es del TALLER y no global, igual que {@code Inventory}, {@code Job} y
 * {@code Sale}: dos talleres pueden trabajar con proveedores distintos que se
 * llamen igual, y el autocompletado de uno no debe ofrecer los del otro.
 *
 * Solo identidad. No hay NIT, telefono, direccion ni datos bancarios porque
 * ningun flujo del sistema factura ni paga a un proveedor: lo unico que se
 * necesita saber es de quien vino un lote. Campos que nadie escribe solo
 * producen formularios con huecos.
 *
 * El nombre se guarda TAL Y COMO LO ESCRIBIO la persona —es lo que se muestra—,
 * pero la unicidad se decide sobre su forma canonica
 * ({@code UPPER(REGEXP_REPLACE(BTRIM(name), '\s+', ' '))}), que es la misma
 * expresion del indice {@code uq_supplier_canonical_name_per_workshop}. Ver
 * {@code SupplierServiceImpl}.
 */
@Entity
@Getter
@Setter
@SuperBuilder
@EqualsAndHashCode(of = "id", callSuper = false)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Supplier extends Auditable {

    @UuidGenerator
    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workshop_id")
    private Workshop workshop;

    @Column(length = ColumnLimits.SUPPLIER_NAME)
    private String name;

}
