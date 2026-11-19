package com.cns.spring_house.sale.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cns.spring_house.family.entity.Family;
import com.cns.spring_house.family.entity.FamilyCategory;
import com.cns.spring_house.family.repository.FamilyRepository;
import com.cns.spring_house.inventory.entity.Inventory;
import com.cns.spring_house.inventory.entity.InventoryItemStatus;
import com.cns.spring_house.inventory.repository.InventoryItemRepository;
import com.cns.spring_house.inventory.repository.InventoryRepository;
import com.cns.spring_house.job.entity.Job;
import com.cns.spring_house.job.repository.JobRepository;
import com.cns.spring_house.product.entity.Product;
import com.cns.spring_house.product.repository.ProductRepository;
import com.cns.spring_house.product.service.ProductAttributesValidator;
import com.cns.spring_house.acquisition.repository.InventoryAcquisitionRepository;
import com.cns.spring_house.sale.dto.NoChargeEligibility;
import com.cns.spring_house.sale.dto.PriceReferenceDTO;
import com.cns.spring_house.sale.dto.PriceReferenceSource;
import com.cns.spring_house.sale.dto.PriceStatus;
import com.cns.spring_house.sale.dto.SaleLineGuidanceDTO;
import com.cns.spring_house.sale.dto.SaleLineGuidanceRequestDTO;
import com.cns.spring_house.sale.entity.SaleStatus;
import com.cns.spring_house.sale.handler.SaleDetailHandler;
import com.cns.spring_house.sale.repository.SaleDetailRepository;
import com.cns.spring_house.sale.repository.projection.CompletedPriceRow;
import com.cns.spring_house.shared.exception.EntityNotFoundException;
import com.cns.spring_house.shared.exception.ValidationException;
import com.cns.spring_house.shared.security.SecurityUtils;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

