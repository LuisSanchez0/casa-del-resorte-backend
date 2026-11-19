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
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "mechanic_user_id")
    @ToString.Exclude
    private com.cns.spring_house.useraccount.entity.UserAccount mechanic;
    private java.time.Instant workStartedAt;
    private java.time.Instant workFinishedAt;
    private UUID startedBy;
    private UUID finishedBy;

    /**
     * La visita puede iniciar trabajo: sigue dentro y nunca inicio. El mecanico
     * responsable se elige en el mismo inicio, asi que no hace falta tenerlo.
     */
    public boolean isCanStartWork() {
        return exit == null && workStartedAt == null && workFinishedAt == null;
    }

    /**
     * Si el mecanico asignado (asignacion previa de una visita historica, o el
     * responsable de un trabajo ya iniciado) sigue siendo un operador activo
     * del taller de la visita. {@code null} sin mecanico. Solo informativo: el
     * inicio vuelve a validarlo con bloqueo.
     */
    public Boolean getMechanicEligible() {
        if (mechanic == null) return null;
        return Boolean.TRUE.equals(mechanic.getIsActive()) && mechanic.getRole() != null
            && "OPERATOR".equals(mechanic.getRole().getName()) && mechanic.getEmployee() != null
            && mechanic.getEmployee().getWorkshop().getId().equals(workshop.getId());
    }

    /**
     * Reloj de pared entre el PRIMER inicio y el fin definitivo. No es tiempo
     * de trabajo: incluye pausas. El tiempo activo esta en {@code work} de la
     * respuesta ({@code WorkTimingDTO}).
     */
    public Double getDurationSeconds() {
        return workStartedAt == null || workFinishedAt == null ? null
                : java.time.Duration.between(workStartedAt, workFinishedAt).toNanos() / 1_000_000_000d;
    }

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
