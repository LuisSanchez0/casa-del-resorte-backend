package com.cns.spring_house.brand.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.cns.spring_house.shared.audit.Auditable;

import com.cns.spring_house.shared.constants.ColumnLimits;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
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
public class Brand extends Auditable {

    @Id
    @UuidGenerator
    private UUID id;
    @Column(length = ColumnLimits.BRAND_NAME)
    private String name;
    private String logoKey;

    /**
     * Cuando se elimino; null mientras la marca sigue vigente.
     *
     * Eliminacion LOGICA y de una sola via: sin setter, para que ningun mapper
     * ni formulario pueda devolverla a null. Solo {@link #markDeleted} la
     * escribe. La fila se conserva porque sus modelos, y a traves de ellos los
     * productos, ventas y cierres, la siguen referenciando.
     */
    @Setter(AccessLevel.NONE)
    private LocalDateTime deletedAt;

    public boolean isDeleted() {
        return deletedAt != null;
    }

    /** Repetirlo no cambia la fecha original: la marca se elimino una vez. */
    public void markDeleted(LocalDateTime when) {
        if (deletedAt == null) {
            deletedAt = when;
        }
    }

}
