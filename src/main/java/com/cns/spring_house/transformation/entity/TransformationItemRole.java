package com.cns.spring_house.transformation.entity;

public enum TransformationItemRole {

    /** Unidad física consumida o utilizada por la transformación. */
    INPUT,
    /** Sobrante generado automáticamente por una transformación SPECIAL. */
    REMAINDER,
    /** Producto principal generado por una transformación SPECIAL. */
    OUTPUT,
    /** Unidad que estaba dentro de un CONTAINER y fue liberada al desarmarlo. */
    RELEASED;

}
