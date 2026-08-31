package com.cns.spring_house.supplier.dto;

import com.cns.spring_house.shared.constants.ColumnLimits;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Alta de proveedor.
 *
 * Solo el nombre. El taller lo pone el servidor desde el token: si el cliente
 * pudiera enviarlo, podria dar de alta proveedores en el taller de otro.
 *
 * {@code @NotBlank} y no {@code @NotNull}: aqui el campo es obligatorio de
 * verdad, asi que un nombre de solo espacios se rechaza igual que uno ausente.
 * El servicio ademas lo recorta y colapsa sus espacios repetidos antes de
 * guardarlo, de modo que lo que se persiste ya es la forma que se mostrara.
 */
public record CreateSupplierRequestDTO(

        @NotBlank(message = "El nombre del proveedor es obligatorio")
        @Size(
                max = ColumnLimits.SUPPLIER_NAME,
                message = "El nombre no puede superar los {max} caracteres")
        String name

) { }
