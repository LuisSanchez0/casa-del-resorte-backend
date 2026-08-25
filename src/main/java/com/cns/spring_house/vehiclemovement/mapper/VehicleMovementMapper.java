package com.cns.spring_house.vehiclemovement.mapper;

import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants.ComponentModel;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;

import com.cns.spring_house.vehiclemovement.dto.CreateVehicleMovementRequestDTO;
import com.cns.spring_house.vehiclemovement.dto.UpdateVehicleMovementRequestDTO;
import com.cns.spring_house.vehiclemovement.dto.VehicleMovementResponseDTO;
import com.cns.spring_house.vehiclemovement.entity.VehicleMovement;

@Mapper(componentModel = ComponentModel.SPRING)
public interface VehicleMovementMapper {

    /**
     * `photos` se ignora en ambos sentidos: el DTO trae MultipartFile y la entidad
     * guarda solo la key devuelta por S3, asi que la coleccion la arma el servicio.
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "workshop", ignore = true)
    // El actor lo fija el servicio desde la identidad autenticada: si el mapper
    // pudiera escribirlo, un campo del formulario decidiria de quien es la
    // entrada y "Mis estadisticas" pasaria a ser falsificable desde el cliente.
    @Mapping(target = "employee", ignore = true)
    @Mapping(target = "entry", ignore = true)
    @Mapping(target = "exit", ignore = true)
    @Mapping(target = "photos", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    VehicleMovement toVehicleMovement(CreateVehicleMovementRequestDTO requestDTO);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "workshop", ignore = true)
    // Quien registro la entrada no cambia porque otro edite las notas.
    @Mapping(target = "employee", ignore = true)
    @Mapping(target = "plate", ignore = true)
    // El telefono es la foto historica de la ENTRADA. Editar notas o fotografias
    // no puede reescribirlo: si el mapper pudiera tocarlo, un PATCH cambiaria a
    // posteriori el dato de contacto de una bitacora ya cerrada.
    @Mapping(target = "phone", ignore = true)
    @Mapping(target = "entry", ignore = true)
    @Mapping(target = "exit", ignore = true)
    @Mapping(target = "photos", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    void updateVehicleMovementFromDTO(
            UpdateVehicleMovementRequestDTO requestDTO,
            @MappingTarget VehicleMovement movement);

    /** Respuesta completa, telefono incluido: solo para POLICE y ADMIN. */
    VehicleMovementResponseDTO toResponse(VehicleMovement movement);

    /**
     * La MISMA respuesta sin el telefono, para cualquier otro rol autenticado.
     *
     * Existe porque {@code GET /v1/vehicle-movements} lo comparten varios roles
     * —Vendedor lo usa para sugerir placas al facturar— y el telefono solo tiene
     * destinatario en el historial de Policia y de Admin. Con un unico
     * {@code toResponse}, anadir el campo al DTO habria bastado para empezar a
     * mandar telefonos en respuestas que nadie va a mostrar.
     *
     * Es una omision en el borde de serializacion, no un filtro de consulta: la
     * fila se lee igual y lo unico que cambia es lo que sale por el JSON. Deja el
     * campo en null, indistinguible de una bitacora historica sin telefono, que
     * es exactamente lo que un rol no autorizado debe poder deducir.
     */
    @Mapping(target = "phone", ignore = true)
    VehicleMovementResponseDTO toResponseWithoutPhone(VehicleMovement movement);

}
