package com.cns.spring_house.sale.handler;

import java.math.BigDecimal;

import com.cns.spring_house.family.entity.FamilyCategory;
import com.cns.spring_house.sale.dto.AddSaleDetailRequestDTO;
import com.cns.spring_house.sale.entity.Sale;
import com.cns.spring_house.sale.entity.SaleDetail;
import com.cns.spring_house.shared.exception.ValidationException;

public interface SaleDetailHandler {

    FamilyCategory getType();

    SaleDetail addDetail(Sale sale, AddSaleDetailRequestDTO requestDTO);

    /**
     * Devuelve al inventario lo que el detalle habia reservado, SIN sacarlo de la
     * venta. Es el inverso exacto de {@link #addDetail} sobre el inventario.
     *
     * Existe separado de {@link #removeDetail} porque hay dos formas de soltar una
     * reserva: quitar la linea (el detalle desaparece) y cancelar la venta (el
     * detalle sobrevive como historial). Las dos tienen que restaurar identico, y
     * la unica manera de garantizarlo es que compartan este metodo.
     */
    void restore(SaleDetail saleDetail);

    /**
     * Quita la linea de la venta devolviendo lo que reservo. La restauracion vive
     * en {@link #restore}: aqui solo se añade sacar el detalle de la venta.
     */
    default void removeDetail(Sale sale, SaleDetail saleDetail) {
        restore(saleDetail);
        sale.removeDetail(saleDetail);
    }

    /**
     * Precio efectivo de la linea y que hacer con el catalogo.
     *
     * Son dos respuestas y no una porque la MISMA cifra significa cosas
     * distintas segun de donde salga: sobre un producto con precio, un importe
     * escrito a mano es un descuento de ESTA venta; sobre uno sin precio, es el
     * precio que el taller acaba de asignarle.
     *
     * @param price              importe con el que se cobra la linea
     * @param becomesCatalogPrice si ademas pasa a ser el precio vigente del
     *                            taller para las proximas ventas
     */
    record PricingOutcome(BigDecimal price, boolean becomesCatalogPrice) { }

    /**
     * Un importe de catalogo que el taller todavia no ha fijado.
     *
     * En el esquema NO existe el precio nulo: `inventory.price` y `job.price`
     * son `NUMERIC(10,2) NOT NULL CHECK (>= 0)`. El cero es, por tanto, la unica
     * forma que tiene este modelo de decir "sin precio asignado", y ya se usa
     * asi: el inventario de salida de una transformacion se crea en cero
     * precisamente porque nadie le ha puesto precio todavia.
     */
    static boolean isUnpriced(BigDecimal catalogPrice) {
        return catalogPrice == null || catalogPrice.signum() <= 0;
    }

    /**
     * Resuelve el precio efectivo del detalle, lo valida y decide si ademas
     * pasa a ser el precio vigente del taller. Vive aqui porque cada handler
     * conoce una fuente de costo distinta pero la regla es la misma.
     *
     * Dos caminos, y la diferencia la decide el CATALOGO, no el cliente:
     *
     * <ul>
     * <li>Con precio de catalogo, lo que se envie es un precio especial de esta
     * venta. No reescribe nada: un descuento puntual no puede cambiarle el
     * precio al taller.</li>
     * <li>Sin precio de catalogo (cero), enviarlo es OBLIGATORIO —si no, la
     * pieza se despacharia gratis— y ademas queda como el precio vigente. Es
     * el mismo importe y una sola decision, asi que se persiste en la MISMA
     * transaccion que la linea: o hay venta y precio, o no hay ninguna de las
     * dos.</li>
     * </ul>
     *
     * Que el cliente no mande ninguna bandera es deliberado: "este producto no
     * tiene precio" es un hecho del servidor, y dejar que lo afirme quien
     * pregunta seria darle a elegir cuando reescribir el catalogo.
     *
     * @param requestedPrice  precio enviado con la linea; null usa el de catalogo
     * @param catalogPrice    precio vigente del producto o servicio
     * @param acquisitionCost costo de adquisicion, o null si la categoria no tiene
     *                        uno real (caso JOB), donde solo se exige no negativo
     */
    default PricingOutcome resolvePricing(
            BigDecimal requestedPrice,
            BigDecimal catalogPrice,
            BigDecimal acquisitionCost) {

        if (isUnpriced(catalogPrice)) {
            if (requestedPrice == null || requestedPrice.signum() <= 0) {
                throw new ValidationException(
                        "Este producto no tiene un precio de venta asignado: "
                        + "indique el precio con el que se cobrara");
            }
            validate(requestedPrice, acquisitionCost);
            return new PricingOutcome(requestedPrice, true);
        }

        BigDecimal price = requestedPrice != null ? requestedPrice : catalogPrice;
        validate(price, acquisitionCost);
        return new PricingOutcome(price, false);
    }

    private void validate(BigDecimal price, BigDecimal acquisitionCost) {
        if (price.signum() < 0) {
            throw new ValidationException(
                    "El precio de venta no puede ser negativo");
        }
        // Vender por debajo del costo es perdida directa: se permite rebajar el
        // precio de catalogo, nunca bajar del costo con el que se adquirio.
        if (acquisitionCost != null && price.compareTo(acquisitionCost) < 0) {
            throw new ValidationException(
                    "El precio de venta no puede ser menor al costo de adquisicion del producto");
        }
    }

}
