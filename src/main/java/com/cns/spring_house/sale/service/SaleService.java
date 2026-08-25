package com.cns.spring_house.sale.service;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;

import com.cns.spring_house.sale.dto.AddSaleDetailRequestDTO;
import com.cns.spring_house.sale.dto.CancelSaleRequestDTO;
import com.cns.spring_house.sale.dto.CompleteSaleRequestDTO;
import com.cns.spring_house.sale.dto.CreateSaleRequestDTO;
import com.cns.spring_house.sale.dto.ExchangeInvoiceSummaryDTO;
import com.cns.spring_house.sale.dto.RegisterPaymentRequestDTO;
import com.cns.spring_house.sale.dto.ReverseSaleRequestDTO;
import com.cns.spring_house.sale.dto.SaleResponseDTO;
import com.cns.spring_house.sale.dto.SaleSummaryDTO;
import com.cns.spring_house.sale.dto.SearchSaleRequestDTO;
import com.cns.spring_house.sale.entity.PaymentStatus;

public interface SaleService {

    List<SaleResponseDTO> getAll(SearchSaleRequestDTO requestDTO);

    /**
     * Cartera de facturas cambiarias del taller autenticado, de la mas reciente a
     * la mas antigua, en resumen y paginada.
     *
     * @param paymentStatus null devuelve toda la cartera, cobrada y pendiente
     * @param page          pagina solicitada, desde 0
     * @param size          filas por pagina, entre 1 y 100
     */
    Page<ExchangeInvoiceSummaryDTO> getExchangeInvoices(
            PaymentStatus paymentStatus, int page, int size);

    /**
     * Facturas ya emitidas del taller autenticado que coinciden con un fragmento
     * de texto, de la mas reciente a la mas antigua y en resumen.
     *
     * Es el buscador de la bitacora: la secretaria teclea los primeros
     * caracteres del identificador impreso —o el nit, el nombre o la placa— y
     * elige. Solo devuelve ventas COMPLETED, incluidas las cambiarias que siguen
     * pendientes de cobro.
     *
     * @param query texto parcial; null o en blanco devuelve las mas recientes
     * @param limit numero maximo de sugerencias; null usa el valor por defecto
     */
    List<SaleSummaryDTO> searchCompletedSales(String query, Integer limit);

    SaleResponseDTO get(UUID id);

    SaleResponseDTO create(CreateSaleRequestDTO requestDTO);

    SaleResponseDTO addDetail(UUID id, AddSaleDetailRequestDTO requestDTO);

    SaleResponseDTO removeDetail(UUID saleDetailId);

    SaleResponseDTO completeSale(UUID id, CompleteSaleRequestDTO requestDTO);

    SaleResponseDTO registerPayment(UUID id, RegisterPaymentRequestDTO requestDTO);

    /**
     * Anula una venta abierta conservandola como historial en CANCELLED. Para
     * hacer desaparecer un borrador, {@link #delete}. El motivo es
     * obligatorio y queda persistido en {@code SaleAction}.
     */
    SaleResponseDTO cancel(UUID id, CancelSaleRequestDTO requestDTO);

    /**
     * Revierte una venta ya COMPLETED: restaura exactamente lo que esa venta
     * habia consumido (stock, items, WorkResult) y la deja en REVERSED,
     * conservando la Sale y sus SaleDetail como historial. Distinto de
     * {@link #cancel}, que solo aplica a una venta que nunca llego a
     * completarse.
     */
    SaleResponseDTO reverse(UUID id, ReverseSaleRequestDTO requestDTO);

    void delete(UUID id);

}