/**
 * De donde sale cada cifra que la caja propone, en este orden y sin inventar
 * ninguna:
 *
 * <ol>
 * <li><b>Precio de catalogo del taller</b> para la pieza exacta
 * (`inventory.price`, o `job.price` en un servicio) mayor que cero: es el
 * precio vigente y no hace falta buscar nada mas (AVAILABLE).</li>
 * <li><b>Ultimo precio cobrado</b> por esa misma pieza en una venta COMPLETADA
 * de este taller (LAST_SALE).</li>
 * <li><b>Pieza equivalente</b>: el precio de catalogo, en este taller, de otro
 * registro con la misma familia, modelo y exactamente las mismas
 * caracteristicas (EQUIVALENT_PIECE). Rotulada como referencia a
 * confirmar.</li>
 * <li>Nada de lo anterior: sin referencia confiable (REQUIRED). No se propone
 * ninguna cifra. Si hay piezas SIMILARES —una sola medida distinta— se
 * devuelven como contexto (SIMILAR_PIECE): la mas cercana por debajo y la mas
 * cercana por encima de esa medida, con la diferencia a la vista. Nunca pasan
 * a ser la sugerencia: no existe una regla comercial que diga cuanto vale una
 * medida distinta, y no se inventa una.</li>
 * </ol>
 *
 * Lo que NO se usa, a proposito: el costo como precio (no hay politica de
 * margen autorizada en el sistema) ni precios de otros talleres. El costo solo
 * aparece como lo que es, el minimo que el servidor exige.
 *
 * Una referencia por debajo de ese minimo se informa pero no se sugiere: el
 * servidor la rechazaria al agregar.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class SaleLineGuidanceServiceImpl implements SaleLineGuidanceService {

    /** Piezas equivalentes que se muestran como mucho. */
    static final int MAX_EQUIVALENT = 2;
    /**
     * Candidatas a comparable que se leen como mucho. Acota la consulta en un
     * taller con muchas piezas de la misma familia y modelo; se toman las de
     * inventario modificado mas recientemente.
     */
    static final int MAX_COMPARABLE_CANDIDATES = 200;

    private final ProductRepository productRepository;
    /** Los lotes vivos, para saber si el costo de lo que queda esta registrado. */
    private final InventoryAcquisitionRepository acquisitionRepository;
    private final FamilyRepository familyRepository;
    private final InventoryRepository inventoryRepository;
    private final InventoryItemRepository itemRepository;
    private final JobRepository jobRepository;
    private final SaleDetailRepository detailRepository;
    private final ObjectMapper objectMapper;

    /** La linea pedida, venga de un producto o de una especificacion. */
    private record Target(
            Family family,
            UUID modelId,
            Map<String, Object> attributes,
            Product product) {
    }

    /** Precio vigente, costo y donde quedaria guardado el precio confirmado. */
    private record Catalog(
            BigDecimal price,
            BigDecimal cost,
            boolean hasHome,
            Integer availableUnits,
            UUID inventoryId) {
    }

    @Override
    public SaleLineGuidanceDTO guide(SaleLineGuidanceRequestDTO requestDTO) {
        UUID workshopId = SecurityUtils.getWorkshopId();
        Target target = resolveTarget(requestDTO);
        Catalog catalog = catalogOf(target, workshopId);
        BigDecimal minimum = catalog.cost() != null && catalog.cost().signum() > 0
                ? catalog.cost()
                : null;
        UUID productId = target.product() == null ? null : target.product().getId();

        NoChargeEligibility noCharge = noChargeOf(target, catalog);

        if (!SaleDetailHandler.isUnpriced(catalog.price())) {
            return new SaleLineGuidanceDTO(
                    PriceStatus.AVAILABLE,
                    catalog.price(),
                    false,
                    minimum,
                    null,
                    List.of(),
                    catalog.availableUnits(),
                    productId,
                    noCharge);
        }

        List<PriceReferenceDTO> references = new ArrayList<>();
        if (target.product() != null) {
            lastChargedPrice(target.product(), workshopId, minimum).ifPresent(references::add);
        }
        if (target.family().getCategory() != FamilyCategory.JOB) {
            references.addAll(comparables(target, workshopId, minimum));
        }
        // Solo lo confiable se propone: la misma pieza cobrada o una
        // equivalente. Una similar es contexto, nunca la sugerencia.
        PriceReferenceDTO suggestion = references.stream()
                .filter(reference -> reference.source() != PriceReferenceSource.SIMILAR_PIECE)
                .filter(reference -> !reference.belowMinimum())
                .findFirst()
                .orElse(null);
        List<PriceReferenceDTO> ordered = new ArrayList<>(references);
        if (suggestion != null) {
            ordered.remove(suggestion);
            ordered.addFirst(suggestion);
        }
        return new SaleLineGuidanceDTO(
                suggestion != null ? PriceStatus.SUGGESTED : PriceStatus.REQUIRED,
                null,
                catalog.hasHome(),
                minimum,
                suggestion,
                List.copyOf(ordered),
                catalog.availableUnits(),
                productId,
                noCharge);
    }

    /**
     * La regla de "sin cargo" del servidor, adelantada para la caja. Al
     * agregar se decide otra vez con las unidades que de verdad se toman
     * (`SaleDetailHandler.requireRecordedZeroCost`); aqui se mira lo que queda
     * en los lotes vivos, asi que nunca ofrece un Q0 que el alta rechazaria por
     * costo desconocido o positivo. Desconocido no es cero.
     */
    private NoChargeEligibility noChargeOf(Target target, Catalog catalog) {
        FamilyCategory category = target.family().getCategory();
        if (category == FamilyCategory.SPECIAL) {
            return NoChargeEligibility.REQUEST;
        }
        if (category == FamilyCategory.JOB) {
            return NoChargeEligibility.COST_UNKNOWN;
        }
        if (SaleDetailHandler.isUnpriced(catalog.price())) {
            return NoChargeEligibility.NO_CATALOG;
        }
        if (catalog.cost() != null && catalog.cost().signum() > 0) {
            return NoChargeEligibility.COST;
        }
        UUID inventoryId = catalog.inventoryId();
        if (inventoryId == null
                || acquisitionRepository.existsByInventoryIdAndQuantityRemainingGreaterThanAndUnitCostIsNull(inventoryId, 0)) {
            return NoChargeEligibility.COST_UNKNOWN;
        }
        if (acquisitionRepository.existsByInventoryIdAndQuantityRemainingGreaterThanAndUnitCostGreaterThan(
                inventoryId, 0, BigDecimal.ZERO)) {
            return NoChargeEligibility.COST;
        }
        return NoChargeEligibility.ALLOWED;
    }

    // ── La pieza pedida ──────────────────────────────────────────

    private Target resolveTarget(SaleLineGuidanceRequestDTO requestDTO) {
        boolean byProduct = requestDTO.product() != null;
        boolean bySpecification = requestDTO.family() != null;
        if (byProduct == bySpecification) {
            throw new ValidationException(
                    "Indique el producto o la familia y las caracteristicas de la pieza, no ambos");
        }
        if (byProduct) {
            Product product = productRepository.findById(requestDTO.product())
                    .orElseThrow(() -> new EntityNotFoundException(
                            "No existe el producto especificado"));
            return new Target(
                    product.getFamily(),
                    product.getModel() == null ? null : product.getModel().getId(),
                    product.getAttributes() == null ? Map.of() : product.getAttributes(),
                    product);
        }

        Family family = familyRepository.findById(requestDTO.family())
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe la familia especificada"));
        if (family.getCategory() != FamilyCategory.SPECIAL) {
            throw new ValidationException(
                    "Solo se pueden solicitar piezas de una familia especial");
        }
        if (Boolean.TRUE.equals(family.getRequiresModel()) && requestDTO.model() == null) {
            throw new ValidationException("El modelo es obligatorio para esta familia");
        }
        if (!Boolean.TRUE.equals(family.getRequiresModel()) && requestDTO.model() != null) {
            throw new ValidationException("La familia especificada no lleva modelo");
        }
        if (requestDTO.attributes() == null || requestDTO.attributes().isEmpty()) {
            throw new ValidationException("Indique las caracteristicas de la pieza");
        }
        // La misma regla que aplicara "Solicitar con estas caracteristicas":
        // no se orienta sobre una pieza que el catalogo no admitiria.
        Map<String, Object> attributes = new HashMap<>(requestDTO.attributes());
        ProductAttributesValidator.validate(family, attributes);
        Product existing = productRepository
                .findTargetProduct(family.getId(), requestDTO.model(),
                        objectMapper.writeValueAsString(attributes))
                .orElse(null);
        return new Target(family, requestDTO.model(), attributes, existing);
    }

    // ── Catalogo del taller ──────────────────────────────────────

    private Catalog catalogOf(Target target, UUID workshopId) {
        if (target.family().getCategory() == FamilyCategory.JOB) {
            Job job = jobRepository
                    .findByProductIdAndWorkshopId(target.product().getId(), workshopId)
                    .orElseThrow(() -> new EntityNotFoundException(
                            "No existe el servicio especificado"));
            return new Catalog(job.getPrice(), null, true, null, null);
        }
        Optional<Inventory> inventory = target.product() == null
                ? Optional.empty()
                : inventoryRepository.findByProductIdAndWorkshopId(target.product().getId(), workshopId);
        if (inventory.isEmpty()) {
            // Sin inventario en el taller: nada que vender de stock y ningun
            // lugar donde el precio confirmado quede como catalogo.
            return new Catalog(null, null, false, 0, null);
        }
        Inventory found = inventory.get();
        return new Catalog(found.getPrice(), found.getCost(), true, availableUnits(target, found), found.getId());
    }

    /**
     * Lo que el alta podria tomar AHORA de lo terminado. Una pieza especial o
     * una maleta se cuentan por unidades fisicas libres —las que reservaria
     * `SaleRequestLines.addSpecial`—; una pieza normal, por sus existencias.
     */
    private Integer availableUnits(Target target, Inventory inventory) {
        FamilyCategory category = target.family().getCategory();
        if (category == FamilyCategory.SPECIAL || category == FamilyCategory.CONTAINER) {
            return itemRepository.countByInventoryAndStatus(inventory, InventoryItemStatus.AVAILABLE);
        }
        return inventory.getStock() == null ? 0 : Math.max(inventory.getStock(), 0);
    }

    // ── Referencias ──────────────────────────────────────────────

    private Optional<PriceReferenceDTO> lastChargedPrice(
            Product product, UUID workshopId, BigDecimal minimum) {
        List<CompletedPriceRow> rows = detailRepository.findChargedPrices(
                product.getId(), workshopId, SaleStatus.COMPLETED, PageRequest.of(0, 1));
        return rows.stream().findFirst().map(row -> new PriceReferenceDTO(
                PriceReferenceSource.LAST_SALE,
                row.price(),
                row.completedAt(),
                product.getId(),
                product.getName(),
                product.getSku(),
                List.of(),
                isBelow(row.price(), minimum)));
    }

    /**
     * Piezas del taller de la misma familia y modelo que sirven de referencia:
     * hasta {@link #MAX_EQUIVALENT} equivalentes y, por cada medida que
     * difiera, la similar mas cercana por debajo y la mas cercana por encima.
     * Orden determinista: equivalentes primero; luego por nombre de la medida,
     * por debajo antes que por encima; a igualdad, el orden de la consulta y
     * el id del producto.
     */
    private List<PriceReferenceDTO> comparables(
            Target target, UUID workshopId, BigDecimal minimum) {
        PageRequest page = PageRequest.of(0, MAX_COMPARABLE_CANDIDATES);
        List<Inventory> candidates = target.modelId() == null
                ? inventoryRepository.findPricedComparablesWithoutModel(
                        workshopId, target.family().getId(), page)
                : inventoryRepository.findPricedComparables(
                        workshopId, target.family().getId(), target.modelId(), page);

        List<Ranked> equivalent = new ArrayList<>();
        // medida -> [mas cercana por debajo, mas cercana por encima]
        Map<String, Ranked[]> bracket = new TreeMap<>();
        for (int position = 0; position < candidates.size(); position++) {
            Inventory candidate = candidates.get(position);
            Product product = candidate.getProduct();
            if (target.product() != null && product.getId().equals(target.product().getId())) {
                continue;
            }
            int order = position;
            ComparablePieces.compare(target.attributes(), product.getAttributes()).ifPresent(comparison -> {
                Ranked ranked = new Ranked(candidate, comparison, order);
                if (comparison.kind() == ComparablePieces.Kind.EQUIVALENT) {
                    equivalent.add(ranked);
                    return;
                }
                Ranked[] sides = bracket.computeIfAbsent(comparison.attribute(), ignored -> new Ranked[2]);
                int side = comparison.gap().signum() < 0 ? 0 : 1;
                if (sides[side] == null || closer(ranked, sides[side])) {
                    sides[side] = ranked;
                }
            });
        }

        List<PriceReferenceDTO> references = new ArrayList<>();
        equivalent.stream()
                .sorted(Comparator.comparingInt(Ranked::order))
                .limit(MAX_EQUIVALENT)
                .forEach(r -> references.add(reference(PriceReferenceSource.EQUIVALENT_PIECE, r, minimum)));
        bracket.values().forEach(sides -> {
            for (Ranked side : sides) {
                if (side != null) {
                    references.add(reference(PriceReferenceSource.SIMILAR_PIECE, side, minimum));
                }
            }
        });
        return references;
    }

    /** Una candidata aceptada, con su posicion en la consulta para desempatar. */
    private record Ranked(Inventory inventory, ComparablePieces.Comparison comparison, int order) {
    }

    /**
     * Mas cerca en la medida que difiere; a igual distancia, la que la
     * consulta trajo antes (inventario modificado mas recientemente) y, si
     * aun empatan, el id del producto.
     */
    private static boolean closer(Ranked candidate, Ranked current) {
        int byDistance = candidate.comparison().gap().abs().compareTo(current.comparison().gap().abs());
        if (byDistance != 0) {
            return byDistance < 0;
        }
        if (candidate.order() != current.order()) {
            return candidate.order() < current.order();
        }
        return candidate.inventory().getProduct().getId()
                .compareTo(current.inventory().getProduct().getId()) < 0;
    }

    private static PriceReferenceDTO reference(
            PriceReferenceSource source, Ranked ranked, BigDecimal minimum) {
        Product product = ranked.inventory().getProduct();
        return new PriceReferenceDTO(
                source,
                ranked.inventory().getPrice(),
                null,
                product.getId(),
                product.getName(),
                product.getSku(),
                ranked.comparison().differences(),
                isBelow(ranked.inventory().getPrice(), minimum));
    }

    private static boolean isBelow(BigDecimal price, BigDecimal minimum) {
        return minimum != null && price.compareTo(minimum) < 0;
    }

}
