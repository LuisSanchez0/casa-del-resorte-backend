package com.cns.spring_house.cashclosure.dto;

/**
 * Un PDF ya generado con el nombre con el que debe descargarse.
 *
 * El nombre lo decide el servicio y no el controlador porque depende de datos
 * que solo el servicio conoce (la fecha operativa del cierre, los extremos del
 * rango), y armarlo en el controlador obligaria a devolverle esos datos solo
 * para eso.
 */
public record CashClosureDocumentDTO(

    String fileName,
    byte[] content

) {

}
