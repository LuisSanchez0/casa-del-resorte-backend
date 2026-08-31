package com.cns.spring_house.cashclosure.report;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.LocalDateTime;

import com.cns.spring_house.family.entity.FamilyCategory;
import com.cns.spring_house.sale.entity.PaymentMethod;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Como se leen las cifras y las fechas en el documento.
 *
 * El signo va DELANTE del simbolo de moneda (`-Q20.00`) porque asi se lee un
 * faltante de un vistazo; `Q-20.00` obliga a mirar dos veces.
 */
final class ReportFormats {

    private ReportFormats() {
    }

    static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    /**
     * Locale.ROOT fija coma para los miles y punto para los decimales, que es
     * como se escribe el quetzal. Sin fijarlo, el formato dependeria de la
     * configuracion regional de la maquina donde corre el servidor y el mismo
     * cierre se veria distinto en dos despliegues.
     */
    private static final DecimalFormat AMOUNT = new DecimalFormat(
            "#,##0.00", DecimalFormatSymbols.getInstance(Locale.ROOT));

    static String money(BigDecimal amount) {
        BigDecimal value = amount == null ? BigDecimal.ZERO : amount;
        String formatted = AMOUNT.format(value.abs());
        return (value.signum() < 0 ? "-Q" : "Q") + formatted;
    }

    /** Importes que siempre llevan signo explicito, como la diferencia de caja. */
    static String signedMoney(BigDecimal amount) {
        BigDecimal value = amount == null ? BigDecimal.ZERO : amount;
        if (value.signum() <= 0) {
            return money(value);
        }
        return "+" + money(value);
    }

    static String count(long value) {
        return Long.toString(value);
    }

    static String date(LocalDate value) {
        return value == null ? "" : DATE.format(value);
    }

    static String dateTime(LocalDateTime value) {
        return value == null ? "" : DATE_TIME.format(value);
    }


    /**
     * La categoria de un articulo en el idioma del taller. Se traduce aqui y no
     * en la plantilla para que el PDF y la pantalla usen las mismas palabras.
     */
    static String itemType(FamilyCategory category) {
        return switch (category) {
            case NORMAL -> "Producto";
            case SPECIAL -> "Pieza";
            case CONTAINER -> "Contenedor";
            case JOB -> "Servicio";
        };
    }

    /** La forma de pago en el idioma del taller. */
    static String paymentMethod(PaymentMethod method) {
        return switch (method) {
            case CASH -> "Efectivo";
            case CARD -> "Tarjeta";
            case TRANSFER -> "Transferencia / Deposito";
        };
    }

}
