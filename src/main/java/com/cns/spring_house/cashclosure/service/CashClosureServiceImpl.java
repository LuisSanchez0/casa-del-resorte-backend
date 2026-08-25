package com.cns.spring_house.cashclosure.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cns.spring_house.cashclosure.dto.CashClosureBalanceSummaryDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureBasePreviewResponseDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureCollectionsDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureDaySummaryDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureDocumentDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureInvoicingDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureRangeReceivablesDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureResponseDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureSummaryResponseDTO;
import com.cns.spring_house.cashclosure.dto.CreateCashClosureRequestDTO;
import com.cns.spring_house.cashclosure.entity.CashClosure;
import com.cns.spring_house.cashclosure.mapper.CashClosureMapper;
import com.cns.spring_house.cashclosure.report.CashClosureReportService;
import com.cns.spring_house.cashclosure.report.ReportHeaderDTO;
import com.cns.spring_house.cashclosure.repository.CashClosureRepository;
import com.cns.spring_house.cashclosure.repository.projection.CashClosureSummaryProjection;
import com.cns.spring_house.employee.entity.Employee;
import com.cns.spring_house.shared.exception.EntityNotFoundException;
import com.cns.spring_house.shared.exception.ValidationException;
import com.cns.spring_house.shared.security.CurrentUserService;
import com.cns.spring_house.shared.security.SecurityUtils;
import com.cns.spring_house.workshop.entity.Workshop;
import com.cns.spring_house.workshop.repository.WorkshopRepository;

import lombok.RequiredArgsConstructor;

