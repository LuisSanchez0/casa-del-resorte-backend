package com.cns.spring_house.dailylog.entity;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.cns.spring_house.employee.entity.Employee;
import com.cns.spring_house.sale.entity.Sale;
import com.cns.spring_house.shared.audit.Auditable;
import com.cns.spring_house.workshop.entity.Workshop;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
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

/**
 * Registro historico que la secretaria levanta tomando como referencia una
 * factura ya emitida.
 *
 * Tiene UNA sola relacion de negocio: la {@link Sale} que le sirve de
 * referencia. Todo lo demas que describe —productos y servicios— es texto, no
 * catalogo: ver {@link DailyLogProduct} y {@link DailyLogService}.
 *
 * Las lineas NO se contrastan contra {@code Sale.details}. La venta identifica
 * el documento; el detalle lo escribe la secretaria a mano, ayudada por el
 * autocompletado, y puede no coincidir linea por linea con lo que registro el
 * vendedor.
 */
@Entity
@Getter
@Setter
@SuperBuilder
@EqualsAndHashCode(of = "id", callSuper = false)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class DailyLog extends Auditable {

    @UuidGenerator
    @Id
    private UUID id;
    @Builder.Default
    private LocalDate performedDate = LocalDate.now();
    /**
     * La factura de referencia, uno a uno: {@code sale_id} es UNIQUE en el
     * esquema, asi que una venta no puede acabar con dos registros. Es nullable
     * unicamente por las filas anteriores a V26, que nacieron sin venta; el
     * servicio la exige siempre para un registro nuevo.
     */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sale_id", unique = true)
    private Sale sale;
    @ManyToOne
    @JoinColumn(name = "workshop_id")
    private Workshop workshop;
    @ManyToOne
    @JoinColumn(name = "employee_id")
    private Employee employee;
    /** Nota general del registro; opcional desde V26. */
    private String description;
    @Builder.Default
    @OneToMany(mappedBy = "dailyLog", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<DailyLogProduct> products = new ArrayList<>();
    @Builder.Default
    @OneToMany(mappedBy = "dailyLog", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<DailyLogService> services = new ArrayList<>();

    public void addProduct(DailyLogProduct product) {
        product.setDailyLog(this);
        this.products.add(product);
    }

    public void addService(DailyLogService service) {
        service.setDailyLog(this);
        this.services.add(service);
    }

}
