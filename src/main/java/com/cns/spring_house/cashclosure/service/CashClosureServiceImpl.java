package com.cns.spring_house.cashclosure.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cns.spring_house.cashclosure.dto.CashClosureCollectionsDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureDocumentDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureExchangeDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureInvoicingDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureMonthStatus;
import com.cns.spring_house.cashclosure.dto.CashClosureMonthlyResponseDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureRefundsDTO;
import com.cns.spring_house.cashclosure.dto.ClosureRefundDTO;
import com.cns.spring_house.cashclosure.dto.ClosureRefundMethodDTO;
import com.cns.spring_house.cashclosure.entity.CashClosureRefund;
import com.cns.spring_house.cashclosure.dto.CashClosureResponseDTO;
import com.cns.spring_house.cashclosure.dto.ClosureExchangeInvoiceDTO;
import com.cns.spring_house.cashclosure.dto.ClosurePaymentMethodDTO;
import com.cns.spring_house.cashclosure.dto.ClosureSoldItemDTO;
import com.cns.spring_house.cashclosure.entity.CashClosure;
import com.cns.spring_house.cashclosure.entity.CashClosureExchangeInvoice;
import com.cns.spring_house.cashclosure.entity.ExchangeInvoiceScope;
import com.cns.spring_house.cashclosure.mapper.CashClosureMapper;
import com.cns.spring_house.cashclosure.report.CashClosureReportService;
import com.cns.spring_house.cashclosure.report.ReportHeaderDTO;
import com.cns.spring_house.cashclosure.repository.CashClosureRepository;
import com.cns.spring_house.cashclosure.repository.projection.CashClosureItemProjection;
import com.cns.spring_house.cashclosure.repository.projection.CashClosurePaymentMethodProjection;
import com.cns.spring_house.cashclosure.repository.projection.CashClosureSummaryProjection;
import com.cns.spring_house.employee.entity.Employee;
import com.cns.spring_house.family.entity.FamilyCategory;
import com.cns.spring_house.sale.entity.PaymentMethod;
import com.cns.spring_house.shared.exception.EntityNotFoundException;
import com.cns.spring_house.shared.exception.ValidationException;
import com.cns.spring_house.shared.security.CurrentUserService;
import com.cns.spring_house.shared.security.SecurityUtils;
import com.cns.spring_house.workshop.entity.Workshop;
import com.cns.spring_house.workshop.repository.WorkshopRepository;

import lombok.RequiredArgsConstructor;

