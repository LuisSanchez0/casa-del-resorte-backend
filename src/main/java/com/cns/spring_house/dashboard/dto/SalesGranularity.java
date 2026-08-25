package com.cns.spring_house.dashboard.dto;

/**
 * Unidad de agregacion de la serie de ventas generadas.
 *
 * Cada valor lleva el argumento que {@code DATE_TRUNC} necesita en PostgreSQL.
 * Esta aqui, en el enum, y no en el servicio: asi el conjunto de unidades que
 * pueden llegar a la sentencia es exactamente el conjunto de valores que el
 * enum admite, y ningun texto del cliente puede alcanzar la consulta.
 */
public enum SalesGranularity {

    DAY("day"),
    MONTH("month"),
    YEAR("year");

    private final String sqlUnit;

    SalesGranularity(String sqlUnit) {
        this.sqlUnit = sqlUnit;
    }

    public String sqlUnit() {
        return sqlUnit;
    }

}
