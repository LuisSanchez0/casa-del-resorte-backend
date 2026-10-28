package com.cns.spring_house.sale.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cns.spring_house.acquisition.service.InventoryLotService;
import com.cns.spring_house.employee.entity.Employee;
import com.cns.spring_house.family.entity.Family;
import com.cns.spring_house.family.entity.FamilyCategory;
import com.cns.spring_house.family.repository.FamilyRepository;
import com.cns.spring_house.family.service.HoleMeasurementValidator;
import com.cns.spring_house.family.service.TransformationFamilyCompatibility;
import com.cns.spring_house.inventory.dto.InventoryItemResponseDTO;
import com.cns.spring_house.inventory.entity.Inventory;
import com.cns.spring_house.inventory.entity.InventoryItem;
import com.cns.spring_house.inventory.entity.InventoryItemStatus;
import com.cns.spring_house.inventory.mapper.InventoryItemMapper;
import com.cns.spring_house.inventory.repository.InventoryItemRepository;
import com.cns.spring_house.inventory.repository.InventoryRepository;
import com.cns.spring_house.job.entity.Job;
import com.cns.spring_house.job.entity.JobLog;
import com.cns.spring_house.job.repository.JobLogRepository;
import com.cns.spring_house.job.repository.JobRepository;
import com.cns.spring_house.model.entity.Model;
import com.cns.spring_house.product.entity.Product;
import com.cns.spring_house.product.repository.ProductRepository;
import com.cns.spring_house.sale.dto.BlockFulfillmentRequestDTO;
import com.cns.spring_house.sale.dto.FulfillTransformationRequestDTO;
import com.cns.spring_house.sale.dto.FulfillmentProductDTO;
import com.cns.spring_house.sale.dto.FulfillmentSaleProgressDTO;
import com.cns.spring_house.sale.dto.FulfillmentSourceDTO;
import com.cns.spring_house.sale.dto.FulfillmentTaskResponseDTO;
import com.cns.spring_house.sale.dto.ReleaseFulfillmentRequestDTO;
import com.cns.spring_house.sale.entity.Fulfillment;
import com.cns.spring_house.sale.entity.FulfillmentArea;
import com.cns.spring_house.sale.entity.FulfillmentBlockReason;
import com.cns.spring_house.sale.entity.FulfillmentStatus;
import com.cns.spring_house.sale.entity.FulfillmentType;
import com.cns.spring_house.sale.entity.Sale;
import com.cns.spring_house.sale.entity.SaleDetail;
import com.cns.spring_house.sale.entity.SaleStatus;
import com.cns.spring_house.sale.entity.TaxMode;
import com.cns.spring_house.sale.handler.SaleDetailHandler;
import com.cns.spring_house.sale.repository.SaleDetailRepository;
import com.cns.spring_house.sale.repository.SaleRepository;
import com.cns.spring_house.sale.repository.projection.FulfillmentTaskRow;
import com.cns.spring_house.sale.repository.projection.SaleAreaProgressRow;
import com.cns.spring_house.shared.barcode.BarcodeIdentity;
import com.cns.spring_house.shared.exception.EntityNotFoundException;
import com.cns.spring_house.shared.exception.ValidationException;
import com.cns.spring_house.shared.security.CurrentUserService;
import com.cns.spring_house.shared.security.SecurityUtils;
import com.cns.spring_house.shared.util.LikePatterns;
import com.cns.spring_house.transformation.dto.CreateTransformationRequestDTO;
import com.cns.spring_house.transformation.dto.TransformationResponseDTO;
import com.cns.spring_house.transformation.entity.Transformation;
import com.cns.spring_house.transformation.entity.TransformationItem;
import com.cns.spring_house.transformation.entity.TransformationItemRole;
import com.cns.spring_house.transformation.handler.CurlCountRequirement;
import com.cns.spring_house.transformation.handler.SpecialPieceRules;
import com.cns.spring_house.transformation.repository.TransformationRepository;
import com.cns.spring_house.transformation.service.TransformationLengthValidator;
import com.cns.spring_house.transformation.service.TransformationService;
import com.cns.spring_house.workresult.entity.WorkResult;
import com.cns.spring_house.workresult.entity.WorkResultStatus;
import com.cns.spring_house.workresult.repository.WorkResultRepository;
import com.cns.spring_house.workresult.service.WorkResultService;

import lombok.RequiredArgsConstructor;

