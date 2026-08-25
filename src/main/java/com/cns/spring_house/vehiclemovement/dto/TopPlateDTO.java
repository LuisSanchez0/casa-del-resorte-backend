package com.cns.spring_house.vehiclemovement.dto;

/**
 * Placa normalizada (UPPER) y su numero de ENTRADAS historicas, no de vehiculos
 * ni de visitas abiertas. El orden lo fija la consulta: entries DESC, plate ASC,
 * para que dos placas empatadas no cambien de posicion entre peticiones.
 */
public record TopPlateDTO(

        String plate,
        long entries

) {

}
