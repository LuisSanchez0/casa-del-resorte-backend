package com.cns.spring_house.sale.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import com.cns.spring_house.family.entity.Attribute;
import com.cns.spring_house.family.entity.Family;
import com.cns.spring_house.product.entity.Product;
import com.cns.spring_house.shared.exception.ValidationException;
import com.cns.spring_house.transformation.handler.SpecialPieceRules;

/**
 * Los colochos de la pieza que pidio el vendedor, en una linea de la venta.
 *
 * <ul>
 * <li>{@code hasCurls} TRUE: la pieza lleva colochos; {@code count} cuantos.
 * Una linea NUEVA siempre trae la cantidad: "si" sin cantidad no es un pedido
 * valido. Solo una linea guardada antes de esa regla puede tenerla vacia, y
 * ahi el Operador PUEDE registrarla.</li>
 * <li>{@code hasCurls} FALSE: la pieza no lleva colochos. Nadie le agrega.</li>
 * <li>{@code hasCurls} null: no se sabe —linea anterior a la regla—. Nunca se
 * lee como "no lleva" ni como cero.</li>
 * </ul>
 *
 * La decision del vendedor vive en la linea ({@code sale_detail.requested_curls}
 * y {@code requested_curl_count}); si el producto pedido ya fija sus colochos,
 * la decision es la del producto y se guarda igual en la linea, como foto de lo
 * pedido.
 */
public record RequestedCurls(Boolean hasCurls, Integer count) {

    public static final String ATTRIBUTE = "cantidadColochos";

    private static final RequestedCurls UNKNOWN = new RequestedCurls(null, null);

    /** La pieza no lleva colochos. */
    public boolean excludesCurls() {
        return Boolean.FALSE.equals(hasCurls);
    }

    /**
     * Lo pedido en una linea ya guardada. Una linea anterior a la regla no
     * tiene decision: se lee lo que fije su producto, y si no fija nada, no se
     * sabe.
     */
    public static RequestedCurls of(Boolean requestedCurls, Integer requestedCount,
            Map<String, Object> productAttributes) {
        if (requestedCurls != null) {
            return new RequestedCurls(requestedCurls, requestedCurls ? requestedCount : null);
        }
        Integer fixed = fixedCount(productAttributes);
        if (fixed == null) {
            return UNKNOWN;
        }
        return new RequestedCurls(fixed > 0, fixed > 0 ? fixed : null);
    }

    /**
     * La decision de una linea NUEVA, validada contra la pieza pedida.
     *
     * <ul>
     * <li>Familia sin colochos: no se admite ninguna decision.</li>
     * <li>Producto que fija sus colochos (n o 0): la decision es la suya; si el
     * vendedor manda otra, se rechaza.</li>
     * <li>Producto que no los fija: el vendedor TIENE que decir si lleva o no.
     * "Si" exige cuantos —un entero de 1 en adelante—; "No" no admite una
     * cantidad positiva (un cero es la misma decision y se acepta).</li>
     * </ul>
     *
     * Es la UNICA puerta de una linea nueva: la usan "agregar" y "solicitar con
     * estas caracteristicas", asi que ninguna de las dos acepta un "si" sin
     * cantidad, y como no deja nada creado cuando falla, la transaccion se
     * deshace entera.
     */
    public static RequestedCurls forNewLine(Product product, Boolean requestedCurls, Integer requestedCount) {
        if (!declaresCurlCount(product.getFamily())) {
            if (requestedCurls != null || requestedCount != null) {
                throw new ValidationException("La pieza pedida no maneja colochos");
            }
            return UNKNOWN;
        }
        if (requestedCount != null && requestedCount < 0) {
            throw new ValidationException("La cantidad de colochos no puede ser negativa");
        }
        Integer fixed = fixedCount(product.getAttributes());
        if (fixed != null) {
            boolean has = fixed > 0;
            if (requestedCurls != null && requestedCurls != has) {
                throw new ValidationException(has
                        ? "La pieza elegida lleva " + fixed + " colochos"
                        : "La pieza elegida no lleva colochos");
            }
            if (requestedCount != null && !requestedCount.equals(has ? fixed : 0)) {
                throw new ValidationException(has
                        ? "La pieza elegida lleva " + fixed + " colochos: no se puede pedir otra cantidad"
                        : "La pieza elegida no lleva colochos");
            }
            return new RequestedCurls(has, has ? fixed : null);
        }
        if (requestedCurls == null) {
            throw new ValidationException("Indique si la pieza lleva colochos");
        }
        if (!requestedCurls) {
            if (requestedCount != null && requestedCount != 0) {
                throw new ValidationException("Una pieza sin colochos no lleva cantidad de colochos");
            }
            return new RequestedCurls(false, null);
        }
        if (requestedCount == null) {
            throw new ValidationException("Indique cuántos colochos lleva la pieza");
        }
        if (requestedCount < 1) {
            throw new ValidationException(
                    "La cantidad de colochos es un número entero de 1 en adelante; si no lleva, elija «No»");
        }
        return new RequestedCurls(true, requestedCount);
    }

    /**
     * La cantidad de colochos escrita en las caracteristicas de una pieza NUEVA:
     * un entero de 0 en adelante. El tipo NUMBER de la familia por si solo
     * admitiria `3.5` o `-2`, y esa pieza naceria en el catalogo con colochos
     * imposibles.
     */
    public static void validateSpecified(Map<String, Object> attributes) {
        if (attributes == null) {
            return;
        }
        attributes.forEach((key, value) -> {
            if (key == null || !key.equalsIgnoreCase(ATTRIBUTE) || value == null) {
                return;
            }
            if (!(value instanceof Number number)) {
                throw new ValidationException("La cantidad de colochos debe ser un número entero");
            }
            BigDecimal decimal = new BigDecimal(number.toString());
            if (decimal.signum() < 0 || decimal.stripTrailingZeros().scale() > 0) {
                throw new ValidationException(
                        "La cantidad de colochos debe ser un número entero mayor o igual a cero");
            }
        });
    }

    /** Colochos que fija la pieza, o null si no los fija. */
    public static Integer fixedCount(Map<String, Object> attributes) {
        Double value = attributes == null ? null : SpecialPieceRules.numericAttribute(attributes, ATTRIBUTE);
        return value == null ? null : value.intValue();
    }

    /** Una familia maneja colochos si declara la caracteristica. */
    public static boolean declaresCurlCount(Family family) {
        List<Attribute> declared = family == null ? null : family.getAttributes();
        return declared != null && declared.stream()
                .anyMatch(attribute -> attribute.getName() != null
                        && attribute.getName().equalsIgnoreCase(ATTRIBUTE));
    }

}
