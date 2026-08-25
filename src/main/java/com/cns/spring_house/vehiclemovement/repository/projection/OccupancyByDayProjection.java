package com.cns.spring_house.vehiclemovement.repository.projection;

/**
 * Vehiculos DENTRO del taller al cerrar cada dia del periodo.
 *
 * No es {@link EntriesByDayProjection} con otro nombre: aquella cuenta cuantos
 * vehiculos ENTRARON ese dia, y esta cuantos SEGUIAN dentro al terminarlo. Un
 * dia sin ninguna entrada puede tener ocho vehiculos dentro —los que entraron
 * antes y no han salido—, y un dia con seis entradas puede cerrar en cero.
 *
 * La fecha viaja como texto {@code YYYY-MM-DD} por el mismo motivo que el resto
 * de proyecciones nativas del modulo.
 */
public interface OccupancyByDayProjection {

    String getBucketDate();

    long getVehicles();

}
