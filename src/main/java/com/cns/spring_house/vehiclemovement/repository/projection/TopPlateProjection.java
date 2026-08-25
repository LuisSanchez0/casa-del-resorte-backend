package com.cns.spring_house.vehiclemovement.repository.projection;

/** Una fila de la agregacion "placas con mas ingresos", ya normalizada a UPPER. */
public interface TopPlateProjection {

    String getPlate();

    long getEntries();

}
