package com.cns.spring_house.acquisition.entity;

/**
 * Como llego al taller la mercaderia de un lote COMPRADO.
 *
 * Vive en la ADQUISICION y no en el proveedor a proposito: la identidad de
 * quien vende no es lo mismo que la via comercial de una compra concreta, y un
 * mismo proveedor puede traer una vez material nacional y otra material
 * importado. Clavarlo en {@code Supplier} obligaria a dar de alta el mismo
 * proveedor dos veces para poder registrar las dos compras.
 *
 * En la interfaz se rotulan "Nacional" e "Importacion"; los literales siguen en
 * ingles y en mayusculas como el resto de enums persistidos del proyecto
 * ({@code SaleStatus}, {@code InvoiceType}, {@code FamilyCategory}).
 */
public enum AcquisitionType {

    NATIONAL,
    IMPORTATION;

}
