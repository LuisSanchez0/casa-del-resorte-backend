package com.cns.spring_house.cashclosure.service;

import java.util.UUID;

/**
 * Lo unico que el modulo de ventas necesita saber del cierre de caja: si la
 * caja del dia sigue abierta para registrar actividad financiera.
 *
 * Es una interfaz aparte de {@code CashClosureService} —aunque la implemente la
 * misma clase— para que {@code SaleServiceImpl} dependa de una sola operacion y
 * no del servicio completo de cierres, con sus previsualizaciones, sus rangos y
 * sus PDF.
 *
 * Es UNA sola regla para las tres operaciones que mueven cifras de caja:
 *
 * <pre>
 * create()          -> abre una venta
 * completeSale()    -> emite el documento  (facturacion, y cobro si es INVOICE)
 * registerPayment() -> cobra una cambiaria (cobro)
 * </pre>
 *
 * No existen —ni deben existir— validadores separados por operacion: los tres
 * responderian exactamente a la misma pregunta, y tres copias de una regla son
 * tres sitios donde puede quedarse desactualizada.
 */
public interface CashClosureGuard {

    /**
     * Rechaza la operacion si el taller ya cerro la caja de la fecha operativa
     * en curso, y deja tomado hasta el final de la transaccion el cerrojo que
     * impide que un cierre se confirme en paralelo.
     *
     * Quien lo llama debe hacerlo ANTES de producir cualquier efecto: el valor
     * de este metodo no esta solo en la excepcion que lanza, sino en el cerrojo
     * que deja tomado durante el resto de la transaccion. Llamarlo despues de
     * escribir dejaria exactamente la carrera que existe para evitar.
     *
     * La fecha operativa la resuelve la implementacion con el mismo
     * {@code Clock} que usa el cierre —y que desde ahora usan tambien
     * {@code completedAt} y {@code paidAt}—: si cada lado mirase su propio
     * reloj, una peticion servida justo en el cambio de dia podria comparar
     * fechas distintas.
     *
     * @throws com.cns.spring_house.shared.exception.ValidationException si la
     *         caja de ese taller y esa fecha ya fue cerrada.
     */
    void ensureCashRegisterIsOpen(UUID workshopId);

}
