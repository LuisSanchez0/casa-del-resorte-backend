package com.cns.spring_house.vehiclemovement.dto;

import java.util.List;
import java.util.UUID;

import org.springframework.web.multipart.MultipartFile;

import jakarta.validation.constraints.Size;

public record UpdateVehicleMovementRequestDTO(

        String notes,
        @Size(max = 10, message = "No se pueden adjuntar mas de 10 fotografias")
        List<MultipartFile> photos,
        List<UUID> removedPhotos

) {

}
