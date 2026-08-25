package com.cns.spring_house.vehiclemovement.dto;

import java.util.List;
import java.util.Locale;

import org.springframework.web.multipart.MultipartFile;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateVehicleMovementRequestDTO(

        /**
         * Estructura de placa guatemalteca soportada: una o dos letras de tipo,
         * tres digitos y tres letras (P123ABC, C123ABC, M123ABC, TC123ABC). Sin
         * este patron una placa incompleta como "P" quedaba registrada, porque
         * @NotBlank solo exige que haya texto.
         *
         * El patron exige mayusculas y las obtiene siempre: el constructor
         * compacto normaliza la placa antes de que corra Bean Validation.
         */
        @NotBlank(message = "La placa es obligatoria")
        @Pattern(
                regexp = "^[A-Z]{1,2}\\d{3}[A-Z]{3}$",
                message = "La placa no tiene un formato valido. Formato esperado: P123ABC")
        String plate,

        /**
         * Telefono local guatemalteco de la persona asociada a esta entrada:
         * EXACTAMENTE ocho digitos. El backend es el validador autoritativo; que
         * Angular valide lo mismo es solo el aviso inmediato.
         *
         * No se acepta ninguna otra forma —'5845-2136', '5845 2136',
         * '+50258452136', '50258452136'— porque en este proyecto no existe
         * ninguna politica de normalizacion de telefonos que las convierta. Sin
         * esa politica, "arreglar" el valor seria adivinar: quitarle el 502 a
         * '50258452136' presupone que ese 502 es un prefijo de pais y no los
         * primeros digitos de otra cosa. Se rechaza y se pide el numero local.
         *
         * El @Pattern corre DESPUES de la normalizacion del constructor
         * compacto, asi que un espacio sobrante ya no llega hasta aqui.
         *
         * Es obligatorio para toda bitacora NUEVA. Las anteriores a V31 se
         * quedan en NULL: ver el comentario de la columna en la migracion.
         */
        @NotBlank(message = "El telefono es obligatorio")
        @Pattern(
                regexp = "^[0-9]{8}$",
                message = "El telefono debe tener exactamente 8 digitos")
        String phone,
        String notes,
        @Size(max = 10, message = "No se pueden adjuntar mas de 10 fotografias")
        List<MultipartFile> photos

) {

    /**
     * La placa se normaliza en el borde para que la comprobacion de duplicados y
     * el valor persistido sean siempre el mismo texto: validar " p123abc " y
     * guardar otra cosa dejaria pasar entradas abiertas repetidas.
     *
     * Locale.ROOT y no el del servidor: en locales como el turco 'i' no pasa a 'I',
     * lo que cambiaria la placa segun donde se despliegue.
     *
     * Se respeta el null para que @NotBlank sea quien produzca el error de
     * validacion en lugar de un NullPointerException al construir el record.
     */
    public CreateVehicleMovementRequestDTO {
        plate = plate == null ? null : plate.trim().toUpperCase(Locale.ROOT);
        // Al telefono se le recorta el espacio exterior y NADA MAS. Es lo mismo
        // que ya se hace con la placa, y por el mismo motivo: un campo de
        // formulario llega con espacios sin que el usuario haya escrito otra
        // cosa. Lo que NO se hace es quitar guiones, espacios interiores ni
        // prefijos: eso ya no seria normalizar sino reinterpretar el numero, y
        // el valor validado tiene que ser exactamente el que se persiste.
        phone = phone == null ? null : phone.trim();
    }

}
