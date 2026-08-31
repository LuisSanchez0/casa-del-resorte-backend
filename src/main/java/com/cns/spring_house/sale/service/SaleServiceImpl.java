package com.cns.spring_house.sale.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cns.spring_house.cashclosure.service.CashClosureGuard;
import com.cns.spring_house.customer.entity.Customer;
import com.cns.spring_house.customer.repository.CustomerRepository;
import com.cns.spring_house.employee.entity.Employee;
import com.cns.spring_house.family.entity.FamilyCategory;
import com.cns.spring_house.product.entity.Product;
import com.cns.spring_house.product.repository.ProductRepository;
import com.cns.spring_house.sale.dto.AddSaleDetailRequestDTO;
import com.cns.spring_house.sale.dto.CancelSaleRequestDTO;
import com.cns.spring_house.sale.dto.CompleteSaleRequestDTO;
import com.cns.spring_house.sale.dto.CreateSaleRequestDTO;
import com.cns.spring_house.sale.dto.ExchangeInvoiceSummaryDTO;
import com.cns.spring_house.sale.dto.RegisterPaymentRequestDTO;
import com.cns.spring_house.refund.entity.SaleRefund;
import com.cns.spring_house.refund.entity.SaleRefundType;
import com.cns.spring_house.refund.repository.SaleRefundRepository;
import com.cns.spring_house.sale.dto.RefundRequestDTO;
import com.cns.spring_house.sale.dto.ReverseSaleRequestDTO;
import com.cns.spring_house.sale.dto.SaleResponseDTO;
import com.cns.spring_house.sale.dto.SaleSummaryDTO;
import com.cns.spring_house.sale.dto.SearchSaleRequestDTO;
import com.cns.spring_house.sale.entity.InvoiceType;
import com.cns.spring_house.sale.entity.PaymentStatus;
import com.cns.spring_house.sale.entity.Sale;
import com.cns.spring_house.sale.entity.SaleAction;
import com.cns.spring_house.sale.entity.SaleActionType;
import com.cns.spring_house.sale.entity.SaleDetail;
import com.cns.spring_house.sale.entity.SaleStatus;
import com.cns.spring_house.sale.handler.SaleDetailHandler;
import com.cns.spring_house.sale.handler.SaleDetailHandlerRegistry;
import com.cns.spring_house.sale.mapper.SaleMapper;
import com.cns.spring_house.sale.repository.SaleDetailRepository;
import com.cns.spring_house.sale.repository.SaleRepository;
import com.cns.spring_house.sale.repository.SaleSpecs;
import com.cns.spring_house.shared.exception.EntityNotFoundException;
import com.cns.spring_house.shared.exception.ValidationException;
import com.cns.spring_house.shared.security.CurrentUserService;
import com.cns.spring_house.shared.security.SecurityUtils;
import com.cns.spring_house.shared.util.LikePatterns;
import com.cns.spring_house.workresult.service.WorkResultService;
import com.cns.spring_house.workshop.entity.Workshop;

import lombok.RequiredArgsConstructor;

@Service
@Transactional
@RequiredArgsConstructor
public class SaleServiceImpl implements SaleService {

    /**
     * La cartera se lee de lo mas reciente a lo mas antiguo por fecha de emision,
     * y el ORDER BY lo resuelve la base de datos.
     *
     * El desempate por id no es decorativo: sin un segundo criterio, dos
     * documentos emitidos en el mismo instante pueden salir en distinto orden en
     * cada consulta, y con paginacion eso significa una fila repetida en una
     * pagina y ausente en la siguiente.
     */
    private static final Sort MOST_RECENTLY_ISSUED = Sort.by(
            Sort.Order.desc("completedAt"),
            Sort.Order.desc("id"));

    /** Un tamaño mayor deja de ser una pagina y vuelve a ser la tabla entera. */
    private static final int MAX_PAGE_SIZE = 100;

    /**
     * Sugerencias del buscador de facturas. Una lista desplegable con la que se
     * elige de un vistazo no mejora por ser mas larga: por encima de esto deja
     * de ser un buscador y vuelve a ser un listado.
     */
    private static final int DEFAULT_SUGGESTIONS = 8;

    private static final int MAX_SUGGESTIONS = 10;

