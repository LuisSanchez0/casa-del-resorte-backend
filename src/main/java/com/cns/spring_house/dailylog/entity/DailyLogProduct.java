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
 * Linea de producto de un {@link DailyLog}: un SNAPSHOT TEXTUAL de lo que la
 * secretaria registra que se vendio.
 *
 * NO tiene relacion con {@code Product}, y no es un descuido: no hay
 * {@code @ManyToOne Product}, no hay {@code product_id} y no se guarda su UUID
 * por ninguna otra via. El nombre SI se valida contra el catalogo al crear el
 * registro —{@code DailyLogServiceImpl} rechaza con 400 un producto que no
 * exista— pero esa comprobacion muere en el POST. Lo que queda escrito es texto.
 *
 * La diferencia se ve al renombrar o retirar una pieza del catalogo: con una
 * relacion viva, el historial de meses atras cambiaria solo porque hoy alguien
 * edito el producto, o quedaria bloqueado el borrado de la pieza. Aqui el
 * registro sigue diciendo lo que decia el dia que se levanto, que es justo lo
 * que se le pide a un historico.
 *
 * Por el mismo motivo {@link #unitPrice} es el precio al que la secretaria
 * declara la venta y NO se contrasta con el precio actual del catalogo: el
 * catalogo cambia, la factura ya emitida no.
 */
@Entity
@Getter
@Setter
@Builder
@EqualsAndHashCode(of = "id")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class DailyLogProduct {

    @UuidGenerator
    @Id
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "daily_log_id")
    private DailyLog dailyLog;
    /** Nombre canonico tomado de {@code Product.name}, no el texto del cliente. */
    @Column(length = ColumnLimits.PRODUCT_NAME)
    private String name;
    private BigDecimal unitPrice;
    private Integer quantity;
    private BigDecimal subtotal;

}
