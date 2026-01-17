package com.cns.spring_house.dto.leafpack;

import java.util.List;
import java.util.UUID;

import com.cns.spring_house.dto.leaf.CreateLeafRequestDTO;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

public record CreateLeafPackRequestDTO(
    @NotNull(message = "El ID del stock item es obligatorio")
    UUID stockItemId,
    @NotNull(message = "La lista de hojas es obligatoria")
    @NotEmpty(message = "La lista de hojas no puede estar vacía")
    @Valid
    List<CreateLeafRequestDTO> leaves
) {}
