package com.cns.spring_house.cashclosure.report;

/**
 * Los datos corporativos que encabezan el reporte, tal y como existen HOY en el
 * modelo.
 *
 * `Workshop` solo tiene nombre y direccion: no hay NIT, ni telefono, ni correo.
 * El encabezado imprime lo que hay y calla el resto, en vez de reservar huecos
 * vacios o inventar datos, y no se anadieron columnas al taller para llenar un
 * reporte que nadie pidio ampliar.
 *
 * Tampoco se guarda una copia de estos datos en `cash_closure`: la relacion con
 * el taller ya identifica al emisor, y un taller no cambia de nombre lo bastante
 * a menudo como para justificar duplicarlo en cada cierre. Si algun dia hiciera
 * falta que el documento historico conserve la razon social del momento, esa es
 * una decision que se toma entonces y con motivo.
 */
public record ReportHeaderDTO(

    String workshopName,
    /** Puede ser null: el encabezado simplemente no imprime la linea. */
    String address

) {

}
