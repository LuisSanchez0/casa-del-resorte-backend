package com.cns.spring_house.sale.handler;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.cns.spring_house.family.entity.FamilyCategory;
import com.cns.spring_house.sale.dto.AddSaleDetailRequestDTO;
import com.cns.spring_house.sale.entity.Sale;
import com.cns.spring_house.sale.entity.SaleDetail;
import org.springframework.security.access.AccessDeniedException;

import com.cns.spring_house.shared.exception.PriceConflictException;
import com.cns.spring_house.shared.exception.ValidationException;
import com.cns.spring_house.shared.security.SecurityUtils;

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
     * El precio de catalogo sobre el que el vendedor decidio sigue siendo el
     * vigente.
     *
     * El vendedor confirma un precio mirando el catalogo de ESE momento; si
     * entre tanto otra caja o Control lo cambio, la misma cifra ya no significa
     * lo mismo: lo que iba a ser el precio de catalogo pasaria a ser un precio
     * especial, o lo que iba a fijar el precio del taller dejaria de fijarlo.
     * En vez de reinterpretarlo en silencio se rechaza con un 409 y el
     * vendedor lo reconfirma sobre el precio nuevo.
     *
     * Es OBLIGATORIO en toda linea nueva: sin el, una cifra decidida sobre un
     * catalogo viejo se aplicaria a ciegas sobre el nuevo. Cero significa "lo
     * vi sin precio" —inventario con precio 0, o sin inventario en el taller—
     * y coincide con cualquier catalogo sin precio, igual que
     * {@link #isUnpriced}; asi hay un valor posible tambien donde no existe
     * catalogo. Si otro lo fija entretanto, ya no coincide: 409.
     *
     * No es una bandera de autorizacion: no decide el precio ni su origen, solo
     * impide aplicar una decision tomada sobre un dato que ya cambio. Corre
     * con el inventario (o el servicio) ya bloqueado, en la misma transaccion
     * que escribe la linea.
     */
    static void requireCatalogPriceAsSeen(BigDecimal expected, BigDecimal current) {
        if (expected == null) {
            throw new ValidationException(SEEN_CATALOG_REQUIRED);
        }
        boolean expectedUnpriced = isUnpriced(expected);
        boolean currentUnpriced = isUnpriced(current);
        if (expectedUnpriced && currentUnpriced) {
            return;
        }
        if (!expectedUnpriced && !currentUnpriced && expected.compareTo(current) == 0) {
            return;
        }
        throw new PriceConflictException(currentUnpriced
                ? "Este producto ya no tiene precio de catalogo. Revise y confirme el precio de nuevo."
                : "El precio de catalogo cambio a Q " + current.setScale(2, RoundingMode.HALF_UP).toPlainString()
                        + ". Revise y confirme el precio de nuevo.");
    }

    /** Sin el precio de catalogo que vio el vendedor no se agrega ninguna linea. */
    String SEEN_CATALOG_REQUIRED =
            "Indique el precio de catalogo que tenia a la vista (0 si no tenia precio): "
            + "sin el no se puede saber si cambio";

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
     * "Este producto no tiene precio" es un hecho del servidor: el cliente no
     * lo afirma. Lo que si afirma el cliente es su INTENCION de no cobrar
     * (`noCharge`), y sin ella un cero se rechaza: ver {@link #requireExplicitNoCharge}.
     *
     * @param requestedPrice       precio enviado con la linea; null usa el de catalogo
     * @param catalogPrice         precio vigente del producto o servicio
     * @param acquisitionCost      costo de adquisicion, o null si la categoria no
     *                             tiene uno real (caso JOB)
     * @param expectedCatalogPrice catalogo que vio el vendedor (0 = sin precio);
     *                             obligatorio
     * @param noCharge             el vendedor pidio expresamente "sin cargo"
     */
    default PricingOutcome resolvePricing(
            BigDecimal requestedPrice,
            BigDecimal catalogPrice,
            BigDecimal acquisitionCost,
            BigDecimal expectedCatalogPrice,
            boolean noCharge) {
        if (noCharge) {
            requireNoChargeAuthority();
        }
        requireCatalogPriceAsSeen(expectedCatalogPrice, catalogPrice);

        if (isUnpriced(catalogPrice)) {
            if (noCharge) {
                throw new ValidationException(NO_CHARGE_WITHOUT_CATALOG);
            }
            if (requestedPrice == null || requestedPrice.signum() <= 0) {
                throw new ValidationException(
                        "Este producto no tiene un precio de venta asignado: "
                        + "indique el precio con el que se cobrara");
            }
            validateAgainstCost(requestedPrice, acquisitionCost);
            return new PricingOutcome(requestedPrice, true);
        }

        BigDecimal price = requestedPrice != null ? requestedPrice : catalogPrice;
        requireExplicitNoCharge(price, noCharge);
        if (noCharge && acquisitionCost == null) {
            // Un servicio no tiene costo en el modelo: desconocido no es cero.
            throw new ValidationException(NO_CHARGE_COST_UNKNOWN);
        }
        validateAgainstCost(price, acquisitionCost);
        return new PricingOutcome(price, false);
    }

    /** Cero sin precio de catalogo: el producto no tiene precio, no es gratis. */
    String NO_CHARGE_WITHOUT_CATALOG =
            "Este producto no tiene precio de catalogo: no puede ir sin cargo. Indique un precio mayor que cero";

    /**
     * LA regla de "sin cargo", igual al agregar una linea y al corregir su
     * precio. `noCharge` es una PETICION, no un permiso:
     *
     * <ul>
     * <li>Solo la piden los roles que deciden importes en la caja (ver
     * {@link #NO_CHARGE_ROLES}); para cualquier otro, 403.</li>
     * <li>Q0.00 solo con esa peticion expresa. Un cero suelto —un campo en
     * blanco convertido, un cliente que lo fabrica— se rechaza: no se confunde
     * con "sin precio" ni con un descuento.</li>
     * <li>`noCharge` solo con Q0.00: no puede quedar marcado sobre otro
     * importe.</li>
     * <li>Hace falta precio de catalogo, ningun minimo positivo, y un costo
     * REGISTRADO en cero para las unidades de la linea
     * ({@link #requireRecordedZeroCost}). Desconocido no es cero.</li>
     * </ul>
     */
    static void requireExplicitNoCharge(BigDecimal price, boolean noCharge) {
        if (noCharge) {
            requireNoChargeAuthority();
        }
        if (price.signum() == 0 && !noCharge) {
            throw new ValidationException(
                    "Un precio de Q0.00 solo se acepta marcado expresamente como \"sin cargo\"");
        }
        if (noCharge && price.signum() != 0) {
            throw new ValidationException("\"Sin cargo\" exige un precio de Q0.00");
        }
    }

    /**
     * Quien puede PEDIR una linea sin cargo: los mismos roles que deciden
     * importes en la caja ("Solo el vendedor —y Administracion— decide
     * importes", la regla de `update-price`). Agregar una linea no exige rol en
     * el controlador; un "sin cargo" si, igual al agregar que al corregir.
     *
     * Que el vendedor pueda autorizarlo SOLO, sin aprobacion de Administracion,
     * es el comportamiento que el sistema ya tenia; si debe cambiar es una
     * decision del negocio pendiente (ver docs/caja-price-guidance.md).
     */
    String[] NO_CHARGE_ROLES = {"SELLER", "ADMIN"};

    /** 403 para cualquier rol que no decide importes. Lee el rol del token, nunca del cuerpo. */
    static void requireNoChargeAuthority() {
        if (!SecurityUtils.hasAnyRole(NO_CHARGE_ROLES)) {
            throw new AccessDeniedException(
                    "Solo el vendedor o Administracion pueden pedir una linea sin cargo");
        }
    }

    String NO_CHARGE_FOR_REQUEST =
            "Una solicitud al taller no puede ir sin cargo: la pieza y su costo se conocen "
            + "cuando el taller la cumple. Indique un precio mayor que cero";
    String NO_CHARGE_COST_UNKNOWN =
            "El costo de esta linea no esta registrado (entro sin compra, o es un servicio): "
            + "un costo desconocido no es cero, asi que no puede ir sin cargo. Indique un precio mayor que cero";
    String NO_CHARGE_COST_POSITIVE =
            "Las unidades de esta linea tienen costo: no puede ir sin cargo. Indique un precio desde su costo";

    /**
     * "Sin cargo" exige que el costo de las unidades de ESTA linea este
     * registrado y sea cero: `costTotal` lo fija el consumo FIFO al tomar las
     * unidades, y es null en cuanto UNA viene de un lote sin costo
     * (PRODUCTION: transformacion, maleta armada) —ver V32: "sin cobertura de
     * costo, no costo cero"—. Un servicio no tiene costo en el modelo, y una
     * solicitud al taller no tiene unidad ni costo hasta que se cumple. En
     * ninguno de esos casos se presume cero.
     *
     * Se llama DESPUES de tomar las unidades y en la misma transaccion: si
     * rechaza, todo lo anterior —reserva, lotes, catalogo— se deshace.
     */
    static void requireRecordedZeroCost(SaleDetail detail) {
        if (detail.isRequest()) {
            throw new ValidationException(NO_CHARGE_FOR_REQUEST);
        }
        if (detail.getCostTotal() == null) {
            throw new ValidationException(NO_CHARGE_COST_UNKNOWN);
        }
        if (detail.getCostTotal().signum() > 0) {
            throw new ValidationException(NO_CHARGE_COST_POSITIVE);
        }
    }

    /**
     * El precio nuevo de una linea YA agregada, con las MISMAS reglas que al
     * agregarla (ver {@link #resolvePricing}) salvo que nunca reescribe el
     * catalogo: corregir lo que se cobra en una venta no le cambia el precio al
     * taller.
     *
     * @param floor el minimo vigente: el costo de la pieza o, en revision de
     *              precio, el costo de la pieza de origen si es mayor
     */
    static void validateEditedPrice(
            BigDecimal price, boolean noCharge, BigDecimal catalogPrice, BigDecimal floor) {
        if (price == null) {
            throw new ValidationException("El precio es obligatorio");
        }
        if (price.signum() < 0) {
            throw new ValidationException("El precio de venta no puede ser negativo");
        }
        if (price.stripTrailingZeros().scale() > 2) {
            throw new ValidationException("El precio admite como maximo dos decimales");
        }
        requireExplicitNoCharge(price, noCharge);
        if (price.signum() == 0 && isUnpriced(catalogPrice)) {
            throw new ValidationException(NO_CHARGE_WITHOUT_CATALOG);
        }
        if (floor != null && price.compareTo(floor) < 0) {
            throw new ValidationException(
                    "El precio no puede ser menor al costo de la pieza (Q "
                    + floor.setScale(2, RoundingMode.HALF_UP).toPlainString() + ")");
        }
    }

    private void validateAgainstCost(BigDecimal price, BigDecimal acquisitionCost) {
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