/**
 * Cumplimiento de las solicitudes de la comanda por Control y el Operador.
 *
 * Toda accion que cambia una solicitud toma PRIMERO el cerrojo de su venta y
 * despues, si toca stock, el de los inventarios en orden de producto —el mismo
 * orden que usa el envio de la comanda—. Asi dos personas nunca deciden sobre
 * la misma venta a la vez, y dos operaciones que tocan los mismos inventarios
 * nunca se esperan en circulo.
 *
 * El resultado de cada accion vuelve SOLO a la venta: la pieza entregada, la
 * fabricada o el servicio ejecutado quedan en la linea que los pidio, y el
 * vendedor no tiene que volver a buscarlos ni agregarlos.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class SaleFulfillmentServiceImpl implements SaleFulfillmentService {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 200;
    private static final int MAX_ELIGIBLE_ITEMS = 100;
    private static final int MAX_SOURCES = 200;
    private static final int MAX_SOURCE_QUERY = 100;

    private static final Set<FulfillmentStatus> OPEN_STATUSES = EnumSet.of(
            FulfillmentStatus.QUEUED,
            FulfillmentStatus.IN_PROGRESS,
            FulfillmentStatus.BLOCKED);

    /** Enviadas al taller y no anuladas: lo que cuenta en el avance de un area. */
    private static final Set<FulfillmentStatus> DISPATCHED_STATUSES = EnumSet.of(
            FulfillmentStatus.QUEUED,
            FulfillmentStatus.IN_PROGRESS,
            FulfillmentStatus.BLOCKED,
            FulfillmentStatus.FULFILLED);

    private final SaleRepository saleRepository;
    private final SaleDetailRepository detailRepository;
    private final InventoryRepository inventoryRepository;
    private final InventoryItemRepository itemRepository;
    private final InventoryItemMapper itemMapper;
    private final InventoryLotService lotService;
    private final FamilyRepository familyRepository;
    private final ProductRepository productRepository;
    private final TransformationService transformationService;
    private final TransformationRepository transformationRepository;
    private final JobRepository jobRepository;
    private final JobLogRepository jobLogRepository;
    private final WorkResultService workResultService;
    private final WorkResultRepository workResultRepository;
    private final CurrentUserService currentUserService;
    private final Clock clock;

    // ── Colas ────────────────────────────────────────────────────

    /**
     * Cola de un area. Sin estados pedidos, lo que el area tiene pendiente:
     * en cola, en curso y bloqueado.
     */
    @Override
    @Transactional(readOnly = true)
    public List<FulfillmentTaskResponseDTO> getQueue(
            FulfillmentArea area, List<FulfillmentStatus> statuses, Integer limit) {
        return getQueue(area, statuses, limit, 0);
    }

    @Override
    @Transactional(readOnly = true)
    public List<FulfillmentTaskResponseDTO> getQueue(
            FulfillmentArea area, List<FulfillmentStatus> statuses, Integer limit, Integer page) {
        if (area == null) {
            throw new ValidationException("Indique el area: CONTROL u OPERATOR");
        }
        int pageNumber = page == null ? 0 : page;
        if (pageNumber < 0 || (long) pageNumber * clamp(limit) > Integer.MAX_VALUE) {
            throw new ValidationException("La pagina solicitada no es valida");
        }
        Collection<FulfillmentStatus> wanted = statuses == null || statuses.isEmpty()
                ? OPEN_STATUSES
                : statuses;
        List<FulfillmentTaskRow> rows = detailRepository.findFulfillmentQueue(
                SecurityUtils.getWorkshopId(),
                typesOf(area),
                wanted,
                PageRequest.of(pageNumber, clamp(limit)));
        QueueContext context = contextOf(rows, area);
        return rows.stream()
                .map(row -> toResponse(row, context))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public FulfillmentTaskResponseDTO get(UUID id) {
        return response(id);
    }

    // ── Control ──────────────────────────────────────────────────

    /**
     * La unidad reservada para esta linea —ya es de la venta, aunque el stock
     * libre este en cero— y, mientras no se haya entregado, cualquier otra
     * unidad disponible del MISMO producto: Control entrega la que tiene a
     * mano, no necesariamente la que el sistema aparto.
     */
    @Override
    @Transactional(readOnly = true)
    public List<InventoryItemResponseDTO> getEligibleItems(UUID id) {
        FulfillmentTaskRow row = findRow(id);
        requireType(row.type(), FulfillmentType.STOCK_RELEASE);
        List<InventoryItemResponseDTO> items = new ArrayList<>();
        if (row.itemId() != null) {
            itemRepository.findById(row.itemId())
                    .map(itemMapper::toResponse)
                    .ifPresent(items::add);
        }
        if (row.status() != FulfillmentStatus.QUEUED) {
            return items;
        }
        inventoryRepository
                .findByProductIdAndWorkshopId(row.productId(), SecurityUtils.getWorkshopId())
                .map(inventory -> itemRepository.findByInventoryIdAndStatus(
                        inventory.getId(),
                        InventoryItemStatus.AVAILABLE,
                        PageRequest.of(0, MAX_ELIGIBLE_ITEMS)))
                .orElse(List.of())
                .stream()
                .map(itemMapper::toResponse)
                .forEach(items::add);
        return items;
    }

    /**
     * Control entrega la unidad. Si es otra que la reservada, se cambian: la
     * elegida sale y la reservada vuelve a estar disponible. El stock y el
     * costo no se mueven —es una unidad del mismo inventario por otra—; lo que
     * cambia es QUE pieza concreta, con su codigo, queda en la venta.
     */
    @Override
    public FulfillmentTaskResponseDTO release(UUID id, ReleaseFulfillmentRequestDTO requestDTO) {
        SaleDetail detail = lockRequest(id);
        requireType(detail.getFulfillment().getType(), FulfillmentType.STOCK_RELEASE);
        FulfillmentStatus status = detail.getFulfillment().getStatus();
        if (status == FulfillmentStatus.FULFILLED) {
            throw new ValidationException("La solicitud ya fue entregada");
        }
        if (status != FulfillmentStatus.QUEUED) {
            throw new ValidationException("La solicitud no esta pendiente de entrega");
        }
        InventoryItem reserved = detail.getItem();
        UUID chosen = requestDTO.item() == null ? reserved.getId() : requestDTO.item();
        if (!chosen.equals(reserved.getId())) {
            swapUnit(detail, reserved, chosen);
        }
        linkProducedWork(detail);
        complete(detail);
        return save(detail, id);
    }

    /**
     * Si la unidad entregada nacio de una transformacion, su trabajo queda en
     * esta linea: al facturar la venta pasa a SOLD, igual que cuando el
     * Operador fabrica para la venta. Sin esto, una pieza fabricada antes y
     * entregada desde stock quedaria con su trabajo "sin facturar" para
     * siempre, aunque se cobro.
     *
     * Solo se toma un trabajo PENDING que ninguna otra linea tenga.
     */
    private void linkProducedWork(SaleDetail detail) {
        workResultRepository.findByItemId(detail.getItem().getId())
                .filter(workResult -> workResult.getStatus() == WorkResultStatus.PENDING)
                .filter(workResult -> !detailRepository.existsByWorkResultId(workResult.getId()))
                .ifPresent(detail::setWorkResult);
    }

    private void swapUnit(SaleDetail detail, InventoryItem reserved, UUID chosenId) {
        UUID workshopId = SecurityUtils.getWorkshopId();
        Inventory inventory = inventoryRepository.findByItemIdForUpdate(chosenId)
                .filter(found -> found.getWorkshop().getId().equals(workshopId))
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el item especificado"));
        if (!inventory.getId().equals(reserved.getInventory().getId())) {
            throw new ValidationException(
                    "La unidad elegida no es del producto solicitado");
        }
        // Se lee DESPUES del cerrojo: si otra entrega acaba de llevarsela, aqui
        // ya figura como vendida.
        InventoryItem chosen = itemRepository
                .findByIdAndInventoryWorkshopId(chosenId, workshopId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el item especificado"));
        if (chosen.getStatus() != InventoryItemStatus.AVAILABLE) {
            throw new ValidationException(
                    "La unidad elegida no esta disponible");
        }
        chosen.setStatus(InventoryItemStatus.SOLD);
        reserved.setStatus(InventoryItemStatus.AVAILABLE);
        detail.setItem(chosen);
    }

    // ── Operador ─────────────────────────────────────────────────

    /** El Operador toma la solicitud; nadie mas puede terminarla mientras tanto. */
    @Override
    public FulfillmentTaskResponseDTO start(UUID id) {
        SaleDetail detail = lockRequest(id);
        requireOperator(detail);
        Fulfillment fulfillment = detail.getFulfillment();
        Employee employee = currentUserService.getCurrentEmployee();
        if (fulfillment.getStatus() == FulfillmentStatus.IN_PROGRESS) {
            requireTakenBy(fulfillment, employee);
            return response(id);
        }
        requireNotAwaitingPriceReview(fulfillment);
        requireWorkable(fulfillment, employee);
        fulfillment.setStatus(FulfillmentStatus.IN_PROGRESS);
        fulfillment.setEmployee(employee);
        fulfillment.setStartedAt(now());
        fulfillment.clearBlock();
        return save(detail, id);
    }

    /**
     * "Pendiente de materia prima": el Operador no puede cumplirla por ahora.
     * No fabrica ni reserva nada; la venta sigue sin poder completarse hasta
     * que alguien la retome y la termine.
     */
    @Override
    public FulfillmentTaskResponseDTO block(UUID id, BlockFulfillmentRequestDTO requestDTO) {
        SaleDetail detail = lockRequest(id);
        requireOperator(detail);
        Fulfillment fulfillment = detail.getFulfillment();
        Employee employee = currentUserService.getCurrentEmployee();
        requireNotAwaitingPriceReview(fulfillment);
        requireWorkable(fulfillment, employee);
        fulfillment.block(FulfillmentBlockReason.MATERIAL, requestDTO.reason(), null);
        fulfillment.setEmployee(employee);
        return save(detail, id);
    }

    /**
     * Piezas de las que se puede fabricar lo pedido.
     *
     * Las candidatas salen de las familias que pueden llegar a ser la pedida
     * ({@link TransformationFamilyCompatibility}) y se filtran con las MISMAS
     * reglas que aplicara la transformacion —largo y medidas con orificio—, de
     * modo que no se ofrece ninguna que luego la transformacion rechazaria por
     * ellas. La autoridad sigue siendo la transformacion al ejecutarse.
     */
    @Override
    @Transactional(readOnly = true)
    public List<FulfillmentSourceDTO> getSources(UUID id, String query) {
        FulfillmentTaskRow row = findRow(id);
        requireType(row.type(), FulfillmentType.TRANSFORMATION);
        Product target = productRepository.findById(row.productId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el producto solicitado"));
        Family targetFamily = target.getFamily();
        Set<UUID> sourceFamilies = familyRepository.findAll().stream()
                .filter(family -> family.getCategory() == FamilyCategory.SPECIAL)
                .filter(family -> TransformationFamilyCompatibility.isAllowed(family, targetFamily))
                .map(Family::getId)
                .collect(Collectors.toSet());
        if (sourceFamilies.isEmpty()) {
            return List.of();
        }
        RequestedCurls requested = requestOf(row);
        String search = query == null || query.isBlank() ? null : query.trim();
        if (search != null && search.length() > MAX_SOURCE_QUERY) {
            throw new ValidationException(
                    "La busqueda admite hasta " + MAX_SOURCE_QUERY + " caracteres");
        }
        return itemRepository.findTransformationSources(
                        SecurityUtils.getWorkshopId(),
                        sourceFamilies,
                        target.getId(),
                        search == null ? null : LikePatterns.containsIgnoreCaseAndAccents(search),
                        BarcodeIdentity.parseItem(search).orElse(null),
                        LikePatterns.ACCENTED,
                        LikePatterns.UNACCENTED,
                        PageRequest.of(0, MAX_SOURCES))
                .stream()
                .map(item -> toSource(item, target, requested, row.price()))
                .flatMap(Optional::stream)
                .sorted(Comparator.comparing(source -> !source.sameModel()))
                .toList();
    }

    private Optional<FulfillmentSourceDTO> toSource(
            InventoryItem item, Product target, RequestedCurls requested, BigDecimal authorizedPrice) {
        Product source = item.getInventory().getProduct();
        Family sourceFamily = source.getFamily();
        Family targetFamily = target.getFamily();
        boolean sameFamily = TransformationFamilyCompatibility.sameFamily(sourceFamily, targetFamily);
        Map<String, Object> effective = effectiveOutput(source, target, sameFamily);
        try {
            TransformationLengthValidator.validate(
                    sourceFamily, source.getAttributes(), effective);
            HoleMeasurementValidator.validate(sourceFamily, effective);
            if (!sameFamily) {
                HoleMeasurementValidator.validate(targetFamily, effective);
            }
        } catch (ValidationException rejected) {
            return Optional.empty();
        }
        Optional<Double> remainder = SpecialPieceRules.remainderLength(
                source.getAttributes(), effective);
        boolean supportsCurls = supportsCurlCount(sourceFamily);
        Model model = source.getModel();
        return Optional.of(new FulfillmentSourceDTO(
                itemMapper.toResponse(item),
                sourceFamily.getName(),
                model == null ? null : model.getName(),
                model == null || model.getBrand() == null ? null : model.getBrand().getName(),
                Objects.equals(modelId(source), modelId(target)),
                remainder.orElse(null),
                // Opcional, y solo si la venta no fijo la cantidad ni dijo que no
                // lleva: lo que la venta no especifico no se exige.
                sameFamily && supportsCurls && requested.count() == null && !requested.excludesCurls(),
                remainder.isPresent() && supportsCurls,
                authorizedPrice.compareTo(productionCost(item.getInventory())) >= 0));
    }

    /**
     * Lo que sera la pieza resultante, con la MISMA lectura que la
     * transformacion: en la misma familia, las caracteristicas pedidas se
     * imponen sobre las de la pieza de origen; cambiando de familia, la pieza
     * resultante ES el producto pedido.
     */
    private Map<String, Object> effectiveOutput(Product source, Product target, boolean sameFamily) {
        Map<String, Object> effective = new HashMap<>();
        if (sameFamily && source.getAttributes() != null) {
            effective.putAll(source.getAttributes());
        }
        if (target.getAttributes() != null) {
            target.getAttributes().forEach((key, value) -> {
                effective.keySet().removeIf(current -> current != null
                        && !current.equals(key) && current.equalsIgnoreCase(key));
                effective.put(key, value);
            });
        }
        return effective;
    }

    /**
     * Fabrica la pieza pedida con la transformacion de siempre —mismos
     * manejadores, mismas validaciones, misma identidad fisica: la pieza de
     * origen se consume y la resultante nace con su propio id y codigo— y la
     * entrega a la venta en la misma transaccion.
     *
     * El producto de salida es SIEMPRE el pedido por el vendedor; el operador
     * no puede cambiarlo. Si la pieza de origen aporta caracteristicas que la
     * pedida no dice, la transformacion resuelve el producto equivalente que
     * las incluye y la linea pasa a ese producto: es la pieza que se entrega y
     * cumple todo lo pedido. Precio y descripcion fiscal no cambian.
     *
     * Con tope de tiempo: si otra sesion retiene la venta, un inventario o la
     * pieza sin confirmar, esperar no termina nunca y el Operador veia el
     * dialogo "Fabricando..." para siempre. Al cumplirse el tope la base cancela
     * la espera, la transaccion se deshace entera y la respuesta es un 409 que
     * se puede reintentar.
     */
    @Override
    @Transactional(timeoutString = "${app.fulfillment.transform-timeout-seconds:10}")
    public FulfillmentTaskResponseDTO transform(UUID id, FulfillTransformationRequestDTO requestDTO) {
        SaleDetail detail = lockRequest(id);
        requireType(detail.getFulfillment().getType(), FulfillmentType.TRANSFORMATION);
        Fulfillment fulfillment = detail.getFulfillment();
        Employee employee = currentUserService.getCurrentEmployee();
        requireWorkable(fulfillment, employee);

        UUID workshopId = SecurityUtils.getWorkshopId();
        Product target = detail.getProduct();
        UUID sourceProductId = itemRepository
                .findProductIdByIdAndWorkshopId(requestDTO.inputItem(), workshopId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el item especificado"));
        if (sourceProductId.equals(target.getId())) {
            throw new ValidationException(
                    "La pieza elegida ya es la pieza solicitada: no hay nada que fabricar");
        }
        // Inventario de origen y de destino, en orden de producto. La pieza de
        // origen se lee DESPUES, dentro de la transformacion: si otro operador
        // acaba de consumirla, ahi ya figura como no disponible.
        Map<UUID, Inventory> locked = lockInventories(workshopId, sourceProductId, target.getId());
        Inventory sourceInventory = Optional.ofNullable(locked.get(sourceProductId))
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el item especificado"));

        // Revision de precio ANTES de tocar nada fisico. El precio lo autorizo
        // el vendedor sin saber que pieza usaria el taller; si la elegida cuesta
        // mas, no se fabrica: la solicitud vuelve al vendedor con el minimo que
        // debe cubrir. El operador no puede cambiar el precio.
        BigDecimal productionCost = productionCost(sourceInventory);
        if (detail.getPrice().compareTo(productionCost) < 0) {
            fulfillment.block(
                    FulfillmentBlockReason.PRICE_REVIEW,
                    "Requiere revision de precio",
                    productionCost);
            fulfillment.setEmployee(employee);
            return save(detail, id);
        }

        Product source = productRepository.findById(sourceProductId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el producto especificado"));

        // Los colochos de la pieza principal son los PEDIDOS: si la venta no los
        // especifico, la pieza se fabrica sin inventarlos.
        TransformationResponseDTO result = transformationService.create(
                new CreateTransformationRequestDTO(
                        requestDTO.inputItem(),
                        null,
                        target.getId(),
                        null,
                        null,
                        outputCurlCount(source.getFamily(), detail, requestDTO.outputCurlCount()),
                        // El sobrante de una comanda lo resuelve la transformacion: sin colochos.
                        null,
                        null),
                CurlCountRequirement.AS_REQUESTED);
        Transformation transformation = transformationRepository.findById(result.id())
                .orElseThrow(() -> new IllegalStateException(
                        "La transformacion recien creada no existe"));
        InventoryItem output = transformation.getItems().stream()
                .filter(item -> item.getRole() == TransformationItemRole.OUTPUT)
                .map(TransformationItem::getItem)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "La transformacion no genero una pieza de salida"));

        assignOutput(detail, output);
        fulfillment.setTransformation(transformation);
        complete(detail);
        return save(detail, id);
    }

    /**
     * Colochos de la pieza principal, con lo que pidio el vendedor
     * ({@link RequestedCurls}) y la regla de la transformacion:
     *
     * <ul>
     * <li>Cambiando de familia, la pieza ES el producto pedido y ya trae los
     * suyos: no se indican. En una familia sin colochos, tampoco.</li>
     * <li>"No lleva colochos": se fabrica con cero —la forma del dominio de
     * decir sin colochos— y el operador no puede agregarle.</li>
     * <li>Cantidad fijada en la venta: mandan los pedidos; el operador no
     * puede fabricar otra.</li>
     * <li>"Lleva", sin cantidad: solo una linea guardada antes de que la
     * cantidad fuera obligatoria (hoy "si" exige cuantos). Ahi el operador PUEDE
     * registrarla (al menos 1); sin ella la pieza queda sin colochos
     * registrados.</li>
     * <li>Linea anterior a la regla: el operador PUEDE registrarla (0 o mas),
     * como hasta ahora.</li>
     * </ul>
     *
     * Lo que registre el operador va a la pieza fabricada; la decision del
     * vendedor en la linea no se toca.
     */
    private Integer outputCurlCount(Family sourceFamily, SaleDetail detail, Integer requested) {
        if (requested != null && requested < 0) {
            throw new ValidationException(
                    "La cantidad de colochos debe ser mayor o igual a cero");
        }
        Product target = detail.getProduct();
        if (!TransformationFamilyCompatibility.sameFamily(sourceFamily, target.getFamily())
                || !supportsCurlCount(sourceFamily)) {
            if (requested != null) {
                throw new ValidationException(
                        "La pieza solicitada no admite indicar colochos");
            }
            return null;
        }
        RequestedCurls ordered = RequestedCurls.of(
                detail.getRequestedCurls(), detail.getRequestedCurlCount(), target.getAttributes());
        if (ordered.excludesCurls()) {
            if (requested != null) {
                throw new ValidationException(
                        "La venta indica que la pieza no lleva colochos: no se le pueden agregar");
            }
            return 0;
        }
        Integer fixed = ordered.count();
        if (fixed != null) {
            if (requested != null && !requested.equals(fixed)) {
                throw new ValidationException(
                        "La pieza solicitada lleva " + fixed + " colochos: no se puede fabricar con otra cantidad");
            }
            return fixed;
        }
        if (Boolean.TRUE.equals(ordered.hasCurls()) && requested != null && requested < 1) {
            throw new ValidationException(
                    "La venta indica que la pieza lleva colochos: registre al menos 1 o deje la cantidad vacía");
        }
        return requested;
    }

    /**
     * La pieza fabricada sale del stock en cuanto nace y queda en la venta, con
     * su costo FIFO y su trabajo asociado, igual que si el vendedor la hubiera
     * agregado desde "Trabajos recientes" —pero sin que tenga que hacerlo—.
     */
    private void assignOutput(SaleDetail detail, InventoryItem output) {
        Inventory inventory = inventoryRepository.findByItemIdForUpdate(output.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "La pieza fabricada no tiene inventario"));
        output.setStatus(InventoryItemStatus.SOLD);
        inventory.removeStock(1);
        Product produced = inventory.getProduct();
        if (!produced.getId().equals(detail.getProduct().getId())) {
            // Lo que el cliente pidio ya figura en su cuenta desde que se
            // agrego: la pieza equivalente no le cambia el nombre en la
            // factura. Sin descripcion alterna, la linea se leia con el nombre
            // del producto, que aqui cambia.
            detail.setInvoiceDescription(detail.resolveInvoiceDescription());
            detail.setProduct(produced);
        }
        // Un inventario recien creado nace sin precio de catalogo; el precio que
        // el vendedor confirmo para esta pieza pasa a ser el vigente, como
        // ocurre al vender cualquier pieza sin precio.
        if (SaleDetailHandler.isUnpriced(inventory.getPrice())) {
            inventory.setPrice(detail.getPrice());
        }
        detail.setItem(output);
        lotService.consumeForSaleDetail(detail, inventory, 1);
        WorkResult workResult = workResultRepository.findByItemId(output.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "La pieza fabricada no tiene su trabajo registrado"));
        detail.setWorkResult(workResult);
    }

    /**
     * El servicio queda ejecutado: se registra en el historial de trabajos con
     * el Operador que lo hizo, y vuelve a la venta sin que el vendedor tenga
     * que buscarlo.
     */
    @Override
    public FulfillmentTaskResponseDTO completeService(UUID id) {
        SaleDetail detail = lockRequest(id);
        requireType(detail.getFulfillment().getType(), FulfillmentType.SERVICE);
        Fulfillment fulfillment = detail.getFulfillment();
        Employee employee = currentUserService.getCurrentEmployee();
        requireWorkable(fulfillment, employee);
        Job job = jobRepository
                .findByProductIdAndWorkshopId(detail.getProduct().getId(), SecurityUtils.getWorkshopId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el servicio especificado"));
        JobLog jobLog = jobLogRepository.save(JobLog.builder()
                .performedDate(now())
                .job(job)
                .employee(employee)
                .build());
        workResultService.createPendingFromJobLog(jobLog);
        WorkResult workResult = workResultRepository.findByJobLogId(jobLog.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "El servicio ejecutado no tiene su trabajo registrado"));
        detail.setWorkResult(workResult);
        fulfillment.setJobLog(jobLog);
        complete(detail);
        return save(detail, id);
    }

    // ── Comun ────────────────────────────────────────────────────

    /**
     * La solicitud, con su venta BLOQUEADA y abierta. El cerrojo se toma antes
     * de leer la venta: una venta ya cargada no se relee por haber esperado.
     */
    private SaleDetail lockRequest(UUID id) {
        UUID workshopId = SecurityUtils.getWorkshopId();
        UUID saleId = detailRepository.findSaleIdByIdAndWorkshopId(id, workshopId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe la solicitud especificada"));
        saleRepository.lockByIdAndWorkshopId(saleId, workshopId);
        Sale sale = saleRepository.findByIdAndWorkshopId(saleId, workshopId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe la solicitud especificada"));
        if (sale.getStatus() != SaleStatus.PENDING) {
            throw new ValidationException(
                    "La venta de esta solicitud ya no esta abierta");
        }
        SaleDetail detail = sale.getDetails().stream()
                .filter(candidate -> candidate.getId().equals(id))
                .findFirst()
                .filter(SaleDetail::isRequest)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe la solicitud especificada"));
        if (detail.getFulfillment().isPending()) {
            throw new ValidationException(
                    "La solicitud todavia no se ha enviado al taller");
        }
        return detail;
    }

    /** Bloquea los inventarios en orden de producto y los devuelve por producto. */
    private Map<UUID, Inventory> lockInventories(UUID workshopId, UUID... productIds) {
        Map<UUID, Inventory> locked = new HashMap<>();
        java.util.Arrays.stream(productIds)
                .sorted()
                .forEach(productId -> inventoryRepository
                        .findByProductIdAndWorkshopIdForUpdate(productId, workshopId)
                        .ifPresent(inventory -> locked.put(productId, inventory)));
        return locked;
    }

    /**
     * Costo real de fabricar desde una pieza de origen, con la regla de la
     * propia transformacion: la pieza resultante hereda el costo del
     * inventario de origen (SpecialTransformationHandler.generateOutputItem) y
     * el sobrante no se lleva ninguno. No se inventa un costo cero: si el
     * inventario no tiene costo registrado, no hay minimo que exigir.
     */
    private static BigDecimal productionCost(Inventory sourceInventory) {
        BigDecimal cost = sourceInventory.getCost();
        return cost == null ? BigDecimal.ZERO : cost;
    }

    private void requireNotAwaitingPriceReview(Fulfillment fulfillment) {
        if (fulfillment.awaitsPriceReview()) {
            throw new ValidationException(
                    "La solicitud espera que el vendedor revise el precio");
        }
    }

    private void requireOperator(SaleDetail detail) {
        if (detail.getFulfillment().area() != FulfillmentArea.OPERATOR) {
            throw new ValidationException(
                    "La solicitud no corresponde al Operador");
        }
    }

    private void requireType(FulfillmentType actual, FulfillmentType expected) {
        if (actual != expected) {
            throw new ValidationException(switch (expected) {
                case STOCK_RELEASE -> "La solicitud no es una entrega de Control";
                case TRANSFORMATION -> "La solicitud no es una pieza para fabricar";
                case SERVICE -> "La solicitud no es un servicio";
            });
        }
    }

    /**
     * Se puede trabajar en una solicitud en cola o bloqueada, o en una en curso
     * si la tomo quien la quiere terminar. Una cumplida o anulada ya no.
     */
    private void requireWorkable(Fulfillment fulfillment, Employee employee) {
        switch (fulfillment.getStatus()) {
            case QUEUED, BLOCKED -> {
                // Libre para quien la quiera trabajar.
            }
            case IN_PROGRESS -> requireTakenBy(fulfillment, employee);
            case FULFILLED -> throw new ValidationException(
                    "La solicitud ya fue cumplida");
            case CANCELLED -> throw new ValidationException(
                    "La solicitud fue anulada");
            case PENDING -> throw new ValidationException(
                    "La solicitud todavia no se ha enviado al taller");
        }
    }

    private void requireTakenBy(Fulfillment fulfillment, Employee employee) {
        Employee owner = fulfillment.getEmployee();
        if (owner != null && !owner.getId().equals(employee.getId())) {
            throw new ValidationException(
                    "La solicitud ya la tomo " + fullName(owner.getFirstName(), owner.getLastName()));
        }
    }

    private void complete(SaleDetail detail) {
        Fulfillment fulfillment = detail.getFulfillment();
        LocalDateTime now = now();
        if (fulfillment.getStartedAt() == null) {
            fulfillment.setStartedAt(now);
        }
        fulfillment.setStatus(FulfillmentStatus.FULFILLED);
        fulfillment.setCompletedAt(now);
        fulfillment.setEmployee(currentUserService.getCurrentEmployee());
        fulfillment.clearBlock();
    }

    private FulfillmentTaskResponseDTO save(SaleDetail detail, UUID id) {
        Sale sale = detail.getSale();
        sale.refreshFulfillmentStatus();
        saleRepository.save(sale);
        return response(id);
    }

    private FulfillmentTaskResponseDTO response(UUID id) {
        FulfillmentTaskRow row = findRow(id);
        return toResponse(row, contextOf(List.of(row), row.type().area()));
    }

    private FulfillmentTaskRow findRow(UUID id) {
        return detailRepository.findFulfillmentTask(id, SecurityUtils.getWorkshopId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe la solicitud especificada"));
    }

    private Set<FulfillmentType> typesOf(FulfillmentArea area) {
        return EnumSet.allOf(FulfillmentType.class).stream()
                .filter(type -> type.area() == area)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(FulfillmentType.class)));
    }

    private int clamp(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    /**
     * Lo que la respuesta necesita ademas de cada fila, para toda la pagina a la
     * vez: existencias, familias con colochos y avance de cada venta en el
     * area. Una consulta por cada cosa, nunca una por fila.
     */
    private record QueueContext(
            Map<UUID, Integer> stock,
            Set<UUID> curlFamilies,
            Map<UUID, FulfillmentSaleProgressDTO> progress) {
    }

    private QueueContext contextOf(List<FulfillmentTaskRow> rows, FulfillmentArea area) {
        return new QueueContext(stockOf(rows), curlFamiliesOf(rows), progressOf(rows, area));
    }

    private Set<UUID> curlFamiliesOf(List<FulfillmentTaskRow> rows) {
        Set<UUID> familyIds = rows.stream()
                .map(FulfillmentTaskRow::familyId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (familyIds.isEmpty()) {
            return Set.of();
        }
        return familyRepository.findIdsDeclaringAttribute(
                familyIds, RequestedCurls.ATTRIBUTE.toLowerCase(java.util.Locale.ROOT));
    }

    private Map<UUID, FulfillmentSaleProgressDTO> progressOf(
            List<FulfillmentTaskRow> rows, FulfillmentArea area) {
        Set<UUID> saleIds = rows.stream()
                .map(FulfillmentTaskRow::saleId)
                .collect(Collectors.toSet());
        if (saleIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, FulfillmentSaleProgressDTO> progress = new HashMap<>();
        for (SaleAreaProgressRow count : detailRepository.countFulfillmentBySale(
                SecurityUtils.getWorkshopId(), saleIds, typesOf(area), DISPATCHED_STATUSES)) {
            progress.put(count.saleId(), new FulfillmentSaleProgressDTO(
                    (int) count.total(), (int) count.fulfilled()));
        }
        return progress;
    }

    /** Existencias de todos los productos de entrega en UNA consulta. */
    private Map<UUID, Integer> stockOf(List<FulfillmentTaskRow> rows) {
        Set<UUID> productIds = rows.stream()
                .filter(row -> row.type() == FulfillmentType.STOCK_RELEASE)
                .map(FulfillmentTaskRow::productId)
                .collect(Collectors.toSet());
        if (productIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Integer> stock = new HashMap<>();
        for (Object[] entry : inventoryRepository.findStockByProductIds(
                SecurityUtils.getWorkshopId(), productIds)) {
            stock.put((UUID) entry[0], (Integer) entry[1]);
        }
        return stock;
    }

    private FulfillmentTaskResponseDTO toResponse(FulfillmentTaskRow row, QueueContext context) {
        boolean registered = row.taxMode() == TaxMode.NIT;
        RequestedCurls requested = requestOf(row);
        return new FulfillmentTaskResponseDTO(
                row.id(),
                row.saleId(),
                registered ? row.registeredCustomerName() : row.saleCustomerName(),
                registered ? row.registeredCustomerNit() : null,
                row.plate(),
                fullName(row.sellerFirstName(), row.sellerLastName()),
                row.type(),
                row.type() == null ? null : row.type().area(),
                row.status(),
                row.note(),
                row.blockReason(),
                row.dispatchedAt(),
                row.startedAt(),
                row.completedAt(),
                fullName(row.employeeFirstName(), row.employeeLastName()),
                new FulfillmentProductDTO(
                        row.productId(),
                        row.productName(),
                        row.productSku(),
                        row.familyId(),
                        row.familyName(),
                        row.modelId(),
                        row.modelName(),
                        row.brandId(),
                        row.brandName(),
                        row.attributes()),
                requested.hasCurls(),
                requested.count(),
                context.curlFamilies().contains(row.familyId()),
                row.quantity(),
                row.instructions(),
                row.itemId(),
                BarcodeIdentity.item(row.itemId()),
                row.type() == FulfillmentType.STOCK_RELEASE
                        ? context.stock().getOrDefault(row.productId(), 0)
                        : null,
                row.transformationId(),
                row.jobLogId(),
                context.progress().getOrDefault(
                        row.saleId(), new FulfillmentSaleProgressDTO(0, 0)));
    }

    /** Al microsegundo, que es lo que guarda la base. */
    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    private static String fullName(String firstName, String lastName) {
        if (firstName == null && lastName == null) {
            return null;
        }
        return ((firstName == null ? "" : firstName) + " "
                + (lastName == null ? "" : lastName)).trim();
    }

    private static UUID modelId(Product product) {
        return product.getModel() == null ? null : product.getModel().getId();
    }

    private boolean supportsCurlCount(Family family) {
        return RequestedCurls.declaresCurlCount(family);
    }

    /** Lo que pidio el vendedor; en una linea anterior a la regla, lo que fija su producto. */
    private static RequestedCurls requestOf(FulfillmentTaskRow row) {
        return RequestedCurls.of(row.requestedCurls(), row.requestedCurlCount(), row.attributes());
    }

}
