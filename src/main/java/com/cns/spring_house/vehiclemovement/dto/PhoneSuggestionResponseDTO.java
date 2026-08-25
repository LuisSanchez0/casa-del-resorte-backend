package com.cns.spring_house.vehiclemovement.dto;

/**
 * Telefono sugerido para una placa: el de su bitacora mas reciente CON telefono
 * en el taller autenticado, o null si esa placa no tiene ninguno.
 *
 * Un solo campo, a proposito. No devuelve el movimiento del que sale, ni su
 * fecha, ni la lista de telefonos que ha tenido la placa: el alta solo necesita
 * saber que numero proponer, y todo lo demas seria exponer historial de contacto
 * sin que nadie lo haya pedido.
 *
 * Es una SUGERENCIA, no un vinculo. Que exista no autoriza a reutilizarlo solo:
 * el operador tiene que elegir explicitamente entre usarlo o escribir otro, y lo
 * que se guarde se guarda como valor propio de la bitacora nueva.
 */
public record PhoneSuggestionResponseDTO(

        String phone

) {

}
