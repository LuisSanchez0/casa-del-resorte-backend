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
     * Resuelve el precio efectivo del detalle y lo valida. Vive aqui porque cada
     * handler conoce una fuente de costo distinta pero la regla es la misma.
     *
     * @param requestedPrice  precio especial de la venta; null usa el de catalogo
     * @param catalogPrice    precio vigente del producto o servicio
     * @param acquisitionCost costo de adquisicion, o null si la categoria no tiene
     *                        uno real (caso JOB), donde solo se exige no negativo
     */
    default BigDecimal resolvePrice(
            BigDecimal requestedPrice,
            BigDecimal catalogPrice,
            BigDecimal acquisitionCost) {
        BigDecimal price = requestedPrice != null ? requestedPrice : catalogPrice;
        if (price == null) {
            throw new ValidationException("El precio es obligatorio");
        }
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
        return price;
    }

}
