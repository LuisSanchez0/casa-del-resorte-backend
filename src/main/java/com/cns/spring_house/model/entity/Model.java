package com.cns.spring_house.model.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.cns.spring_house.shared.audit.Auditable;
import com.cns.spring_house.brand.entity.Brand;

import com.cns.spring_house.shared.constants.ColumnLimits;

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

@Entity
@Getter
@Setter
@SuperBuilder
@EqualsAndHashCode(of = "id", callSuper = false)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Model extends Auditable {

    @Id
    @UuidGenerator
    private UUID id;
    @ManyToOne
    @JoinColumn(name = "brand_id")
    private Brand brand;
    @Column(length = ColumnLimits.MODEL_NAME)
    private String name;
    /**
     * Intervalo cerrado de años a los que aplica el modelo. Un modelo de un
     * solo año no es un caso aparte: es el intervalo con los dos extremos
     * iguales, y la cadena "2020-2025" solo existe en la presentación.
     */
    private Integer yearFrom;
    private Integer yearTo;

    /**
     * Cuando se elimino; null mientras el modelo sigue vigente. Misma regla que
     * {@link Brand#getDeletedAt}: logica, de una sola via y sin setter.
     */
    @Setter(AccessLevel.NONE)
    private LocalDateTime deletedAt;

    public boolean isDeleted() {
        return deletedAt != null;
    }

    /**
     * Se puede elegir para algo NUEVO: el modelo y su marca estan vigentes.
     * Eliminar la marca no marca a sus modelos —cada uno conserva su propio
     * estado—, pero dejan de ser elegibles por ella.
     */
    public boolean isSelectable() {
        return !isDeleted() && brand != null && !brand.isDeleted();
    }

    /** Repetirlo no cambia la fecha original. */
    public void markDeleted(LocalDateTime when) {
        if (deletedAt == null) {
            deletedAt = when;
        }
    }

}
