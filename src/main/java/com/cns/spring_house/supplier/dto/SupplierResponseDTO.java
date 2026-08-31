package com.cns.spring_house.supplier.dto;

import java.util.UUID;

/**
 * Un proveedor tal y como lo ve el cliente.
 *
 * NO lleva taller. No es un olvido: el taller sale del token en cada peticion,
 * asi que exponerlo aqui solo daria al cliente un identificador que jamas debe
 * enviar de vuelta.
 *
 * {@code name} es el nombre CANONICO que guarda el servidor —el que se pinta en
 * el campo cuando se elige un proveedor—, no el texto que la persona tecleo
 * para encontrarlo.
 */
public record SupplierResponseDTO(

        UUID id,
        String name

) { }
