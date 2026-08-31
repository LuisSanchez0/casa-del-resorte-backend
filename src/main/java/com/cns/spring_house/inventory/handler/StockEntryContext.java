package com.cns.spring_house.inventory.handler;

import com.cns.spring_house.employee.entity.Employee;
import com.cns.spring_house.supplier.entity.Supplier;

/**
 * Lo que el SERVIDOR aporta a una entrada de inventario, ya resuelto y
 * verificado contra el taller autenticado.
 *
 * Existe para que los handlers no vuelvan a consultar el proveedor ni la
 * identidad de quien registra: si cada uno lo hiciera por su cuenta, cada uno
 * tendria su propia idea de que significa "el proveedor de este taller", y basta
 * que una difiera para abrir el cruce entre talleres que el modelo prohibe. El
 * servicio lo resuelve UNA vez y aqui viaja ya comprobado.
 *
 * {@code supplier} es null en el armado de una maleta, que no es una compra.
 */
public record StockEntryContext(Supplier supplier, Employee employee) { }