/**
 * Cierre de caja: previsualizacion dinamica, confirmacion del snapshot y
 * lecturas historicas.
 *
 * Dos invariantes gobiernan la clase entera:
 *
 * 1. El taller SIEMPRE sale de {@code SecurityUtils.getWorkshopId()}. Ningun
 *    metodo lo acepta como parametro, ni siquiera los de exportacion, y ninguna
 *    lectura historica usa {@code findById} a secas.
 *
 * 2. Mientras no existe cierre, `sale` es la fuente de verdad y todo se calcula
 *    al vuelo; en cuanto existe, el snapshot lo es y NADA vuelve a recalcularse.
 *    Por eso {@link #get}, {@link #getByDate}, {@link #getRange},
 *    {@link #getSummary} y los PDF historicos no tienen acceso al calculador.
 *
 * Implementa tambien {@link CashClosureGuard} porque el bloqueo de ventas
 * necesita exactamente la misma comprobacion —cerrojo mas existencia del
 * cierre— que hace {@link #close}: separarla en otra clase habria significado
 * escribirla dos veces.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CashClosureServiceImpl implements CashClosureService, CashClosureGuard {

    /**
     * Un rango mas largo que un ano deja de ser un reporte y se vuelve una
     * descarga del historico completo. El limite es del reporte, no del modelo:
     * los cierres siguen ahi y se consultan por tramos.
     */
    static final int MAX_RANGE_DAYS = 366;

    private final CashClosureRepository repository;
    private final CashClosureCalculator calculator;
    private final CashClosureMapper mapper;
    private final CashClosureReportService reportService;
    private final CurrentUserService currentUserService;
    private final WorkshopRepository workshopRepository;
    private final Clock clock;

    // ── Previsualizacion: calcula, no persiste ───────────────────

    @Override
    public CashClosureBasePreviewResponseDTO getPreview() {
        UUID workshopId = SecurityUtils.getWorkshopId();
        LocalDate businessDate = businessDate();

        DailyCashFigures figures = calculator.calculate(workshopId, businessDate);
        CashClosure existing = repository
                .findByWorkshopIdAndBusinessDate(workshopId, businessDate)
                .orElse(null);

        return mapper.toBasePreview(figures, existing);
    }

    /**
     * Previsualizacion final. Vuelve a calcular las cifras del dia y les aplica
     * el arqueo declarado, pero no escribe una sola fila: consultar la
     * previsualizacion nunca cierra la caja ni bloquea ventas nuevas.
     *
     * Si el dia YA estaba cerrado responde igual, con {@code alreadyClosed} en
     * true: sigue siendo informacion util —"asi se ve el dia ahora mismo"— y el
     * documento oficial es el snapshot, que se pide por su propia ruta.
     */
    @Override
    public CashClosureResponseDTO preview(CreateCashClosureRequestDTO requestDTO) {
        UUID workshopId = SecurityUtils.getWorkshopId();
        LocalDate businessDate = businessDate();

        DailyCashFigures figures = calculator.calculate(workshopId, businessDate);
        boolean alreadyClosed = repository
                .existsByWorkshopIdAndBusinessDate(workshopId, businessDate);

        return mapper.toPreview(
                figures,
                requestDTO.openingCash(),
                requestDTO.countedCash(),
                requestDTO.notes(),
                alreadyClosed);
    }

    @Override
    public CashClosureDocumentDTO previewDocument(CreateCashClosureRequestDTO requestDTO) {
        CashClosureResponseDTO preview = preview(requestDTO);
        Employee employee = currentUserService.getCurrentEmployee();

        byte[] content = reportService.renderDaily(
                preview,
                headerOf(employee.getWorkshop()),
                true,
                fullNameOf(employee));

        return new CashClosureDocumentDTO(
                "previsualizacion-cierre-caja-" + preview.businessDate() + ".pdf",
                content);
    }

    // ── Cierre definitivo ────────────────────────────────────────

    /**
     * Confirma el cierre del dia.
     *
     * Recalcula TODO en vez de confiar en lo que se previsualizo: entre ver la
     * pantalla y pulsar el boton pueden haberse emitido facturas o cobrado
     * cartera, y cerrar con cifras viejas dejaria un snapshot que no describe el
     * dia. Del cliente solo se aceptan el fondo inicial, el efectivo contado y
     * la nota.
     *
     * El cerrojo del taller se toma ANTES de comprobar la existencia del cierre
     * y no se suelta hasta el commit: es lo que impide que una venta se cuele
     * entre el calculo y la insercion.
     */
    @Override
    @Transactional
    public CashClosureResponseDTO close(CreateCashClosureRequestDTO requestDTO) {
        Employee employee = currentUserService.getCurrentEmployee();
        Workshop workshop = currentUserService.getCurrentWorkshop();
        UUID workshopId = SecurityUtils.getWorkshopId();
        LocalDate businessDate = lockBusinessDate(workshopId);

        if (repository.existsByWorkshopIdAndBusinessDate(workshopId, businessDate)) {
            throw new ValidationException(
                    "La caja de este taller ya fue cerrada para la fecha "
                            + businessDate);
        }

        DailyCashFigures figures = calculator.calculate(workshopId, businessDate);
        BigDecimal openingCash = requestDTO.openingCash();
        BigDecimal countedCash = requestDTO.countedCash();
        BigDecimal expectedCash = figures.expectedCash(openingCash);
        BigDecimal difference = countedCash.subtract(expectedCash);

        CashClosure closure = CashClosure.builder()
                .workshop(workshop)
                .closedBy(employee)
                .businessDate(businessDate)
                .openingCash(openingCash)
                .invoiceCount(figures.invoicing().invoiceCount())
                .invoiceTotal(figures.invoicing().invoiceTotal())
                .exchangeInvoiceCount(figures.invoicing().exchangeInvoiceCount())
                .exchangeInvoiceTotal(figures.invoicing().exchangeInvoiceTotal())
                .totalInvoiced(figures.invoicing().totalInvoiced())
                .cashPaymentCount(figures.collections().cashCount())
                .cashPayments(figures.collections().cashAmount())
                .cardPaymentCount(figures.collections().cardCount())
                .cardPayments(figures.collections().cardAmount())
                .transferPaymentCount(figures.collections().transferCount())
                .transferPayments(figures.collections().transferAmount())
                .totalCollected(figures.collections().totalCollected())
                .exchangeCollectedCount(figures.receivables().collectedCount())
                .exchangeCollectedAmount(figures.receivables().collectedAmount())
                .previousExchangeCollectedAmount(
                        figures.receivables().previousCollectedAmount())
                .pendingExchangeAmount(figures.receivables().pendingAmount())
                .expectedCash(expectedCash)
                .countedCash(countedCash)
                .difference(difference)
                .notes(requestDTO.notes())
                .build();

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

    // ── Bloqueo de ventas ────────────────────────────────────────

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
     * El mensaje habla de ventas Y de cobros porque este metodo protege las dos
     * cosas: cuando solo cubria {@code create}, decir "nuevas ventas" era
     * exacto; ahora tambien rechaza cobrar una cambiaria ya emitida, que no es
     * una venta nueva.
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

    @Override
    public List<CashClosureResponseDTO> getRange(LocalDate from, LocalDate to) {
        validateRange(from, to);
        return repository
                .findByWorkshopIdAndBusinessDateBetweenOrderByBusinessDateDescIdDesc(
                        SecurityUtils.getWorkshopId(), from, to)
                .stream()
                .map(mapper::toResponse)
                .toList();
    }

    /**
     * Agregado del rango sobre los snapshots. Sirve igual para un dia, una
     * semana, una quincena o un mes: un cierre mensual es este mismo calculo con
     * {@code from} y {@code to} en los extremos del mes, no otra entidad.
     */
    @Override
    public CashClosureSummaryResponseDTO getSummary(LocalDate from, LocalDate to) {
        validateRange(from, to);
        UUID workshopId = SecurityUtils.getWorkshopId();

        CashClosureSummaryProjection totals = repository.summarizeRange(workshopId, from, to);
        List<CashClosureDaySummaryDTO> days =
                repository.findDaySummaries(workshopId, from, to);

        return new CashClosureSummaryResponseDTO(
                from,
                to,
                totals.getClosureCount(),
                new CashClosureInvoicingDTO(
                        totals.getInvoiceCount(),
                        money(totals.getInvoiceTotal()),
                        totals.getExchangeInvoiceCount(),
                        money(totals.getExchangeInvoiceTotal()),
                        money(totals.getTotalInvoiced())),
                new CashClosureCollectionsDTO(
                        totals.getCashPaymentCount(),
                        money(totals.getCashPayments()),
                        totals.getCardPaymentCount(),
                        money(totals.getCardPayments()),
                        totals.getTransferPaymentCount(),
                        money(totals.getTransferPayments()),
                        money(totals.getTotalCollected())),
                new CashClosureRangeReceivablesDTO(
                        totals.getExchangeCollectedCount(),
                        money(totals.getExchangeCollectedAmount()),
                        endingPendingExchangeAmount(workshopId, from, to)),
                new CashClosureBalanceSummaryDTO(
                        totals.getBalancedClosures(),
                        totals.getShortClosures(),
                        totals.getOverClosures(),
                        money(totals.getNetCashDifference())),
                days);
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
                "cierre-caja-" + closure.getBusinessDate() + ".pdf", content);
    }

    @Override
    public CashClosureDocumentDTO summaryDocument(LocalDate from, LocalDate to) {
        CashClosureSummaryResponseDTO summary = getSummary(from, to);
        byte[] content = reportService.renderSummary(
                summary, headerOf(currentUserService.getCurrentWorkshop()));

        return new CashClosureDocumentDTO(
                "resumen-caja-" + from + "-a-" + to + ".pdf", content);
    }

    // ── Apoyo ────────────────────────────────────────────────────

    /**
     * La fecha de negocio del taller. Se calcula UNA vez por operacion: si cada
     * consulta llamara al reloj por su cuenta, una peticion servida justo en el
     * cambio de dia mezclaria dos fechas distintas.
     */
    private LocalDate businessDate() {
        return LocalDate.now(clock);
    }

    /**
     * Toma el cerrojo del taller y devuelve la fecha operativa YA bajo cerrojo.
     * El orden importa: leer el reloj despues de tener el cerrojo evita que dos
     * transacciones en el limite del dia trabajen sobre fechas distintas creyendo
     * que se estan serializando.
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

    /**
     * Saldo cambiario pendiente al final del rango: el del cierre mas reciente,
     * NUNCA la suma de los diarios. Cada `pendingExchangeAmount` es una foto del
     * saldo total en ese momento, asi que sumar tres dias con Q20,000, Q18,000 y
     * Q25,000 daria Q63,000 de deuda que no existe; la respuesta correcta es
     * Q25,000.
     */
    private BigDecimal endingPendingExchangeAmount(
            UUID workshopId, LocalDate from, LocalDate to) {
        List<BigDecimal> amounts = repository.findPendingExchangeAmounts(
                workshopId, from, to, PageRequest.of(0, 1));
        return amounts.isEmpty() ? money(BigDecimal.ZERO) : money(amounts.get(0));
    }

    private void validateRange(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new ValidationException(
                    "El rango de fechas es obligatorio: se requieren 'from' y 'to'");
        }
        if (from.isAfter(to)) {
            throw new ValidationException(
                    "La fecha inicial no puede ser posterior a la final");
        }
        if (from.plusDays(MAX_RANGE_DAYS - 1L).isBefore(to)) {
            throw new ValidationException(
                    "El rango no puede superar los " + MAX_RANGE_DAYS + " dias");
        }
    }

    private ReportHeaderDTO headerOf(Workshop workshop) {
        return new ReportHeaderDTO(workshop.getName(), workshop.getAddress());
    }

    private String fullNameOf(Employee employee) {
        String firstName = employee.getFirstName() == null ? "" : employee.getFirstName();
        String lastName = employee.getLastName() == null ? "" : employee.getLastName();
        String fullName = (firstName + " " + lastName).trim();
        return fullName.isEmpty() ? null : fullName;
    }

    private static BigDecimal money(BigDecimal amount) {
        return CashClosureCalculator.money(amount);
    }

}