    private final SaleRepository repository;
    private final SaleMapper mapper;
    private final SaleDetailHandlerRegistry registry;
    private final CurrentUserService currentUserService;
    private final CustomerRepository customerRepository;
    private final ProductRepository productRepository;
    private final SaleDetailRepository detailRepository;
    private final WorkResultService workResultService;
    /**
     * Responde si la caja del dia sigue abierta. Se depende de la interfaz
     * estrecha y no del servicio de cierres completo, que ademas traeria
     * previsualizaciones, rangos y PDF.
     *
     * Lo consultan las TRES operaciones que producen actividad financiera
     * —abrir una venta, emitir el documento y cobrar una cambiaria—, siempre
     * con la misma llamada: la pregunta "esta abierta la caja de este taller"
     * tiene un unico sitio donde se responde.
     */
    private final CashClosureGuard cashClosureGuard;
    private final SaleRefundRepository refundRepository;
    /**
     * Fuente unica de "ahora" para los sellos financieros de la venta.
     *
     * `completedAt` y `paidAt` se escribian con un now() desnudo, que resuelve
     * la hora contra la zona por defecto de la JVM. Coincidia con el reloj del
     * cierre solo porque {@code BusinessTimeConfigurer} fija esa zona al
     * arrancar, y aun asi eran dos lecturas distintas del tiempo: en la frontera
     * del dia, el guard podia validar la fecha operativa D y el sello escribirse
     * ya en D+1. Con el reloj inyectado, la fecha que el guard comprueba y la
     * que queda estampada en la venta salen de la misma fuente —la misma que
     * decide {@code CashClosure.businessDate}— y ademas se pueden fijar en las
     * pruebas.
     */
    private final Clock clock;

    /**
     * `requestDTO.workshop()` se ignora deliberadamente: el alcance sale del token
     * para que un query param no pueda listar las ventas de otro taller.
     */
    @Override
    public List<SaleResponseDTO> getAll(SearchSaleRequestDTO requestDTO) {
        Specification<Sale> specs = Specification.allOf(
                Specification.anyOf(
                        SaleSpecs.nameContains(requestDTO.customer()),
                        SaleSpecs.nitContains(requestDTO.customer())),
                SaleSpecs.hasCustomer(requestDTO.customerId()),
                SaleSpecs.hasStatus(requestDTO.status()),
                SaleSpecs.hasWorkshop(SecurityUtils.getWorkshopId()),
                SaleSpecs.hasEmployee(requestDTO.employee()),
                SaleSpecs.plateContains(requestDTO.plate()),
                SaleSpecs.hasInvoiceType(requestDTO.invoiceType()),
                SaleSpecs.hasPaymentStatus(requestDTO.paymentStatus()),
                SaleSpecs.totalBetween(requestDTO.minTotal(), requestDTO.maxTotal()),
                SaleSpecs.completedAtBetween(requestDTO.minDate(), requestDTO.maxDate()));
        List<SaleResponseDTO> sales = repository.findAll(specs).stream()
                .map(mapper::toResponse)
                .toList();
        return sales;
    }

    @Override
    public SaleResponseDTO get(UUID id) {
        return mapper.toResponse(findOwnedSale(id));
    }

    /**
     * Abre una venta nueva.
     *
     * Antes de crear nada se comprueba que la caja del dia siga abierta: una vez
     * que el taller confirma su cierre, ese dia queda contablemente cerrado y
     * admitir una venta mas dejaria una factura fuera del snapshot que alguien
     * ya firmo. La comprobacion vive aqui, en el servidor, y no en si el boton
     * de Angular esta habilitado.
     *
     * El bloqueo es por TALLER y FECHA OPERATIVA: el cierre de un taller no
     * afecta a otro, y el del dia anterior no impide vender hoy. Ni consultar la
     * previsualizacion ni descargar su PDF bloquean nada; solo la existencia de
     * un cierre confirmado.
     */
    @Override
    public SaleResponseDTO create(CreateSaleRequestDTO requestDTO) {
        // El subject del JWT es UserAccount.id: el empleado se resuelve por la
        // relación de la cuenta, y el taller sale de ese empleado.
        Employee employee = currentUserService.getCurrentEmployee();
        Workshop workshop = currentUserService.getCurrentWorkshop();
        cashClosureGuard.ensureCashRegisterIsOpen(workshop.getId());
        Customer customer = customerRepository.findById(requestDTO.customer())
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el cliente especificado"));
        Sale sale = Sale.builder()
                .workshop(workshop)
                .employee(employee)
                .customer(customer)
                .plate(requestDTO.plate())
                .build();
        sale = repository.save(sale);
        return mapper.toResponse(sale);
    }

