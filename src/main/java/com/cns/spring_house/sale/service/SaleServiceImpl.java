package com.cns.spring_house.sale.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
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
import com.cns.spring_house.sale.dto.DispatchSaleRequestDTO;
import com.cns.spring_house.sale.dto.RequestSpecificationDTO;
import com.cns.spring_house.sale.dto.ExchangeInvoiceSummaryDTO;
import com.cns.spring_house.sale.dto.RegisterPaymentRequestDTO;
import com.cns.spring_house.sale.dto.SaveForLaterRequestDTO;
import com.cns.spring_house.sale.dto.UpdateRequestPriceDTO;
import com.cns.spring_house.refund.entity.SaleRefund;
import com.cns.spring_house.refund.entity.SaleRefundType;
import com.cns.spring_house.refund.repository.SaleRefundRepository;
import com.cns.spring_house.sale.dto.RefundRequestDTO;
import com.cns.spring_house.sale.dto.ReverseSaleRequestDTO;
import com.cns.spring_house.sale.dto.SaleResponseDTO;
import com.cns.spring_house.sale.dto.SaleSummaryDTO;
import com.cns.spring_house.sale.dto.SearchSaleRequestDTO;
import com.cns.spring_house.sale.entity.Fulfillment;
import com.cns.spring_house.sale.entity.FulfillmentStatus;
import com.cns.spring_house.sale.entity.FulfillmentType;
import com.cns.spring_house.sale.entity.InvoiceType;
import com.cns.spring_house.sale.entity.PaymentStatus;
import com.cns.spring_house.sale.entity.Sale;
import com.cns.spring_house.sale.entity.SaleAction;
import com.cns.spring_house.sale.entity.SaleActionType;
import com.cns.spring_house.sale.entity.SaleDetail;
import com.cns.spring_house.sale.entity.SaleFulfillmentStatus;
import com.cns.spring_house.sale.entity.SaleStatus;
import com.cns.spring_house.sale.entity.TaxMode;
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

    private static final String UNRESOLVED_COMPLETION_MESSAGE =
            "Debe seleccionar NIT o Consumidor Final antes de finalizar la venta";

    private static final String CF_ONLY_AT_COMPLETION_MESSAGE =
            "Consumidor Final se elige al emitir la venta: abrala sin NIT y elija CF al finalizar";

    private static final String OUTSTANDING_FULFILLMENT_MESSAGE =
            "La venta tiene solicitudes que el taller todavia no ha cumplido: "
            + "no se puede completar hasta que todas esten listas";

    private static final String SPECIAL_UNIT_MESSAGE =
            "Una pieza especial se pide al taller: Control entrega la unidad fisica "
            + "al enviar la comanda. No se puede elegir la unidad desde la caja";

    private static final String SERVICE_PERFORMED_MESSAGE =
            "La venta tiene un servicio ya realizado por el taller: no se puede anular. "
            + "Facturelo o regularicelo por otra via";

    private static final String SERVICE_PLATE_MESSAGE =
            "La placa del vehiculo es obligatoria para solicitar un servicio";

    private static final String WORK_IN_PROGRESS_MESSAGE =
            "El taller esta trabajando en una solicitud de esta venta: "
            + "espere a que la termine";

    private final SaleRepository repository;
    private final SaleMapper mapper;
    private final SaleDetailHandlerRegistry registry;
    private final CurrentUserService currentUserService;
    private final CustomerRepository customerRepository;
    private final ProductRepository productRepository;
    private final SaleDetailRepository detailRepository;
    private final WorkResultService workResultService;
    /** Lineas pedidas al taller (piezas especiales y servicios). */
    private final SaleRequestLines requestLines;
    /** Ruta de cada solicitud al enviar la comanda. */
    private final FulfillmentRouter router;
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
                SaleSpecs.isSavedForLater(requestDTO.savedForLater()),
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
        Sale sale = Sale.builder()
                .workshop(workshop)
                .employee(employee)
                .plate(requestDTO.plate())
                .build();
        // Sin modo es el contrato anterior, que solo creaba ventas con cliente:
        // NIT, que sigue exigiendo el cliente. Nunca CF por omision.
        TaxMode taxMode = requestDTO.taxMode() == null
                ? TaxMode.NIT
                : requestDTO.taxMode();
        // CF es una identidad final: se fija al emitir, en la misma transaccion
        // que completa la venta. Aceptarla aqui dejaria un carrito abierto a
        // Consumidor Final si el navegador se cierra antes de emitirlo.
        if (taxMode == TaxMode.CF) {
            throw new ValidationException(CF_ONLY_AT_COMPLETION_MESSAGE);
        }
        applyTaxIdentity(sale, taxMode, requestDTO.customer(), requestDTO.customerName());
        sale = repository.save(sale);
        return mapper.toResponse(sale);
    }

    /**
     * Unico punto que fija la identidad fiscal de una venta, al abrirla y al
     * emitirla, para que las dos puertas no puedan aplicar reglas distintas.
     *
     * Deja la venta con la forma exacta que exige ck_sale_tax_identity: cambiar
     * a CF suelta el Customer —un NIT anterior no puede colarse como identidad
     * del documento— y cambiar a NIT suelta el nombre suelto, porque el nombre
     * pasa a ser el del cliente registrado (se congela al cerrar la venta, ver
     * {@link Sale#freezeFiscalIdentity}). El maestro de clientes no se toca en
     * ningun caso.
     *
     * CF solo llega aqui desde la finalizacion: el alta lo rechaza antes.
     */
    private void applyTaxIdentity(
            Sale sale, TaxMode taxMode, UUID customerId, String customerName) {
        switch (taxMode) {
            case NIT -> {
                if (customerId == null) {
                    throw new ValidationException("El cliente es obligatorio");
                }
                if (customerName != null) {
                    throw new ValidationException(
                            "Una venta con NIT usa el nombre del cliente registrado");
                }
                Customer customer = customerRepository.findById(customerId)
                        .orElseThrow(() -> new EntityNotFoundException(
                                "No existe el cliente especificado"));
                sale.setCustomer(customer);
                sale.setCustomerName(null);
                sale.setCustomerNit(null);
            }
            case CF -> {
                rejectRegisteredCustomer(customerId);
                sale.setCustomer(null);
                sale.setCustomerName(customerName);
                sale.setCustomerNit(null);
            }
            case UNRESOLVED -> {
                rejectRegisteredCustomer(customerId);
                // Un borrador sin NIT puede no tener nombre todavia: el nombre
                // se exige al guardarlo para despues, que es cuando hace falta
                // reconocerlo.
                sale.setCustomer(null);
                sale.setCustomerName(customerName);
                sale.setCustomerNit(null);
            }
        }
        sale.setTaxMode(taxMode);
    }

    private void rejectRegisteredCustomer(UUID customerId) {
        if (customerId != null) {
            throw new ValidationException(
                    "Solo una venta con NIT lleva un cliente registrado");
        }
    }

    /**
     * La identidad con la que se emite: la que pide la finalizacion o, si no
     * pide ninguna, la que la venta ya tenia.
     *
     * Emitir exige una decision explicita entre NIT y CF. Una venta guardada sin
     * definir se rechaza aunque la peticion no diga nada —un cliente antiguo que
     * solo envia el tipo de documento no puede completarla—, y nombre o cliente
     * sin modo tampoco valen: un nombre suelto no convierte nada en CF.
     */
    private void resolveTaxIdentityForCompletion(
            Sale sale, CompleteSaleRequestDTO requestDTO) {
        if (requestDTO.taxMode() == null) {
            if (requestDTO.customer() != null || requestDTO.customerName() != null) {
                throw new ValidationException(
                        "Indique si la venta se factura con NIT o a Consumidor Final");
            }
        } else if (requestDTO.taxMode() == TaxMode.UNRESOLVED) {
            throw new ValidationException(UNRESOLVED_COMPLETION_MESSAGE);
        } else {
            applyTaxIdentity(sale, requestDTO.taxMode(),
                    requestDTO.customer(), requestDTO.customerName());
        }

        if (sale.getTaxMode() == TaxMode.UNRESOLVED) {
            throw new ValidationException(UNRESOLVED_COMPLETION_MESSAGE);
        }
        if (sale.getTaxMode() == TaxMode.NIT && sale.getCustomer() == null) {
            throw new ValidationException("El cliente es obligatorio");
        }
    }

    /**
     * Guarda la venta abierta para retomarla despues.
     *
     * Las lineas ya estaban persistidas desde que se agregaron, y lo reservado
     * sigue reservado; lo que cambia es que la venta deja de ser un carrito en
     * curso y pasa a ser una venta retomable (`savedForLater`), que es lo que
     * lista "Mis ventas pendientes".
     *
     * Una venta sin NIT se guarda SIN DEFINIR y con su nombre de referencia,
     * nunca como Consumidor Final: sin nombre no se podria reconocer despues.
     * Una con NIT se reconoce por su cliente y no admite otro nombre.
     */
    @Override
    public SaleResponseDTO saveForLater(UUID id, SaveForLaterRequestDTO requestDTO) {
        Sale sale = findOpenSale(id);
        switch (sale.getTaxMode()) {
            // Inalcanzable con los datos que admite la base (ck_sale_tax_identity
            // prohibe un CF abierto); se deja para que un estado imposible no se
            // guarde como pendiente.
            case CF -> throw new ValidationException(
                    "Una venta a Consumidor Final se finaliza en el momento: no se puede guardar para despues");
            case NIT -> {
                if (requestDTO.customerName() != null) {
                    throw new ValidationException(
                            "Una venta con NIT usa el nombre del cliente registrado");
                }
            }
            case UNRESOLVED -> {
                String referenceName = requestDTO.customerName() != null
                        ? requestDTO.customerName()
                        : sale.getCustomerName();
                if (referenceName == null) {
                    throw new ValidationException(
                            "El nombre es obligatorio para guardar para despues una venta sin NIT");
                }
                sale.setCustomerName(referenceName);
            }
        }
        sale.setSavedForLater(true);
        sale = repository.save(sale);
        return mapper.toResponse(sale);
    }

    /**
     * Agrega lo que el cliente pide.
     *
     * Dos clases de linea, y la decide QUE se pide, no quien lo pide:
     *
     * <ul>
     * <li>Directa, como siempre: una pieza normal o una maleta, una unidad
     * especial ya senalada, o un trabajo ya ejecutado. El inventario se
     * descuenta en este momento.</li>
     * <li>Solicitud al taller: una pieza especial SIN unidad elegida —el
     * vendedor describe la pieza que el cliente quiere recibir, haya stock o
     * no— o un servicio sin trabajo ya hecho. Queda pendiente de enviar, y el
     * vendedor puede quitarla o corregirla hasta enviar la comanda. Las
     * unidades terminadas libres de una pieza especial quedan reservadas en
     * este momento (ver {@link SaleRequestLines#addSpecial}).</li>
     * </ul>
     *
     * Un servicio se hace sobre un vehiculo: no se solicita sin placa. Si la
     * venta todavia no la tiene, viaja con la linea y se registra en la misma
     * transaccion.
     *
     * El cerrojo de la venta va primero, antes que el del inventario —el mismo
     * orden que enviar, quitar o anular—: una anulacion simultanea no puede
     * dejar una unidad reservada en una venta ya anulada.
     */
    @Override
    public SaleResponseDTO addDetail(UUID id, AddSaleDetailRequestDTO requestDTO) {
        lockSale(id);
        Sale sale = findOpenSale(id);
        registerPlate(sale, requestDTO.plate());
        Product product = productRepository.findById(requestDTO.product())
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el producto especificado"));
        FamilyCategory category = product.getFamily().getCategory();
        // La unidad fisica de una pieza especial la decide Control: aceptar un
        // item aqui —directo o como salida de un trabajo— seria venderla sin
        // pasar por quien la entrega. El vendedor describe la pieza; nada mas.
        if (category == FamilyCategory.SPECIAL
                && (requestDTO.item() != null || requestDTO.workResult() != null)) {
            throw new ValidationException(SPECIAL_UNIT_MESSAGE);
        }
        if (category == FamilyCategory.SPECIAL) {
            requestLines.addSpecial(
                    sale,
                    product,
                    requestDTO.quantity(),
                    requestDTO.price(),
                    requestDTO.invoiceDescription(),
                    requestDTO.instructions(),
                    requestDTO.requestedCurls(),
                    requestDTO.requestedCurlCount());
        } else {
            if (requestDTO.requestedCurls() != null || requestDTO.requestedCurlCount() != null) {
                throw new ValidationException("Solo una pieza especial lleva colochos");
            }
            boolean serviceRequest = category == FamilyCategory.JOB
                    && requestDTO.workResult() == null;
            if (!serviceRequest && requestDTO.instructions() != null) {
                throw new ValidationException(
                        "Las instrucciones solo aplican a lo que se solicita al taller");
            }
            if (serviceRequest) {
                requireServicePlate(sale);
            }
            SaleDetailHandler handler = registry.get(category);
            SaleDetail detail = handler.addDetail(sale, requestDTO);
            if (requestDTO.workResult() != null) {
                reserveWorkResult(detail, requestDTO.workResult());
            } else if (serviceRequest) {
                // El servicio lo ejecuta el Operador; el Job define QUE se hace y
                // las instrucciones solo lo complementan.
                detail.setInstructions(requestDTO.instructions());
                detail.setFulfillment(Fulfillment.pending(FulfillmentType.SERVICE));
            }
        }
        sale.refreshFulfillmentStatus();
        sale = repository.save(sale);
        return mapper.toResponse(sale);
    }

    /**
     * "Solicitar con estas caracteristicas": una pieza especial que todavia no
     * existe en el catalogo. Se resuelve —o se crea, sin existencias— el
     * producto que la describe y se pide como cualquier otra pieza especial.
     * Nunca crea inventario: sin unidades terminadas, queda para fabricar.
     */
    @Override
    public SaleResponseDTO requestSpecification(UUID id, RequestSpecificationDTO requestDTO) {
        lockSale(id);
        Sale sale = findOpenSale(id);
        Product product = requestLines.resolveSpecification(requestDTO);
        requestLines.addSpecial(
                sale,
                product,
                requestDTO.quantity(),
                requestDTO.price(),
                requestDTO.invoiceDescription(),
                requestDTO.instructions(),
                requestDTO.requestedCurls(),
                requestDTO.requestedCurlCount());
        sale.refreshFulfillmentStatus();
        sale = repository.save(sale);
        return mapper.toResponse(sale);
    }

    /**
     * "Enviar comanda": manda al taller las solicitudes que aun no se enviaron.
     *
     * Solo toca lineas PENDING, asi que repetir el envio —un doble clic, un
     * reintento tras un corte— no duplica nada, y enviar despues de agregar mas
     * cosas manda unicamente lo nuevo. Lo ya enviado no se vuelve a evaluar.
     *
     * Control y el Operador tienen que saber de quien es el pedido: una venta
     * sin NIT necesita su nombre de referencia. El pedido queda ademas guardado
     * para despues, porque desde ahora es una venta que se retoma cuando el
     * taller la termine, no un carrito en curso.
     */
    @Override
    public SaleResponseDTO dispatch(UUID id, DispatchSaleRequestDTO requestDTO) {
        lockSale(id);
        Sale sale = findOpenSale(id);
        List<SaleDetail> pending = sale.getDetails().stream()
                .filter(SaleDetail::isRequest)
                .filter(detail -> detail.getFulfillment().isPending())
                .toList();
        if (pending.isEmpty()) {
            return mapper.toResponse(sale);
        }
        requireReferenceName(sale, requestDTO.customerName());
        requirePlateForServices(sale, pending, requestDTO.plate());
        // Al microsegundo, que es lo que guarda la base: la hora que ve el
        // vendedor al enviar es la misma que vera al recargar.
        router.route(pending, LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS));
        sale.setSavedForLater(true);
        sale.refreshFulfillmentStatus();
        sale = repository.save(sale);
        return mapper.toResponse(sale);
    }

    private void requireReferenceName(Sale sale, String customerName) {
        if (sale.getTaxMode() != TaxMode.UNRESOLVED) {
            return;
        }
        String referenceName = customerName != null ? customerName : sale.getCustomerName();
        if (referenceName == null) {
            throw new ValidationException(
                    "Indique el nombre del cliente antes de enviar la comanda: "
                    + "Control y el Operador necesitan saber de quien es el pedido");
        }
        sale.setCustomerName(referenceName);
    }

    /**
     * El taller tiene que saber sobre que vehiculo hace el servicio. Una
     * solicitud de servicio agregada antes de exigir la placa al agregarla
     * puede llegar aqui sin ella: se completa con la del envio.
     */
    private void requirePlateForServices(Sale sale, List<SaleDetail> pending, String plate) {
        boolean sendsService = pending.stream()
                .anyMatch(detail -> detail.getFulfillment().getType() == FulfillmentType.SERVICE);
        if (!sendsService) {
            return;
        }
        registerPlate(sale, plate);
        requireServicePlate(sale);
    }

    private void requireServicePlate(Sale sale) {
        if (sale.getPlate() == null || sale.getPlate().isBlank()) {
            throw new ValidationException(SERVICE_PLATE_MESSAGE);
        }
    }

    /**
     * Registra la placa de una venta abierta que todavia no la tiene. La de una
     * venta que ya la tiene no se reescribe por esta via: es la del vehiculo
     * que se esta atendiendo, y el taller ya puede estar trabajando con ella.
     */
    private void registerPlate(Sale sale, String plate) {
        if (plate == null) {
            return;
        }
        if (sale.getPlate() == null) {
            sale.setPlate(plate);
            return;
        }
        if (!sale.getPlate().equals(plate)) {
            throw new ValidationException(
                    "La venta ya tiene registrada la placa " + sale.getPlate());
        }
    }

    /**
     * El vendedor revisa el precio de una solicitud.
     *
     * Solo mientras no hay nada fisico hecho: antes de enviarla, o cuando el
     * taller la devolvio a revision porque la pieza de origen cuesta mas que lo
     * autorizado. El nuevo precio pasa por los mismos minimos de siempre —el
     * costo del inventario pedido— y, en revision, ademas por el costo real que
     * informo el taller. Si la cubre, la solicitud vuelve a la cola del
     * Operador, libre para cualquiera.
     */
    @Override
    public SaleResponseDTO updateRequestPrice(UUID saleDetailId, UpdateRequestPriceDTO requestDTO) {
        UUID workshopId = SecurityUtils.getWorkshopId();
        detailRepository.findSaleIdByIdAndWorkshopId(saleDetailId, workshopId)
                .ifPresent(this::lockSale);
        SaleDetail detail = detailRepository
                .findByIdAndSaleWorkshopId(saleDetailId, workshopId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el detalle especificado"));
        Sale sale = detail.getSale();
        if (sale.getStatus() != SaleStatus.PENDING) {
            throw new ValidationException("La venta no se encuentra abierta");
        }
        Fulfillment fulfillment = detail.getFulfillment();
        if (!detail.isRequest() || !(fulfillment.isPending() || fulfillment.awaitsPriceReview())) {
            throw new ValidationException(
                    "Solo se puede revisar el precio de una solicitud sin enviar "
                    + "o devuelta por el taller para revision de precio");
        }
        BigDecimal price = requestDTO.price();
        BigDecimal floor = requestLines.minimumPrice(detail.getProduct());
        if (fulfillment.awaitsPriceReview() && fulfillment.getRequiredPrice() != null
                && fulfillment.getRequiredPrice().compareTo(floor) > 0) {
            floor = fulfillment.getRequiredPrice();
        }
        if (price.compareTo(floor) < 0) {
            throw new ValidationException(
                    "El precio no puede ser menor al costo de la pieza (Q " + floor.toPlainString() + ")");
        }
        BigDecimal subTotal = price.multiply(BigDecimal.valueOf(
                detail.getQuantity() == null ? 1 : detail.getQuantity()));
        sale.setTotal(sale.getTotal().subtract(detail.getSubTotal()).add(subTotal));
        detail.setPrice(price);
        detail.setSubTotal(subTotal);
        if (fulfillment.awaitsPriceReview()) {
            fulfillment.clearBlock();
            fulfillment.setStatus(FulfillmentStatus.QUEUED);
            fulfillment.setEmployee(null);
        }
        sale.refreshFulfillmentStatus();
        return mapper.toResponse(repository.save(sale));
    }

    /**
     * "Ventas listas para completar": pedidos abiertos del taller cuyo trabajo
     * ya termino. Se buscan por nombre, NIT o placa.
     */
    @Override
    public List<SaleResponseDTO> getReady(String query) {
        String term = query == null || query.isBlank() ? null : query.trim();
        Specification<Sale> specs = Specification.allOf(
                SaleSpecs.hasWorkshop(SecurityUtils.getWorkshopId()),
                SaleSpecs.hasStatus(SaleStatus.PENDING),
                SaleSpecs.hasFulfillmentStatus(SaleFulfillmentStatus.READY_TO_COMPLETE),
                Specification.anyOf(
                        SaleSpecs.nameContains(term),
                        SaleSpecs.nitContains(term),
                        SaleSpecs.plateContains(term)));
        return repository.findAll(specs).stream()
                .sorted(Comparator.comparing(Sale::getUpdatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(mapper::toResponse)
                .toList();
    }

    @Override
    public SaleResponseDTO removeDetail(UUID saleDetailId) {
        detailRepository
                .findSaleIdByIdAndWorkshopId(saleDetailId, SecurityUtils.getWorkshopId())
                .ifPresent(this::lockSale);
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
        sale.refreshFulfillmentStatus();
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
        // Y el de la venta, que es el que toman Control y el Operador al cumplir
        // sus solicitudes: nadie puede entregar ni fabricar sobre ella mientras
        // se factura.
        lockSale(id);
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
        // Preparar el pedido no es facturarlo, pero no se factura lo que el
        // taller no ha entregado, fabricado o ejecutado. Esta regla vive aqui y
        // no en un boton deshabilitado.
        if (sale.getDetails().stream().anyMatch(SaleDetail::blocksCompletion)) {
            throw new ValidationException(OUTSTANDING_FULFILLMENT_MESSAGE);
        }
        resolveTaxIdentityForCompletion(sale, requestDTO);
        LocalDateTime now = LocalDateTime.now(clock);
        sale.setInvoiceType(requestDTO.invoiceType());
        sale.setCompletedAt(now);
        sale.setStatus(SaleStatus.COMPLETED);
        // La identidad historica se congela en la misma transaccion que emite:
        // si algo de aqui en adelante falla, se deshace junto con el paso a CF
        // o a NIT y la venta sigue abierta tal como estaba.
        sale.freezeFiscalIdentity();
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
        lockSale(id);
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
        // Un trabajo fisico a medias no se deshace por anular la venta: hay que
        // esperar a que el taller lo termine. Se comprueba antes de tocar nada.
        if (sale.getDetails().stream().anyMatch(this::isInProgress)) {
            throw new ValidationException(WORK_IN_PROGRESS_MESSAGE);
        }
        // Un servicio ya hecho tampoco se "desanula": la mano de obra ocurrio.
        // A diferencia de una pieza, no hay nada fisico que devolver al stock,
        // asi que anular la venta solo borraria la constancia de que se hizo.
        if (sale.getDetails().stream().anyMatch(this::isPerformedService)) {
            throw new ValidationException(SERVICE_PERFORMED_MESSAGE);
        }
        sale.getDetails().forEach(this::releaseReservation);
        sale.setStatus(SaleStatus.CANCELLED);
        // Tambien es historial: queda con el cliente que tenia al anularse.
        sale.freezeFiscalIdentity();
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
        if (detail.isRequest()) {
            cancelRequest(detail);
            return;
        }
        FamilyCategory category = detail.getProduct().getFamily().getCategory();
        registry.get(category).restore(detail);
        detail.setWorkResult(null);
    }

    /**
     * Anular la venta con una solicitud dentro.
     *
     * <ul>
     * <li>Sin cumplir y sin nada reservado (pendiente de enviar, en cola del
     * Operador, bloqueada): queda CANCELLED; no hay nada que devolver.</li>
     * <li>En cola de Control: la unidad reservada vuelve a estar disponible,
     * igual que al quitar una pieza del carrito.</li>
     * <li>Ya cumplida: la pieza entregada o fabricada vuelve al stock —es una
     * pieza real— y el trabajo ejecutado se suelta para poder venderse en otra
     * venta. El estado sigue diciendo FULFILLED: el taller SI lo hizo, y la
     * transformacion o el servicio quedan en su historial.</li>
     * </ul>
     */
    private void cancelRequest(SaleDetail detail) {
        Fulfillment fulfillment = detail.getFulfillment();
        if (detail.getItem() != null) {
            registry.get(FamilyCategory.SPECIAL).restore(detail);
        }
        detail.setWorkResult(null);
        if (!fulfillment.isFulfilled()) {
            fulfillment.setStatus(FulfillmentStatus.CANCELLED);
        }
    }

    private boolean isPerformedService(SaleDetail detail) {
        return detail.isRequest()
                && detail.getFulfillment().getType() == FulfillmentType.SERVICE
                && detail.getFulfillment().isFulfilled();
    }

    private boolean isInProgress(SaleDetail detail) {
        return detail.isRequest()
                && detail.getFulfillment().getStatus() == FulfillmentStatus.IN_PROGRESS;
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
        lockSale(id);
        Sale sale = findOwnedSale(id);
        if (sale.getStatus() != SaleStatus.PENDING) {
            throw new ValidationException(
                    "Solo se puede eliminar una venta abierta");
        }
        List.copyOf(sale.getDetails())
                .forEach(detail -> removeDetail(sale, detail));
        repository.delete(sale);
    }

    /**
     * Devuelve al inventario lo que el detalle habia reservado.
     *
     * Una solicitud se puede quitar mientras el taller no la haya empezado ni
     * cumplido: sin enviar no reservo nada, y en cola de Control se suelta su
     * unidad. Una cumplida o en curso ya es trabajo fisico hecho: para
     * deshacerla se anula la venta, que conserva el historial.
     */
    private void removeDetail(Sale sale, SaleDetail saleDetail) {
        if (saleDetail.isRequest()) {
            removeRequest(sale, saleDetail);
            return;
        }
        FamilyCategory category = saleDetail.getProduct().getFamily().getCategory();
        registry.get(category).removeDetail(sale, saleDetail);
    }

    private void removeRequest(Sale sale, SaleDetail detail) {
        FulfillmentStatus status = detail.getFulfillment().getStatus();
        if (status == FulfillmentStatus.IN_PROGRESS) {
            throw new ValidationException(
                    "El taller ya esta trabajando en esta solicitud: no se puede quitar");
        }
        if (status == FulfillmentStatus.FULFILLED) {
            throw new ValidationException(
                    "La solicitud ya fue cumplida: para deshacerla, anule la venta");
        }
        if (detail.getItem() != null) {
            // En cola de Control: la unidad reservada vuelve a estar disponible.
            registry.get(FamilyCategory.SPECIAL).removeDetail(sale, detail);
            return;
        }
        sale.removeDetail(detail);
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
    /**
     * Toma el cerrojo de la venta antes de leerla. Ver
     * {@link SaleRepository#lockByIdAndWorkshopId}.
     */
    private void lockSale(UUID id) {
        repository.lockByIdAndWorkshopId(id, SecurityUtils.getWorkshopId());
    }

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
