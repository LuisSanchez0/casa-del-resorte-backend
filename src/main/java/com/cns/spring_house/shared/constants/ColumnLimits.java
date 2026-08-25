package com.cns.spring_house.shared.constants;

/**
 * Límites que impone el esquema a los campos que rellena el usuario, tal y como
 * los definen las migraciones. Existen para que la validación de los DTO, el
 * mapeo de las entidades y el esquema no puedan separarse: son constantes de
 * compilación, así que las anotaciones de Bean Validation pueden usarlas.
 *
 * Sin ellas, una petición fuera de rango llegaba hasta PostgreSQL y volvía como
 * un 500 genérico —"value too long for type character varying(25)", un
 * desbordamiento de smallint o un CHECK violado— para lo que en realidad es un
 * error de validación del cliente.
 *
 * No son un límite global: cada campo tiene el suyo y rara vez coinciden.
 */
public final class ColumnLimits {

    private ColumnLimits() {

    }

    // ── Longitudes de texto (VARCHAR) ────────────────────────────────

    public static final int PRODUCT_SKU = 25;
    public static final int PRODUCT_NAME = 100;

    public static final int FAMILY_CODE = 25;
    public static final int FAMILY_NAME = 50;

    public static final int ATTRIBUTE_NAME = 50;

    public static final int BRAND_NAME = 50;
    public static final int MODEL_NAME = 50;

    public static final int WORKSHOP_NAME = 100;

    public static final int EMPLOYEE_NAME = 100;
    public static final int EMPLOYEE_DPI = 13;
    public static final int EMPLOYEE_PHONE = 8;

    public static final int USERNAME = 50;

    public static final int CUSTOMER_NIT = 13;
    public static final int CUSTOMER_NAME = 75;

    public static final int PLATE = 10;

    /**
     * Motivo de {@code sale_action} (cancelacion o reversion). 500 sigue el
     * mismo orden de magnitud que otros campos de texto libre del esquema
     * (`daily_log.description` es TEXT sin tope porque es una bitacora
     * interna; este si es un dato que se muestra en el historial comercial y
     * necesita un limite predecible).
     */
    public static final int SALE_ACTION_REASON = 500;

    /**
     * Observaciones de un cierre de caja. Mismo tope que el motivo de
     * {@code sale_action} y por la misma razon: es texto libre que se imprime
     * en un documento historico, asi que necesita un limite predecible en vez
     * de ser un TEXT sin tope como la bitacora interna.
     */
    public static final int CASH_CLOSURE_NOTES = 500;

    // ── Rangos numéricos ─────────────────────────────────────────────

    /**
     * Las columnas de dinero son `NUMERIC(10, 2)`: ocho dígitos enteros y dos
     * decimales. `@Digits` describe justo eso, así que un importe con más parte
     * entera o más decimales se rechaza aquí en vez de desbordar la columna.
     */
    public static final int MONEY_INTEGER_DIGITS = 8;
    public static final int MONEY_FRACTION_DIGITS = 2;

    /**
     * Las existencias son `SMALLINT`. Su techo no es una regla de negocio: es
     * el máximo que la columna puede almacenar, y pasarse reventaba el INSERT.
     */
    public static final int SMALLINT_MAX = 32767;

    /** `model.year` tiene un CHECK de 1900 en adelante. */
    public static final int MODEL_YEAR_MIN = 1900;

}