    @Override
    public SaleResponseDTO addDetail(UUID id, AddSaleDetailRequestDTO requestDTO) {
        Sale sale = findOpenSale(id);
        Product product = productRepository.findById(requestDTO.product())
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el producto especificado"));
        FamilyCategory category = product.getFamily().getCategory();
        SaleDetailHandler handler = registry.get(category);
        SaleDetail detail = handler.addDetail(sale, requestDTO);
        if (requestDTO.workResult() != null) {
            reserveWorkResult(detail, requestDTO.workResult());
        }
        sale = repository.save(sale);
        return mapper.toResponse(sale);
    }

    @Override
    public SaleResponseDTO removeDetail(UUID saleDetailId) {
        SaleDetail saleDetail = detailRepository
                .findByIdAndSaleWorkshopId(
                        saleDetailId, SecurityUtils.getWorkshopId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el detalle especificado"));
        Sale sale = saleDetail.getSale();
        if (sale.getStatus() != SaleStatus.PENDING) {
            throw new ValidationException("La venta no se encuentra abierta");
        }
        removeDetail(sale, saleDetail);
        sale = repository.save(sale);
        return mapper.toResponse(sale);
    }

    /**
     * Emite el documento y fija de una vez el estado comercial y el de cobro. Una
     * factura normal se cobra en el acto; una cambiaria queda cobrada mas tarde
     * pero deja de ser editable igual, porque ya fue emitida.
     */
    @Override
    public SaleResponseDTO completeSale(UUID id, CompleteSaleRequestDTO requestDTO) {
        // La caja se comprueba ANTES que cualquier otra regla, antes de tocar un
        // solo campo y —esto es lo importante— antes de LEER la venta. Emitir el
        // documento es lo que convierte un borrador en facturacion —y, si es
        // factura normal, tambien en cobro—, asi que hacerlo con la caja cerrada
        // anadiria dinero a un dia cuyo snapshot ya esta firmado y que el
        // UNIQUE(workshop, business_date) impide volver a cerrar.
        //
        // El cerrojo del taller que deja tomado solo serializa DESDE que se
        // adquiere: leer la venta antes dejaba entrar a la seccion critica con
        // una entidad ya cargada. Dos emisiones simultaneas leian las dos
        // PENDING, la segunda esperaba el cerrojo y, al despertar, seguia
        // decidiendo sobre su copia en memoria —Hibernate no la relee por haber
        // esperado un cerrojo de OTRA entidad—, volvia a emitir el documento y
        // pisaba metodo de pago y fecha del primero.
        cashClosureGuard.ensureCashRegisterIsOpen(SecurityUtils.getWorkshopId());
        // Primera lectura de la venta dentro de la seccion critica: es la unica
        // que puede decidir si sigue abierta.
        Sale sale = findOpenSale(id);
        // Un documento sin lineas no es facturable: emitirlo dejaba una venta
        // COMPLETED de total cero, ya no editable, que solo se podia descartar
        // borrandola a mano en la base.
        if (sale.getDetails().isEmpty()) {
            throw new ValidationException(
                    "No se puede completar una venta sin detalles");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        sale.setInvoiceType(requestDTO.invoiceType());
        sale.setCompletedAt(now);
        sale.setStatus(SaleStatus.COMPLETED);
        if (requestDTO.invoiceType() == InvoiceType.INVOICE) {
            if (requestDTO.paymentMethod() == null) {
                throw new ValidationException(
                        "El metodo de pago es obligatorio para una factura normal");
            }
            sale.setPaymentMethod(requestDTO.paymentMethod());
            sale.setPaymentStatus(PaymentStatus.PAID);
            sale.setPaidAt(now);
        } else {
            if (requestDTO.paymentMethod() != null) {
                throw new ValidationException(
                        "Una factura cambiaria se emite sin metodo de pago");
            }
            sale.setPaymentStatus(PaymentStatus.PENDING);
        }
        // El trabajo ya se vendio aunque el cobro siga pendiente: la deuda vive en
        // paymentStatus, no en WorkResultStatus.
        sale.getDetails().forEach(this::markWorkResultAsSold);
        sale = repository.save(sale);
        return mapper.toResponse(sale);
    }

    /**
     * Cartera cambiaria paginada: los documentos emitidos a credito del taller del
     * token, opcionalmente acotados a lo que sigue pendiente o a lo ya cobrado.
     *
     * Devuelve un resumen y no la venta entera porque la cartera es historica y
     * crece sin limite: ver {@link SaleRepository#findExchangeInvoiceSummaries}.
     * El detalle completo se pide al abrir un documento concreto.
     */
    @Override
    public Page<ExchangeInvoiceSummaryDTO> getExchangeInvoices(
            PaymentStatus paymentStatus, int page, int size) {
        return repository.findExchangeInvoiceSummaries(
                SecurityUtils.getWorkshopId(),
                paymentStatus,
                pageOf(page, size));
    }

    /**
     * Buscador de facturas ya emitidas, acotado en el propio SQL.
     *
     * El taller sale del token, nunca de la peticion, igual que en el resto de
     * lecturas de venta. El estado no es un filtro opcional del cliente: la
     * consulta fija COMPLETED porque es la unica venta que la bitacora puede
     * registrar, y dejar que el cliente lo eligiera permitiria sugerir una venta
     * anulada.
     *
     * El comodin se arma en {@link LikePatterns} y no en la consulta: `%` forma
     * parte del VALOR del parametro, y meterlo en el JPQL obligaria a concatenar
     * en la sentencia. Ese mismo helper neutraliza los comodines que escriba la
     * persona, para que buscar "%" no devuelva el taller entero.
     */
    @Override
    public List<SaleSummaryDTO> searchCompletedSales(String query, Integer limit) {
        int size = limit == null ? DEFAULT_SUGGESTIONS : limit;
        if (size < 1 || size > MAX_SUGGESTIONS) {
            throw new ValidationException(
                    "El numero de sugerencias debe estar entre 1 y " + MAX_SUGGESTIONS);
        }

        String pattern = query == null || query.isBlank()
                ? null
                : LikePatterns.containsIgnoreCase(query);

        return repository.findCompletedSaleSummaries(
                SecurityUtils.getWorkshopId(),
                pattern,
                PageRequest.of(0, size, MOST_RECENTLY_ISSUED));
    }

    /**
     * Una pagina fuera de rango o de tamaño absurdo anula la paginacion, asi que
     * se rechaza en vez de servirla. El orden no es negociable por el cliente: la
     * cartera se lee siempre de lo mas reciente a lo mas antiguo.
     */
    private Pageable pageOf(int page, int size) {
        if (page < 0) {
            throw new ValidationException(
                    "El numero de pagina no puede ser negativo");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new ValidationException(
                    "El tamaño de pagina debe estar entre 1 y " + MAX_PAGE_SIZE);
        }
        return PageRequest.of(page, size, MOST_RECENTLY_ISSUED);
    }

    /**
     * Cobro posterior de una cambiaria. Solo mueve la dimension de pago: el stock
     * y los WorkResult ya se consumieron al emitir el documento, y `completedAt`
     * conserva la fecha de emision porque emitir y cobrar son eventos distintos.
     */
    @Override
    public SaleResponseDTO registerPayment(
            UUID id, RegisterPaymentRequestDTO requestDTO) {
        // Igual que al emitir: cobrar es meter dinero en la caja del dia, asi
        // que si esa caja ya se cerro la operacion se rechaza antes de tocar
        // `paymentMethod`, `paymentStatus` o `paidAt`. El documento no se
        // pierde: sigue en cartera y se cobra en la siguiente fecha operativa,
        // que es donde el dinero realmente entra.
        //
        // Y por el mismo motivo que en `completeSale`, el guard va antes de LEER
        // la venta: leerla fuera del cerrojo permitia que dos cobros la vieran
        // los dos PENDING y que el segundo cobrara una cambiaria ya cobrada,
        // sustituyendo metodo de pago y `paidAt` del primero.
        cashClosureGuard.ensureCashRegisterIsOpen(SecurityUtils.getWorkshopId());
        Sale sale = findOwnedSale(id);
        if (sale.getStatus() != SaleStatus.COMPLETED) {
            throw new ValidationException(
                    "Solo se puede registrar el pago de una venta finalizada");
        }
        // Una factura normal se cobra en el acto de emitirla: si llega aqui es que
        // el cliente confundio el flujo, no que quede algo por cobrar.
        if (sale.getInvoiceType() != InvoiceType.EXCHANGE_INVOICE) {
            throw new ValidationException(
                    "Solo las facturas cambiarias pueden registrar un pago posterior");
        }
        if (sale.getPaymentStatus() == PaymentStatus.PAID) {
            throw new ValidationException("La venta ya fue pagada");
        }
        sale.setPaymentMethod(requestDTO.paymentMethod());
        sale.setPaymentStatus(PaymentStatus.PAID);
        sale.setPaidAt(LocalDateTime.now(clock));
        sale = repository.save(sale);
        return mapper.toResponse(sale);
    }

    /**
     * Anula una venta abierta conservandola como historial: devuelve todo lo que
     * tenia reservado y la deja en CANCELLED, pero la Sale y sus lineas siguen
     * existiendo con su cliente, su empleado, su placa y su total.
     *
     * Es la diferencia con {@link #delete}: borrar hace desaparecer un borrador
     * que no interesa; cancelar deja constancia de que esa venta existio y se
     * anuló. Una venta ya emitida no se cancela cambiando el enum: eso exige un
     * flujo fiscal (nota de credito) que no forma parte de esta operacion, y
     * para eso existe {@link #reverse}.
     *
     * El motivo ya no es opcional: sin el, el historial no puede explicar por
     * que se anulo, y queda persistido en {@link SaleAction}, no solo en la
     * peticion.
     */
    @Override
    public SaleResponseDTO cancel(UUID id, CancelSaleRequestDTO requestDTO) {
        Sale sale = findOwnedSale(id);
        // El caso ya cancelado se responde antes para no confundirlo con intentar
        // cancelar una venta emitida: son dos errores distintos.
        if (sale.getStatus() == SaleStatus.CANCELLED) {
            throw new ValidationException("La venta ya se encuentra cancelada");
        }
        if (sale.getStatus() != SaleStatus.PENDING) {
            throw new ValidationException(
                    "Solo se puede cancelar una venta abierta");
        }
        sale.getDetails().forEach(this::releaseReservation);
        sale.setStatus(SaleStatus.CANCELLED);
        recordAction(sale, SaleActionType.CANCEL_PENDING, requestDTO.reason());
        sale = repository.save(sale);
        return mapper.toResponse(sale);
    }

    /**
     * Revierte una venta ya COMPLETED: es al historial emitido lo que
     * {@link #cancel} es al borrador abierto, pero con un origen distinto —
     * aqui ya se consumio inventario, se marcaron WorkResult como vendidos y
     * (en una factura normal) ya se cobro— asi que reutiliza los mismos
     * handlers de restauracion, no el flujo de cancelacion.
     *
     * SIGUE sin tocar `paymentStatus`, `paymentMethod` ni `paidAt`. Revertir no
     * anula legalmente un documento ya emitido, y la venta conserva la verdad
     * de que se cobro. Lo que ahora SI se registra, cuando lo hubo, es el hecho
     * separado de que el dinero volvio al cliente: {@link SaleRefund}, con su
     * fecha propia.
     *
     * La devolucion NO se deduce del estado. Se puede revertir sin devolver
     * —el cliente se lleva otra cosa, o se le abona despues— asi que solo
     * existe si la peticion la trae.
     *
     * ORDEN DE LAS VALIDACIONES: todo lo que puede rechazar la operacion se
     * comprueba ANTES de tocar nada. Si la caja del dia ya esta cerrada y se
     * pedia devolver dinero, la peticion entera se rechaza sin haber restaurado
     * existencias; lo contrario dejaria la venta revertida, el stock devuelto y
     * la devolucion sin registrar, que es el peor de los estados posibles.
     */
    @Override
    public SaleResponseDTO reverse(UUID id, ReverseSaleRequestDTO requestDTO) {
        // Devolver dinero mueve la caja, asi que el cerrojo del dia se toma
        // ANTES de leer el estado de la venta. Si se leyera primero, dos
        // devoluciones simultaneas decidirian las dos sobre la misma foto
        // "todavia COMPLETED" y la segunda chocaria contra un indice unico en
        // vez de recibir un error de negocio legible.
        if (requestDTO.hasRefund()) {
            cashClosureGuard.ensureCashRegisterIsOpen(SecurityUtils.getWorkshopId());
        }

        Sale sale = findOwnedSale(id);
        if (sale.getStatus() == SaleStatus.PENDING) {
            throw new ValidationException(
                    "Una venta abierta se anula, no se revierte");
        }
        if (sale.getStatus() == SaleStatus.CANCELLED) {
            throw new ValidationException(
                    "Una venta anulada no se puede revertir");
        }
        if (sale.getStatus() == SaleStatus.REVERSED) {
            throw new ValidationException("La venta ya fue revertida");
        }

        SaleRefund refund = requestDTO.hasRefund()
                ? prepareRefund(sale, requestDTO)
                : null;

        sale.getDetails().forEach(this::restoreDetailForReversal);
        sale.setStatus(SaleStatus.REVERSED);
        recordAction(sale, SaleActionType.REVERSE_COMPLETED, requestDTO.reason());
        sale = repository.save(sale);

        if (refund != null) {
            refundRepository.save(refund);
        }

        return mapper.toResponse(sale);
    }

    /**
     * Valida y construye la devolucion SIN persistirla todavia.
     *
     * Se separa para que todas sus negativas ocurran antes de la primera
     * mutacion. Las cuatro razones por las que una devolucion se rechaza:
     *
     *   1. no hubo dinero que devolver  (la venta nunca se cobro)
     *   2. se devuelve mas de lo cobrado
     *   3. la venta ya tiene devolucion
     *   4. la caja del dia ya esta cerrada
     */
    private SaleRefund prepareRefund(Sale sale, ReverseSaleRequestDTO requestDTO) {
        RefundRequestDTO requested = requestDTO.refund();

        // 1. Sin cobro no hay devolucion posible. Es el caso de la cambiaria
        // emitida y aun pendiente: se facturo a credito, nunca entro dinero, y
        // "devolver" algo que no se recibio no significa nada. Se mira el
        // ESTADO DE PAGO, no el tipo de documento.
        if (sale.getPaymentStatus() != PaymentStatus.PAID) {
            throw new ValidationException(
                    "No se puede registrar una devolucion de una venta que no fue cobrada");
        }

        // 2. Lo devuelto no puede superar lo cobrado. El importe puede ser
        // MENOR —una devolucion parcial es legitima— pero nunca mayor.
        BigDecimal collected = sale.getTotal();
        if (requested.amount().compareTo(collected) > 0) {
            throw new ValidationException(
                    "El monto devuelto no puede superar lo cobrado por la venta");
        }

        // 3. Una devolucion por venta. La base lo garantiza con un UNIQUE; esto
        // solo convierte la violacion en un error legible en el caso normal.
        if (refundRepository.existsBySaleId(sale.getId())) {
            throw new ValidationException("La venta ya tiene una devolucion registrada");
        }

        // Ya NO se exige que el dia del cobro este cerrado.
        //
        // Esa restriccion existia para tapar un defecto que estaba en otro
        // sitio: el cierre borraba el cobro de una venta revertida, asi que una
        // devolucion el mismo dia restaba una salida cuyo ingreso habia
        // desaparecido. Arreglado el cobro —que ahora sobrevive a la reversion,
        // porque el dinero entro cuando entro— el mismo dia cuadra solo:
        // entraron Q1,000, salieron Q400, quedan Q600. Prohibir la devolucion
        // era esconder la mitad de un movimiento real.
        //
        // Lo que SI se mantiene es el cierre confirmado: eso lo comprueba
        // `ensureCashRegisterIsOpen` al principio de `reverse`.

        return SaleRefund.builder()
                .workshop(sale.getWorkshop())
                .sale(sale)
                .type(SaleRefundType.REFUND)
                .amount(requested.amount())
                .effectiveAt(LocalDateTime.now(clock))
                .paymentMethod(requested.paymentMethod())
                .reason(requestDTO.reason())
                .performedBy(currentUserService.getCurrentEmployee())
                .build();
    }

    /**
     * Inverso exacto de lo que {@code completeSale} hizo con este detalle:
     * devuelve al inventario lo que el handler de su categoria reservo, y si
     * llevaba un WorkResult vendido lo regresa a PENDING antes de soltar el
     * puntero.
     *
     * Nulear `workResult` es obligatorio y no solo higiene: el indice
     * `uk_sale_detail_work_result` es unico por WorkResult entre TODOS los
     * SaleDetail, asi que mientras esta linea historica siga apuntandolo, ese
     * trabajo nunca podria venderse de nuevo aunque su estado ya diga PENDING.
     */
    private void restoreDetailForReversal(SaleDetail detail) {
        FamilyCategory category = detail.getProduct().getFamily().getCategory();
        registry.get(category).restore(detail);
        if (detail.getWorkResult() != null) {
            workResultService.restoreToPending(detail.getWorkResult().getId());
            detail.setWorkResult(null);
        }
    }

    /**
     * Unico punto de escritura de {@link SaleAction}: registra motivo, autor y
     * tipo de accion, y lo enlaza a la venta para que se persista en cascada
     * con ella.
     */
    private void recordAction(Sale sale, SaleActionType type, String reason) {
        SaleAction action = SaleAction.builder()
                .sale(sale)
                .actionType(type)
                .reason(reason)
                .performedBy(currentUserService.getCurrentEmployee())
                .build();
        sale.setAction(action);
    }

    /**
     * Suelta lo que el detalle tenia tomado, dejando la linea en su sitio.
     *
     * El WorkResult se desvincula porque en este modelo la reserva ES el detalle
     * que lo apunta: `existsByWorkResultId` y el indice uk_sale_detail_work_result
     * impiden que un segundo detalle lo tome, y la consulta de trabajos
     * disponibles excluye cualquiera referenciado por un SaleDetail. Conservar el
     * puntero en una venta cancelada dejaria ese trabajo inutilizable para
     * siempre por una venta que nunca ocurrio.
     *
     * Se pierde saber que trabajo concreto llevaba una linea cancelada; lo demas
     * del snapshot (producto, cantidad, precio, subtotal, descripcion fiscal) se
     * conserva intacto.
     */
    private void releaseReservation(SaleDetail detail) {
        FamilyCategory category = detail.getProduct().getFamily().getCategory();
        registry.get(category).restore(detail);
        detail.setWorkResult(null);
    }

    private void markWorkResultAsSold(SaleDetail detail) {
        if (detail.getWorkResult() == null) {
            return;
        }
        workResultService.markAsSold(
                detail.getWorkResult().getId(),
                detail.getProduct().getId(),
                resolveItemId(detail));
    }

    private UUID resolveItemId(SaleDetail detail) {
        return detail.getItem() != null ? detail.getItem().getId() : null;
    }

    /**
     * Solo se elimina una venta abierta, y devolviendo lo que reservo: el borrado
     * directo dejaba el stock descontado y los items en SOLD para siempre. Una
     * venta finalizada es historial y no se borra.
     */
    @Override
    public void delete(UUID id) {
        Sale sale = findOwnedSale(id);
        if (sale.getStatus() != SaleStatus.PENDING) {
            throw new ValidationException(
                    "Solo se puede eliminar una venta abierta");
        }
        List.copyOf(sale.getDetails())
                .forEach(detail -> removeDetail(sale, detail));
        repository.delete(sale);
    }

    /** Devuelve al inventario lo que el detalle habia reservado. */
    private void removeDetail(Sale sale, SaleDetail saleDetail) {
        FamilyCategory category = saleDetail.getProduct().getFamily().getCategory();
        registry.get(category).removeDetail(sale, saleDetail);
    }

    /**
     * Un WorkResult solo puede estar reservado en un detalle: sin esta
     * comprobacion la segunda venta llegaba hasta la violacion del indice
     * uk_sale_detail_work_result y respondia un 500.
     */
    private void reserveWorkResult(SaleDetail detail, UUID workResultId) {
        if (detailRepository.existsByWorkResultId(workResultId)) {
            throw new ValidationException(
                    "El trabajo pendiente ya fue agregado a otra venta");
        }
        detail.setWorkResult(workResultService.resolvePending(
                workResultId,
                detail.getProduct().getId(),
                resolveItemId(detail)));
    }

    /**
     * Unico punto de resolucion de una venta. Una venta de otro taller responde
     * igual que una inexistente para no confirmar que existe.
     */
    /**
     * Toda operacion de este servicio devuelve la venta entera, asi que todas
     * cargan por aqui, con el grafo que evita una consulta por linea al publicar
     * el trabajo que respalda cada una. Ver
     * {@link SaleRepository#findByIdAndWorkshopId}.
     */
    private Sale findOwnedSale(UUID id) {
        return repository
                .findByIdAndWorkshopId(id, SecurityUtils.getWorkshopId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe la venta especificada"));
    }

    private Sale findOpenSale(UUID id) {
        Sale sale = findOwnedSale(id);
        if (sale.getStatus() != SaleStatus.PENDING) {
            throw new ValidationException("La venta no se encuentra abierta");
        }
        return sale;
    }

}
