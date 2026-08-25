package com.cns.spring_house.cashclosure.mapper;

import java.math.BigDecimal;

import org.springframework.stereotype.Component;

import com.cns.spring_house.cashclosure.dto.CashClosureBasePreviewResponseDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureCollectionsDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureInvoicingDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureReceivablesDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureResponseDTO;
import com.cns.spring_house.cashclosure.dto.CashReconciliationDTO;
import com.cns.spring_house.cashclosure.entity.CashBalanceStatus;
import com.cns.spring_house.cashclosure.entity.CashClosure;
import com.cns.spring_house.cashclosure.service.DailyCashFigures;
import com.cns.spring_house.employee.entity.Employee;

/**
 * Traduce entre las cifras del dia, la entidad y las respuestas.
 *
 * No es un mapper de MapStruct: la respuesta agrupa las cifras en cuatro
 * bloques anidados (facturacion, cobros, cartera, arqueo) mientras que la
 * entidad las tiene planas, asi que no hay coincidencia de nombres que generar
 * automaticamente. Es codigo escrito a mano porque la correspondencia lo es.
 *
 * El punto importante esta en {@link #toResponse(CashClosure)}: lee campo por
 * campo del snapshot y no toca `sale` ni al calculador. Un cierre historico
 * responde lo que se confirmo ese dia, no lo que hoy diria la base de datos.
 */
@Component
public class CashClosureMapper {

    /** Vista dinamica sin arqueo: lo que responde {@code GET /preview}. */
    public CashClosureBasePreviewResponseDTO toBasePreview(
            DailyCashFigures figures, CashClosure existingClosure) {
        return new CashClosureBasePreviewResponseDTO(
                figures.businessDate(),
                figures.invoicing(),
                figures.collections(),
                figures.receivables(),
                existingClosure != null,
                existingClosure == null ? null : existingClosure.getId());
    }

    /**
     * Previsualizacion final: el documento completo con arqueo, todavia sin
     * identidad. `id`, `closedAt` y `closedBy` van en null porque no existe
     * ningun cierre; es justo lo que distingue este DTO de un snapshot.
     */
    public CashClosureResponseDTO toPreview(
            DailyCashFigures figures,
            BigDecimal openingCash,
            BigDecimal countedCash,
            String notes,
            boolean alreadyClosed) {

        BigDecimal expectedCash = figures.expectedCash(openingCash);
        BigDecimal difference = countedCash.subtract(expectedCash);

        return new CashClosureResponseDTO(
                null,
                figures.businessDate(),
                null,
                null,
                alreadyClosed,
                figures.invoicing(),
                figures.collections(),
                figures.receivables(),
                reconciliation(openingCash, expectedCash, countedCash, difference),
                notes);
    }

    /** El snapshot confirmado, leido de sus propias columnas. */
    public CashClosureResponseDTO toResponse(CashClosure closure) {
        return new CashClosureResponseDTO(
                closure.getId(),
                closure.getBusinessDate(),
                // `createdAt` ES el instante del cierre: la fila se inserta al
                // confirmar y nunca se modifica.
                closure.getCreatedAt(),
                fullNameOf(closure.getClosedBy()),
                true,
                new CashClosureInvoicingDTO(
                        closure.getInvoiceCount(),
                        closure.getInvoiceTotal(),
                        closure.getExchangeInvoiceCount(),
                        closure.getExchangeInvoiceTotal(),
                        closure.getTotalInvoiced()),
                new CashClosureCollectionsDTO(
                        closure.getCashPaymentCount(),
                        closure.getCashPayments(),
                        closure.getCardPaymentCount(),
                        closure.getCardPayments(),
                        closure.getTransferPaymentCount(),
                        closure.getTransferPayments(),
                        closure.getTotalCollected()),
                new CashClosureReceivablesDTO(
                        closure.getExchangeCollectedCount(),
                        closure.getExchangeCollectedAmount(),
                        closure.getPreviousExchangeCollectedAmount(),
                        closure.getPendingExchangeAmount()),
                reconciliation(
                        closure.getOpeningCash(),
                        closure.getExpectedCash(),
                        closure.getCountedCash(),
                        closure.getDifference()),
                closure.getNotes());
    }

    private CashReconciliationDTO reconciliation(
            BigDecimal openingCash,
            BigDecimal expectedCash,
            BigDecimal countedCash,
            BigDecimal difference) {
        return new CashReconciliationDTO(
                openingCash,
                expectedCash,
                countedCash,
                difference,
                CashBalanceStatus.of(difference));
    }

    /**
     * El responsable se expone como texto y no como un objeto empleado: el
     * cierre es un documento, y quien lo firmo se lee, no se navega.
     */
    private String fullNameOf(Employee employee) {
        if (employee == null) {
            return null;
        }
        String firstName = employee.getFirstName() == null ? "" : employee.getFirstName();
        String lastName = employee.getLastName() == null ? "" : employee.getLastName();
        String fullName = (firstName + " " + lastName).trim();
        return fullName.isEmpty() ? null : fullName;
    }

}
