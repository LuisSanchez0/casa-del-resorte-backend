package com.cns.spring_house.dailylog.dto;

/**
 * Las cifras del dia en curso.
 *
 * Los tres conteos NO comparten criterio de fecha, y es deliberado:
 *
 * <pre>
 * salesCount             -> Sale COMPLETED con completed_at dentro del dia
 * workshopDailyLogsCount -> DailyLog del taller con performed_date = hoy
 * myDailyLogsCount       -> los del EMPLEADO autenticado, misma fecha
 * </pre>
 *
 * Una venta se fecha por cuando se EMITIO el documento; una bitacora, por la
 * fecha con la que la secretaria la levanta. Registrar hoy la bitacora de una
 * factura de ayer suma a la bitacora de hoy y a las ventas de ayer, asi que las
 * dos series describen actividades distintas y no tienen por que cuadrar.
 *
 * {@code workshopDailyLogsCount} esta para que {@code gap} se pueda leer sin
 * suponer nada: el hueco entre las ventas del taller y las bitacoras PROPIAS no
 * es "trabajo que falta" mientras otra persona pueda estar levantando el resto.
 * Con las dos cifras delante, la diferencia se explica sola.
 *
 * {@code gap} es {@code salesCount - myDailyLogsCount}, con signo: negativo
 * significa que hay mas bitacoras propias que ventas emitidas hoy, cosa
 * perfectamente posible cuando se ponen al dia facturas de dias anteriores.
 */
public record SecretaryDashboardTodayDTO(

        long salesCount,
        long workshopDailyLogsCount,
        long myDailyLogsCount,
        long gap,
        SecretaryDashboardClosureDTO closure

) {

}
