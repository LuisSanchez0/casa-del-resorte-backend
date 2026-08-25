package com.cns.spring_house.sale.dto;

import java.util.UUID;

import com.cns.spring_house.workresult.dto.WorkResultSource;

/**
 * El trabajo ya ejecutado que respalda una linea de venta, cuando lo hay.
 *
 * Es lo MINIMO que hace falta para reconocer la linea despues: su identidad y
 * si lo que se cobro fue un servicio ejecutado (JOB) o una pieza transformada
 * (TRANSFORMATION). No expone el JobLog ni el item de salida: quien necesite el
 * trabajo entero lo pide a {@code GET /v1/work-results/{id}}.
 *
 * Sin esto, una linea de transformacion es indistinguible de la venta de
 * cualquier otra pieza SPECIAL: {@code SaleDetail} guarda el producto de salida
 * y su item, que son exactamente los mismos campos que llena una venta normal.
 * Deducir el origen por nombre o por fecha seria inventarse el dato, asi que se
 * publica el que ya esta persistido.
 */
public record SaleDetailWorkResultDTO(

    UUID id,
    WorkResultSource source

) {

}
