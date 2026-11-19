package com.cns.spring_house.testsupport;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import com.cns.spring_house.sale.dto.AddSaleDetailRequestDTO;
import com.cns.spring_house.sale.dto.RequestSpecificationDTO;
import com.cns.spring_house.shared.security.SecurityUtils;

/**
 * Una linea nueva como la manda la caja: con el precio de catalogo que el
 * vendedor tenia a la vista al confirmar.
 *
 * `expectedCatalogPrice` es obligatorio desde que se cerro el atajo de omitirlo.
 * Las pruebas escritas antes lo dejaban en null porque era opcional; en una
 * prueba SECUENCIAL lo que el vendedor ve es exactamente lo vigente, asi que
 * estas ayudas lo completan con el catalogo actual del taller del token (0 si
 * no hay precio ni inventario), que es lo que enviaria un cliente correcto.
 *
 * Nunca pisan un valor ya puesto: las pruebas de 409 traen su catalogo visto
 * a proposito. Y las pruebas de "falta la precondicion" llaman al servicio
 * directamente, sin pasar por aqui.
 */
public final class SeenCatalog {

    private SeenCatalog() {
    }

    /** El catalogo vigente del producto en el taller del token; 0 si no tiene. */
    public static BigDecimal current(JdbcTemplate jdbc, UUID product) {
        UUID workshop = SecurityUtils.getWorkshopId();
        List<BigDecimal> prices = jdbc.queryForList(
                "SELECT price FROM inventory WHERE product_id = ? AND workshop_id = ? "
                        + "UNION ALL SELECT price FROM job WHERE product_id = ? AND workshop_id = ?",
                BigDecimal.class, product, workshop, product, workshop);
        return prices.isEmpty() ? BigDecimal.ZERO : prices.get(0);
    }

    public static AddSaleDetailRequestDTO seen(JdbcTemplate jdbc, AddSaleDetailRequestDTO dto) {
        if (dto.expectedCatalogPrice() != null) {
            return dto;
        }
        return new AddSaleDetailRequestDTO(
                dto.product(), dto.item(), dto.workResult(), dto.quantity(), dto.price(),
                dto.invoiceDescription(), dto.instructions(), dto.plate(),
                dto.requestedCurls(), dto.requestedCurlCount(),
                current(jdbc, dto.product()), dto.noCharge());
    }

    /**
     * Una pieza descrita por caracteristicas: lo normal es que no exista o no
     * tenga inventario, y el vendedor la vio "sin precio" (0).
     */
    public static RequestSpecificationDTO seenAsNew(RequestSpecificationDTO dto) {
        if (dto.expectedCatalogPrice() != null) {
            return dto;
        }
        return new RequestSpecificationDTO(
                dto.family(), dto.model(), dto.attributes(), dto.quantity(), dto.price(),
                dto.invoiceDescription(), dto.instructions(), dto.requestedCurls(),
                dto.requestedCurlCount(), BigDecimal.ZERO);
    }

}