/**
 * Cierre de caja: previsualizacion en vivo, confirmacion del snapshot, lectura
 * historica y agregacion mensual.
 *
 * Dos invariantes gobiernan la clase entera:
 *
 * 1. El taller SIEMPRE sale de {@code SecurityUtils.getWorkshopId()}. Ningun
 *    metodo lo acepta como parametro y ninguna lectura historica usa
 *    {@code findById} a secas.
 *
 * 2. Mientras no existe cierre, `sale` es la fuente de verdad y todo se calcula
 *    al vuelo; en cuanto existe, el snapshot lo es y NADA vuelve a recalcularse.
 *    Por eso {@link #get}, {@link #getByDate}, {@link #getMonthly} y el PDF
 *    historico no tienen acceso al calculador.
 *
 * Implementa tambien {@link CashClosureGuard} porque el bloqueo de ventas
 * necesita exactamente la misma comprobacion —cerrojo mas existencia del
 * cierre— que hace {@link #close}: separarla habria significado escribirla dos
 * veces.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CashClosureServiceImpl implements CashClosureService, CashClosureGuard {

    private final CashClosureRepository repository;
    private final CashClosureCalculator calculator;
    private final CashClosureMapper mapper;
    private final CashClosureReportService reportService;
    private final CurrentUserService currentUserService;
    private final WorkshopRepository workshopRepository;
    private final Clock clock;

    // ── Previsualizacion: calcula, no persiste ───────────────────

    @Override
    public CashClosureResponseDTO getPreview() {
        UUID workshopId = SecurityUtils.getWorkshopId();
        LocalDate businessDate = businessDate();

        PeriodCashFigures figures = calculator.calculate(workshopId, businessDate);
        CashClosure existing = repository
                .findByWorkshopIdAndBusinessDate(workshopId, businessDate)
                .orElse(null);

        return mapper.toPreview(figures, existing);
    }

    // ── Cierre definitivo ────────────────────────────────────────

    /**
     * Confirma el cierre del dia.
     *
     * Recalcula TODO contra la base en el momento de confirmar: entre ver la
     * pantalla y pulsar el boton pueden haberse emitido facturas o cobrado
     * cartera, y cerrar con las cifras que el navegador tenia a la vista dejaria
     * un snapshot que no describe el dia.
     *
     * El cerrojo del taller se toma ANTES de comprobar la existencia del cierre
     * y no se suelta hasta el commit: es lo que impide que una venta se cuele
     * entre el calculo y la insercion.
     */
    @Override
    @Transactional
    public CashClosureResponseDTO close() {
        Employee employee = currentUserService.getCurrentEmployee();
        Workshop workshop = currentUserService.getCurrentWorkshop();
        UUID workshopId = SecurityUtils.getWorkshopId();
        LocalDate businessDate = lockBusinessDate(workshopId);

        if (repository.existsByWorkshopIdAndBusinessDate(workshopId, businessDate)) {
            throw new ValidationException(
                    "La caja de este taller ya fue cerrada para la fecha "
                            + businessDate);
        }

        PeriodCashFigures figures = calculator.calculate(workshopId, businessDate);

        CashClosure closure = CashClosure.builder()
                .workshop(workshop)
                .closedBy(employee)
                .businessDate(businessDate)
                .build();
        mapper.applyFigures(closure, figures);

        try {
            return mapper.toResponse(repository.saveAndFlush(closure));
        } catch (DataIntegrityViolationException exception) {
            // Red de seguridad del UNIQUE(workshop_id, business_date). El
            // cerrojo de arriba ya serializa los cierres de un taller, pero la
            // restriccion es la unica garantia que no depende de que todo el
            // mundo pase por este metodo.
            throw new ValidationException(
                    "La caja de este taller ya fue cerrada para la fecha "
                            + businessDate);
        }
    }

    // ── Bloqueo de ventas y cobros ───────────────────────────────

    /**
     * Lo que consultan {@code SaleServiceImpl.create}, {@code completeSale} y
     * {@code registerPayment} antes de mover una sola cifra.
     *
     * La fecha operativa se resuelve con el MISMO {@code Clock} que usa
     * {@link #close}, asi que ambos lados hablan siempre del mismo dia. El
     * cerrojo se toma antes de comprobar y dura hasta el commit de la operacion
     * que llamo: sin el, una venta que consulto "caja abierta" podria emitirse o
     * cobrarse despues de que otro hilo confirmara el cierre.
     *
     * Cubre tambien el COBRO, y eso resuelve el caso delicado: cobrar hoy una
     * cambiaria antigua se rechaza si la caja de HOY ya cerro —ese dinero
     * pertenece a un periodo ya firmado—, pero se acepta sin problema manana,
     * cuando la caja del dia siguiente este abierta. Lo que decide es la fecha
     * del COBRO, no la de emision del documento.
     */
    @Override
    @Transactional
    public void ensureCashRegisterIsOpen(UUID workshopId) {
        LocalDate businessDate = lockBusinessDate(workshopId);
        if (repository.existsByWorkshopIdAndBusinessDate(workshopId, businessDate)) {
            throw new ValidationException(
                    "La caja del dia ya fue cerrada. "
                            + "No se pueden registrar nuevas ventas ni cobros");
        }
    }

    // ── Lecturas historicas: solo snapshot ───────────────────────

    /**
     * Un cierre de otro taller responde igual que uno inexistente: la consulta
     * lleva el taller dentro, asi que conocer el UUID no revela nada.
     */
    @Override
    public CashClosureResponseDTO get(UUID id) {
        return mapper.toResponse(findOwnedClosure(id));
    }

    @Override
    public CashClosureResponseDTO getByDate(LocalDate date) {
        if (date == null) {
            throw new ValidationException("La fecha es obligatoria");
        }
        CashClosure closure = repository
                .findByWorkshopIdAndBusinessDate(SecurityUtils.getWorkshopId(), date)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe un cierre de caja para la fecha indicada"));
        return mapper.toResponse(closure);
    }

    // ── Cierre mensual ───────────────────────────────────────────

    /**
     * El mes, agregado sobre los cierres diarios CONFIRMADOS.
     *
     * Se construye desde los snapshots y no contra `sale`: si se recalculara,
     * cobrar hoy una cambiaria de marzo cambiaria el cierre mensual de marzo,
     * que ya se habia dado por bueno.
     *
     * El limite superior es el ultimo dia del mes que YA OCURRIO. En el mes en
     * curso eso es hoy: contar como "pendientes" los dias que aun no han
     * llegado presentaria como incompleto un mes que va perfectamente al dia.
     *
     * Son CUATRO consultas para el mes entero, no una por dia.
     */
    @Override
    public CashClosureMonthlyResponseDTO getMonthly(int year, int month) {
        YearMonth requested = parseMonth(year, month);
        UUID workshopId = SecurityUtils.getWorkshopId();
        LocalDate today = businessDate();

        LocalDate from = requested.atDay(1);
        LocalDate to = min(requested.atEndOfMonth(), today);

        if (to.isBefore(from)) {
            // Un mes que todavia no ha empezado: no hay nada que agregar, y
            // devolver ceros con "0 dias pendientes" sugeriria que esta cerrado.
            return emptyMonth(requested, from);
        }

        CashClosureSummaryProjection totals =
                repository.summarizeMonth(workshopId, from, to);
        List<ClosureSoldItemDTO> items = monthlyItems(workshopId, from, to);
        CashClosureCollectionsDTO collections =
                monthlyCollections(workshopId, from, to);

        long expectedDays = from.datesUntil(to.plusDays(1)).count();
        long closedDays = totals.getClosureCount();
        long pendingDays = Math.max(0L, expectedDays - closedDays);

        return new CashClosureMonthlyResponseDTO(
                requested.getYear(),
                requested.getMonthValue(),
                from,
                to,
                closedDays,
                pendingDays,
                pendingDays == 0L
                        ? CashClosureMonthStatus.COMPLETE
                        : CashClosureMonthStatus.PARTIAL,
                items,
                money(totals.getItemsBilledAmount()),
                new CashClosureInvoicingDTO(
                        totals.getInvoiceCount(),
                        money(totals.getInvoiceTotal()),
                        totals.getExchangeInvoiceCount(),
                        money(totals.getExchangeInvoiceTotal()),
                        money(totals.getTotalInvoiced())),
                new CashClosureExchangeDTO(
                        totals.getExchangeInvoiceCount(),
                        money(totals.getExchangeInvoiceTotal()),
                        money(totals.getIssuedExchangePendingAmount()),
                        totals.getExchangeCollectedCount(),
                        money(totals.getExchangeCollectedAmount()),
                        money(totals.getPreviousExchangeCollectedAmount()),
                        monthlyInvoices(workshopId, from, to, ExchangeInvoiceScope.ISSUED),
                        monthlyInvoices(workshopId, from, to, ExchangeInvoiceScope.COLLECTED)),
                collections,
                monthlyRefunds(workshopId, from, to, totals),
                money(collections.totalCollected()
                        .subtract(money(totals.getTotalRefunded()))));
    }

    /**
     * Las devoluciones del mes, agregadas de los SNAPSHOTS diarios.
     *
     * Los totales salen de los escalares que `summarizeMonth` ya trajo —no hay
     * consulta extra para ellos— y el detalle de UNA consulta para todo el mes.
     * Releer `sale_refund` seria mas simple y estaria mal: una devolucion
     * registrada despues aparecerian en un mes que ya estaba dicho.
     */
    private CashClosureRefundsDTO monthlyRefunds(
            UUID workshopId, LocalDate from, LocalDate to,
            CashClosureSummaryProjection totals) {

        List<ClosureRefundMethodDTO> methods = List.of(
                new ClosureRefundMethodDTO(PaymentMethod.CASH,
                        totals.getRefundCashCount(), money(totals.getRefundCash())),
                new ClosureRefundMethodDTO(PaymentMethod.CARD,
                        totals.getRefundCardCount(), money(totals.getRefundCard())),
                new ClosureRefundMethodDTO(PaymentMethod.TRANSFER,
                        totals.getRefundTransferCount(),
                        money(totals.getRefundTransfer())));

        List<ClosureRefundDTO> refunds = new ArrayList<>();
        for (CashClosureRefund refund
                : repository.findMonthlyRefunds(workshopId, from, to)) {
            refunds.add(new ClosureRefundDTO(
                    refund.getRefundId(),
                    refund.getSaleId(),
                    refund.getDocumentCode(),
                    money(refund.getAmount()),
                    refund.getEffectiveAt(),
                    refund.getPaymentMethod(),
                    refund.getPerformedByName(),
                    refund.getReason()));
        }

        return new CashClosureRefundsDTO(
                methods,
                totals.getRefundCount(),
                money(totals.getTotalRefunded()),
                List.copyOf(refunds));
    }

    private CashClosureMonthlyResponseDTO emptyMonth(YearMonth month, LocalDate from) {
        return new CashClosureMonthlyResponseDTO(
                month.getYear(), month.getMonthValue(), from, from,
                0L, 0L, CashClosureMonthStatus.PARTIAL,
                List.of(), money(BigDecimal.ZERO),
                new CashClosureInvoicingDTO(0L, money(BigDecimal.ZERO), 0L,
                        money(BigDecimal.ZERO), money(BigDecimal.ZERO)),
                new CashClosureExchangeDTO(0L, money(BigDecimal.ZERO),
                        money(BigDecimal.ZERO), 0L, money(BigDecimal.ZERO),
                        money(BigDecimal.ZERO), List.of(), List.of()),
                new CashClosureCollectionsDTO(zeroMethods(), money(BigDecimal.ZERO)),
                new CashClosureRefundsDTO(
                        zeroRefundMethods(), 0L, money(BigDecimal.ZERO), List.of()),
                money(BigDecimal.ZERO));
    }

    /** Las tres formas de devolucion en cero: un mes sin devoluciones las dice. */
    private static List<ClosureRefundMethodDTO> zeroRefundMethods() {
        List<ClosureRefundMethodDTO> methods = new ArrayList<>();
        for (PaymentMethod method : PaymentMethod.values()) {
            methods.add(new ClosureRefundMethodDTO(
                    method, 0L, BigDecimal.ZERO.setScale(2)));
        }
        return List.copyOf(methods);
    }

    private List<ClosureSoldItemDTO> monthlyItems(
            UUID workshopId, LocalDate from, LocalDate to) {

        List<ClosureSoldItemDTO> items = new ArrayList<>();
        for (CashClosureItemProjection row
                : repository.findMonthlyItems(workshopId, from, to)) {
            items.add(new ClosureSoldItemDTO(
                    row.getProductId(),
                    row.getItemName(),
                    FamilyCategory.valueOf(row.getItemType()),
                    row.getQuantity(),
                    money(row.getBilledAmount())));
        }
        return List.copyOf(items);
    }

    private List<ClosureExchangeInvoiceDTO> monthlyInvoices(
            UUID workshopId, LocalDate from, LocalDate to, ExchangeInvoiceScope scope) {

        List<ClosureExchangeInvoiceDTO> invoices = new ArrayList<>();
        for (CashClosureExchangeInvoice invoice
                : repository.findMonthlyExchangeInvoices(workshopId, from, to, scope)) {
            invoices.add(new ClosureExchangeInvoiceDTO(
                    invoice.getSaleId(),
                    invoice.getDocumentCode(),
                    invoice.getCustomerName(),
                    invoice.getIssuedAt(),
                    invoice.getAmount(),
                    invoice.isCollected(),
                    invoice.getPaidAt(),
                    invoice.getPaymentMethod()));
        }
        return List.copyOf(invoices);
    }

    /**
     * Los cobros del mes por forma de pago, completando con cero las que no
     * tengan fila para que la tabla salga siempre entera.
     */
    private CashClosureCollectionsDTO monthlyCollections(
            UUID workshopId, LocalDate from, LocalDate to) {

        Map<PaymentMethod, CashClosurePaymentMethodProjection> byMethod =
                new EnumMap<>(PaymentMethod.class);
        for (CashClosurePaymentMethodProjection row
                : repository.findMonthlyPaymentMethods(workshopId, from, to)) {
            byMethod.put(PaymentMethod.valueOf(row.getPaymentMethod()), row);
        }

        List<ClosurePaymentMethodDTO> methods = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (PaymentMethod method : PaymentMethod.values()) {
            CashClosurePaymentMethodProjection row = byMethod.get(method);
            BigDecimal amount = row == null
                    ? money(BigDecimal.ZERO) : money(row.getAmount());
            methods.add(new ClosurePaymentMethodDTO(
                    method, row == null ? 0L : row.getPaymentCount(), amount));
            total = total.add(amount);
        }

        return new CashClosureCollectionsDTO(List.copyOf(methods), money(total));
    }

    private static List<ClosurePaymentMethodDTO> zeroMethods() {
        List<ClosurePaymentMethodDTO> methods = new ArrayList<>();
        for (PaymentMethod method : PaymentMethod.values()) {
            methods.add(new ClosurePaymentMethodDTO(
                    method, 0L, BigDecimal.ZERO.setScale(2)));
        }
        return List.copyOf(methods);
    }

    /**
     * El mes pedido, validado. Un mes fuera de rango se rechaza con un error de
     * negocio en vez de devolver una serie absurda.
     */
    private YearMonth parseMonth(int year, int month) {
        if (month < 1 || month > 12) {
            throw new ValidationException("El mes debe estar entre 1 y 12");
        }
        int currentYear = businessDate().getYear();
        if (year < MIN_YEAR || year > currentYear + 1) {
            throw new ValidationException("El ano solicitado esta fuera de rango");
        }
        return YearMonth.of(year, month);
    }

    /** Por debajo de esto el texto no es un ano de negocio. */
    private static final int MIN_YEAR = 2000;

    private static LocalDate min(LocalDate left, LocalDate right) {
        return left.isBefore(right) ? left : right;
    }

    // ── Exportacion ──────────────────────────────────────────────

    @Override
    public CashClosureDocumentDTO document(UUID id) {
        CashClosure closure = findOwnedClosure(id);
        // El PDF historico se dibuja desde el snapshot. El calculador no
        // interviene: recalcularlo contra `sale` haria que el mismo documento
        // dijera cosas distintas segun el dia en que se descargue.
        CashClosureResponseDTO snapshot = mapper.toResponse(closure);

        byte[] content = reportService.renderDaily(
                snapshot,
                headerOf(closure.getWorkshop()),
                false,
                snapshot.closedBy());

        return new CashClosureDocumentDTO(
                "cierre-caja-" + snapshot.businessDate() + ".pdf", content);
    }

    // ── Apoyo ────────────────────────────────────────────────────

    /**
     * Toma el cerrojo del taller y devuelve la fecha operativa.
     *
     * Bloquear la fila del taller es lo que serializa cierre y venta: las dos
     * operaciones piden el mismo cerrojo, asi que una espera a que la otra
     * confirme en vez de decidir sobre un estado que ya cambio.
     */
    private LocalDate lockBusinessDate(UUID workshopId) {
        workshopRepository.lockById(workshopId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el taller autenticado"));
        return businessDate();
    }

    private CashClosure findOwnedClosure(UUID id) {
        return repository
                .findByIdAndWorkshopId(id, SecurityUtils.getWorkshopId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el cierre de caja especificado"));
    }

    /** La fecha operativa del servidor. El navegador nunca decide el dia. */
    private LocalDate businessDate() {
        return LocalDate.now(clock);
    }

    private ReportHeaderDTO headerOf(Workshop workshop) {
        return new ReportHeaderDTO(workshop.getName(), workshop.getAddress());
    }

    private static BigDecimal money(BigDecimal amount) {
        return CashClosureCalculator.money(amount);
    }

}
