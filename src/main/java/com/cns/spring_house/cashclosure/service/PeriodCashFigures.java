package com.cns.spring_house.cashclosure.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.cns.spring_house.cashclosure.dto.CashClosureCollectionsDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureExchangeDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureInvoicingDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureRefundsDTO;
import com.cns.spring_house.cashclosure.dto.ClosureSoldItemDTO;

/**
 * Las cifras de UN periodo de UN taller calculadas contra `sale`.
 *
 * Es el resultado unico de {@link CashClosureCalculator} y la fuente de las dos
 * rutas que necesitan datos frescos: la previsualizacion en vivo y el cierre
 * definitivo. Que las dos pasen por el mismo objeto es lo que garantiza que lo
 * que se ve antes de cerrar y lo que se persiste al cerrar se calculen con las
 * mismas reglas.
 *
 * En cuanto el cierre se confirma esto deja de importar: el historico se lee del
 * snapshot y nunca vuelve a recalcularse.
 */
public record PeriodCashFigures(

    LocalDate businessDate,
    List<ClosureSoldItemDTO> items,

    /**
     * Las dimensiones comerciales de cada articulo —familia, marca y modelo—,
     * indexadas por el mismo {@code productId} que identifica la fila de
     * {@link #items}.
     *
     * Van APARTE y no dentro del articulo porque tienen otro destino: el
     * articulo es lo que la pantalla y el PDF de cierre publican, y esto es
     * material de snapshot que solo se copia al cerrar para que el resumen
     * agrupado siga siendo reproducible cuando el catalogo cambie.
     */
    Map<UUID, ClosureProductDimensions> productDimensions,

    BigDecimal itemsBilledAmount,
    CashClosureInvoicingDTO invoicing,
    CashClosureExchangeDTO exchange,
    CashClosureCollectionsDTO collections,
    CashClosureRefundsDTO refunds

) { }
