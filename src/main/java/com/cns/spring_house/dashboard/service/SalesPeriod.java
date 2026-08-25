package com.cns.spring_house.dashboard.service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;

import com.cns.spring_house.dashboard.dto.SalesGranularity;
import com.cns.spring_house.shared.exception.ValidationException;

/**
 * El intervalo que de verdad se va a consultar y pintar, ya resuelto contra la
 * fecha de negocio.
 *
 * Ambos extremos son el PRIMER dia de su intervalo, en la misma normalizacion
 * que devuelve la consulta: para MONTH, {@code from} es el 1 de enero y
 * {@code to} el 1 del mes en curso.
 *
 * Aqui vive la unica regla que impide inventar futuro: {@code to} nunca pasa
 * del intervalo al que pertenece la fecha de negocio. Pedir agosto en curso
 * devuelve hasta hoy y no hasta el 31, y pedir un mes que todavia no ha
 * empezado devuelve una serie vacia en lugar de treinta ceros que aparentarian
 * dias sin ventas.
 */
record SalesPeriod(SalesGranularity granularity, LocalDate from, LocalDate to) {

    /** Ano minimo aceptado: por debajo, el texto no es un ano de negocio. */
    private static final int MIN_YEAR = 2000;

    /**
     * Resuelve el periodo pedido.
     *
     * @param period       texto del cliente: {@code YYYY-MM} en DAY, {@code YYYY}
     *                     en MONTH, ignorado en YEAR. Null o vacio toma el actual.
     * @param businessDate fecha operativa del servidor.
     * @param historyStart fecha de la primera venta completada del taller, o null
     *                     si no hay ninguna; solo la usa YEAR para no ofrecer anos
     *                     vacios.
     */
    static SalesPeriod resolve(
            SalesGranularity granularity,
            String period,
            LocalDate businessDate,
            LocalDate historyStart) {

        String requested = period == null || period.isBlank() ? null : period.trim();

        return switch (granularity) {
            case DAY -> ofDays(requested, businessDate);
            case MONTH -> ofMonths(requested, businessDate);
            case YEAR -> ofYears(businessDate, historyStart);
        };
    }

    /** Un punto por dia del mes pedido, sin pasar de la fecha de negocio. */
    private static SalesPeriod ofDays(String period, LocalDate businessDate) {
        YearMonth month = period == null
                ? YearMonth.from(businessDate)
                : parseMonth(period);

        LocalDate from = month.atDay(1);
        LocalDate to = min(month.atEndOfMonth(), businessDate);
        return new SalesPeriod(SalesGranularity.DAY, from, to);
    }

    /** Un punto por mes del ano pedido, sin pasar del mes en curso. */
    private static SalesPeriod ofMonths(String period, LocalDate businessDate) {
        int year = period == null ? businessDate.getYear() : parseYear(period);

        LocalDate from = LocalDate.of(year, 1, 1);
        LocalDate to = min(LocalDate.of(year, 12, 1), businessDate.withDayOfMonth(1));
        return new SalesPeriod(SalesGranularity.MONTH, from, to);
    }

    /**
     * Un punto por ano, desde la primera venta del taller hasta el actual.
     *
     * No admite selector porque no lo necesita: el rango disponible ES toda la
     * historia, y un control que solo puede elegir "todo" es un control que no
     * hace nada. Sin ventas, la serie es el ano en curso en cero.
     */
    private static SalesPeriod ofYears(LocalDate businessDate, LocalDate historyStart) {
        LocalDate current = LocalDate.of(businessDate.getYear(), 1, 1);
        LocalDate first = historyStart == null
                ? current
                : LocalDate.of(historyStart.getYear(), 1, 1);
        return new SalesPeriod(SalesGranularity.YEAR, min(first, current), current);
    }

    private static YearMonth parseMonth(String period) {
        try {
            YearMonth month = YearMonth.parse(period);
            requireYearInRange(month.getYear());
            return month;
        } catch (DateTimeParseException exception) {
            throw new ValidationException(
                    "El periodo debe tener el formato AAAA-MM");
        }
    }

    private static int parseYear(String period) {
        int year;
        try {
            year = Integer.parseInt(period);
        } catch (NumberFormatException exception) {
            throw new ValidationException("El periodo debe tener el formato AAAA");
        }
        requireYearInRange(year);
        return year;
    }

    /**
     * El limite superior evita que un ano absurdo genere una serie enorme; el
     * inferior, que un texto como "0001" pase por ano.
     */
    private static void requireYearInRange(int year) {
        if (year < MIN_YEAR || year > LocalDate.now().getYear() + 1) {
            throw new ValidationException("El ano solicitado esta fuera de rango");
        }
    }

    private static LocalDate min(LocalDate left, LocalDate right) {
        return left.isBefore(right) ? left : right;
    }

    /** Cierto cuando el periodo pedido aun no ha empezado: no hay nada que pintar. */
    boolean isEmpty() {
        return to.isBefore(from);
    }

}
