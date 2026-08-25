package com.cns.spring_house.vehiclemovement.entity;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.cns.spring_house.employee.entity.Employee;
import com.cns.spring_house.shared.audit.Auditable;
import com.cns.spring_house.workshop.entity.Workshop;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

@Entity
@Getter
@Setter
@SuperBuilder
@EqualsAndHashCode(of = "id", callSuper = false)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Data
public class VehicleMovement extends Auditable {

    @Id
    @UuidGenerator
    private UUID id;
    @ManyToOne
    @JoinColumn(name = "workshop_id")
    private Workshop workshop;
    /**
     * Empleado que registro la ENTRADA, resuelto desde la identidad autenticada
     * y nunca desde la peticion. Es la unica base del ambito "Mis estadisticas".
     * <p>
     * Es nullable porque los movimientos anteriores a V23 no tienen actor
     * demostrable: se dejan en NULL en lugar de atribuirlos a alguien.
     */
    @ToString.Exclude
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id")
    private Employee employee;
    private String plate;
    /**
     * Telefono de la persona asociada a ESTA entrada, capturado en el momento de
     * abrir la bitacora. Ocho digitos, sin prefijo internacional (V31 lo declara
     * VARCHAR(8) con un CHECK del mismo patron que el DTO de alta).
     * <p>
     * Es una FOTO HISTORICA, no una relacion con el vehiculo ni con un
     * propietario: cada visita conserva el suyo y una visita posterior con otro
     * telefono no reescribe esta. Una placa puede cambiar de dueno, asi que un
     * "telefono vigente de la placa" del que dependiera el historial seria
     * directamente falso.
     * <p>
     * Nullable por los movimientos anteriores a V31, que no tienen telefono
     * demostrable: se dejan en NULL en lugar de copiarles el de un registro mas
     * nuevo. El contrato de la aplicacion lo exige solo para las bitacoras
     * NUEVAS ({@code CreateVehicleMovementRequestDTO}).
     * <p>
     * Solo se escribe al ABRIR la bitacora. Ni la actualizacion de notas y fotos
     * ni el registro de salida lo tocan: el mapper de actualizacion lo ignora
     * explicitamente y {@code registerExit} solo escribe {@code exit}.
     */
    private String phone;
    @Builder.Default
    private LocalDateTime entry = LocalDateTime.now();
    private LocalDateTime exit;
    private String notes;
    @Builder.Default
    @OneToMany(
            mappedBy = "vehicleMovement",
            fetch = FetchType.EAGER,
            cascade = CascadeType.ALL,
            orphanRemoval = true)
    private List<VehicleMovementPhoto> photos = new ArrayList<>();

    public void addPhoto(VehicleMovementPhoto photo) {
        photo.setVehicleMovement(this);
        this.photos.add(photo);
    }

    public void removePhoto(VehicleMovementPhoto photo) {
        this.photos.remove(photo);
        photo.setVehicleMovement(null);
    }

}
