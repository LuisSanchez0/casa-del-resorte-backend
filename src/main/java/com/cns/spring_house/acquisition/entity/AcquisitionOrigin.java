package com.cns.spring_house.acquisition.entity;

/**
 * Por que via entro al inventario la unidad de un lote.
 *
 * Existe para que el invariante
 *
 * <pre>
 * inventory.stock = SUM(quantity_remaining)
 * </pre>
 *
 * valga SIEMPRE y no solo para lo comprado. En este dominio el stock no solo
 * entra comprandolo: una transformacion produce piezas nuevas, armar una maleta
 * crea una unidad de contenedor y desarmarla devuelve sus componentes. Si esas
 * entradas no dejaran lote, un inventario tendria mas existencias que
 * remanentes y el FIFO se toparia con lineas de venta que solo podria costear a
 * medias, que es justo el estado que este modelo debe hacer imposible.
 *
 * <ul>
 * <li>{@code PURCHASE}: compra real registrada en Control. Lleva proveedor,
 * tipo de adquisicion y costo unitario, los tres obligatorios. Es la unica que
 * suma en las graficas de gasto y en el ranking de proveedores.</li>
 * <li>{@code PRODUCTION}: la unidad entro sin comprarse. NO tiene proveedor, ni
 * tipo, ni costo, y el CHECK del esquema impide inventarselos.</li>
 * </ul>
 *
 * Un lote {@code PRODUCTION} no es un proveedor ficticio: es el registro
 * explicito de que esa unidad NO se compro. Lo que se venda consumiendolo queda
 * marcado como sin cobertura de costo, nunca como costo cero, porque costo cero
 * convertiria el precio entero en ganancia.
 */
public enum AcquisitionOrigin {

    PURCHASE,
    PRODUCTION;

}
