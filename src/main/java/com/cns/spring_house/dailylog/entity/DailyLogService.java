package com.cns.spring_house.dailylog.entity;

import java.math.BigDecimal;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.cns.spring_house.shared.constants.ColumnLimits;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Linea de servicio de un {@link DailyLog}, con exactamente el mismo criterio
 * que {@link DailyLogProduct}: SNAPSHOT TEXTUAL.
 *
 * NO tiene relacion con {@code Job}: no hay {@code @ManyToOne Job}, no hay
 * {@code job_id} y no se guarda su UUID. El nombre se valida contra
 * {@code JobRepository} al crear el registro y despues solo queda el texto.
 *
 * El nombre de un servicio no vive en {@code Job} —esa entidad solo tiene
 * producto, taller y precio— sino en {@code Job.product.name}, asi que el
 * nombre canonico que se persiste aqui sale de ahi.
 */
@Entity
@Getter
@Setter
@Builder
@EqualsAndHashCode(of = "id")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class DailyLogService {

    @UuidGenerator
    @Id
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "daily_log_id")
    private DailyLog dailyLog;
    /** Nombre canonico tomado de {@code Job.product.name}. */
    @Column(length = ColumnLimits.PRODUCT_NAME)
    private String name;
    private BigDecimal unitPrice;
    private Integer quantity;
    private BigDecimal subtotal;

}
