package com.cns.spring_house.sale.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.cns.spring_house.acquisition.service.InventoryLotService;
import com.cns.spring_house.family.entity.Family;
import com.cns.spring_house.family.entity.FamilyCategory;
import com.cns.spring_house.family.repository.FamilyRepository;
import com.cns.spring_house.inventory.entity.Inventory;
import com.cns.spring_house.inventory.entity.InventoryItem;
import com.cns.spring_house.inventory.entity.InventoryItemStatus;
import com.cns.spring_house.inventory.repository.InventoryItemRepository;
import com.cns.spring_house.inventory.repository.InventoryRepository;
import com.cns.spring_house.model.entity.Model;
import com.cns.spring_house.model.repository.ModelRepository;
import com.cns.spring_house.product.entity.Product;
import com.cns.spring_house.product.repository.ProductRepository;
import com.cns.spring_house.product.service.ProductAttributesValidator;
import com.cns.spring_house.sale.dto.RequestSpecificationDTO;
import com.cns.spring_house.sale.entity.Fulfillment;
import com.cns.spring_house.sale.entity.FulfillmentType;
import com.cns.spring_house.sale.entity.Sale;
import com.cns.spring_house.sale.entity.SaleDetail;
import com.cns.spring_house.sale.handler.SaleDetailHandler;
import com.cns.spring_house.sale.handler.SaleDetailHandler.PricingOutcome;
import com.cns.spring_house.sale.handler.SaleDetailHandlerRegistry;
import com.cns.spring_house.sale.repository.SaleDetailRepository;
import com.cns.spring_house.shared.constants.ColumnLimits;
import com.cns.spring_house.shared.exception.EntityNotFoundException;
import com.cns.spring_house.shared.exception.ValidationException;
import com.cns.spring_house.shared.security.SecurityUtils;
import com.cns.spring_house.transformation.handler.SpecialPieceRules;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

/**
 * Las lineas que el vendedor PIDE al taller en lugar de sacarlas del inventario.
 *
 * Una pieza especial pedida describe lo que el cliente quiere recibir. Al
 * agregarla, con el inventario bloqueado, las unidades terminadas libres
 * quedan reservadas para esta venta y van a Control; las que faltan las
 * fabrica el Operador. Una unidad no se le promete a dos clientes: la segunda
 * venta ya no la ve libre.
 */
@Component
@RequiredArgsConstructor
public class SaleRequestLines {

    private final SaleDetailHandlerRegistry registry;
    private final InventoryRepository inventoryRepository;
    private final InventoryItemRepository itemRepository;
    private final InventoryLotService lotService;
    private final SaleDetailRepository detailRepository;
    private final FamilyRepository familyRepository;
    private final ModelRepository modelRepository;
    private final ProductRepository productRepository;
    private final ObjectMapper objectMapper;

    /**
     * Una linea por UNIDAD pedida.
     *
     * Cada unidad se cumple por separado —una sale de stock y otra se fabrica—
     * y una pieza especial es siempre una unidad fisica por linea, que es lo que
     * la factura, la reversion y el costo FIFO ya esperan. Pedir tres son tres
     * lineas al mismo precio: el total facturado es el mismo y cada unidad se
     * cobra exactamente una vez.
     *
     * La ruta se decide AQUI, con el stock de este instante leido bajo
     * cerrojo, no con el que vio el vendedor al buscar: pedir diez con seis
     * libres reserva esas seis (Control) y deja cuatro por fabricar
     * (Operador). Enviar la comanda ya no vuelve a reservar nada.
     *
     * Los colochos pedidos se validan contra la pieza ({@link RequestedCurls})
     * y quedan en CADA linea: una unidad entregada de stock y una fabricada son
     * la misma pieza pedida.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<SaleDetail> addSpecial(
            Sale sale,
            Product product,
            Integer quantity,
            BigDecimal requestedPrice,
            String invoiceDescription,
            String instructions,
            Boolean requestedCurls,
            Integer requestedCurlCount) {
        if (product.getFamily().getCategory() != FamilyCategory.SPECIAL) {
            throw new ValidationException(
                    "Solo una pieza especial se solicita al taller");
        }
        RequestedCurls curls = RequestedCurls.forNewLine(product, requestedCurls, requestedCurlCount);
        int units = quantity == null ? 1 : quantity;
        if (units < 1 || units > ColumnLimits.FULFILLMENT_MAX_UNITS) {
            throw new ValidationException(
                    "La cantidad debe estar entre 1 y "
                    + ColumnLimits.FULFILLMENT_MAX_UNITS);
        }

        // Puede no haber inventario: un producto del catalogo que el taller
        // nunca ha tenido, o una pieza recien descrita. Sin inventario no hay
        // precio de catalogo ni costo conocido, y la regla de siempre exige
        // entonces un precio explicito mayor que cero.
        Optional<Inventory> inventory = inventoryRepository
                .findByProductIdAndWorkshopIdForUpdate(
                        product.getId(), SecurityUtils.getWorkshopId());
        SaleDetailHandler handler = registry.get(FamilyCategory.SPECIAL);
        PricingOutcome pricing = handler.resolvePricing(
                requestedPrice,
                inventory.map(Inventory::getPrice).orElse(null),
                inventory.map(Inventory::getCost).orElse(null));
        if (pricing.becomesCatalogPrice()) {
            inventory.ifPresent(found -> found.setPrice(pricing.price()));
        }

        // Las unidades terminadas libres, bloqueadas y de la mas antigua a la
        // mas nueva. "Disponible" ya deja fuera lo reservado por otra venta
        // (SOLD) y los componentes metidos en una maleta (UNAVAILABLE).
        List<InventoryItem> free = inventory
                .map(found -> itemRepository.findItemsForUpdate(
                        found.getId(),
                        InventoryItemStatus.AVAILABLE,
                        PageRequest.of(0, units)))
                .orElse(List.of());

        List<SaleDetail> details = new ArrayList<>();
        for (int unit = 0; unit < units; unit++) {
            boolean fromStock = unit < free.size();
            SaleDetail detail = SaleDetail.builder()
                    .product(product)
                    .quantity(1)
                    .price(pricing.price())
                    .subTotal(pricing.price())
                    .invoiceDescription(invoiceDescription)
                    .instructions(instructions)
                    .requestedCurls(curls.hasCurls())
                    .requestedCurlCount(curls.count())
                    .fulfillment(Fulfillment.pending(fromStock
                            ? FulfillmentType.STOCK_RELEASE
                            : FulfillmentType.TRANSFORMATION))
                    .build();
            sale.addDetail(detail);
            if (fromStock) {
                reserve(detail, free.get(unit), inventory.get());
            }
            details.add(detail);
        }
        return details;
    }

    /**
     * La reserva de siempre: la unidad pasa a SOLD, sale del stock y su costo
     * se toma del lote FIFO en este mismo instante. Quitar la linea o anular la
     * venta la devuelven al stock y a su lote; Control puede despues entregar
     * otra unidad equivalente en su lugar.
     */
    private void reserve(SaleDetail detail, InventoryItem item, Inventory inventory) {
        item.setStatus(InventoryItemStatus.SOLD);
        inventory.removeStock(1);
        detail.setItem(item);
        // La asignacion de lotes apunta al detalle por clave ajena: tiene que
        // estar persistido antes, igual que en una linea normal.
        detailRepository.saveAndFlush(detail);
        lotService.consumeForSaleDetail(detail, inventory, 1);
    }

    /**
     * "Solicitar con estas caracteristicas": resuelve el producto del catalogo
     * que describe la pieza pedida, creandolo si todavia no existe.
     *
     * El producto se crea SIN inventario ni existencias: describe lo que el
     * cliente quiere, no una pieza que el taller tenga. Si ya hay uno con la
     * misma familia, modelo y caracteristicas se reutiliza —es la misma regla
     * con la que la transformacion reutiliza piezas equivalentes—, de modo que
     * pedir dos veces lo mismo no duplica el catalogo.
     *
     * Las caracteristicas se validan con la regla del alta de productos: lo que
     * el catalogo rechazaria, aqui tambien se rechaza.
     */
    /**
     * El minimo de siempre para el precio de una pieza pedida: el costo de su
     * inventario en el taller, si lo tiene. Sin inventario no hay costo
     * conocido todavia, y no se inventa uno.
     */
    public BigDecimal minimumPrice(Product product) {
        return inventoryRepository
                .findByProductIdAndWorkshopId(product.getId(), SecurityUtils.getWorkshopId())
                .map(Inventory::getCost)
                .orElse(BigDecimal.ZERO);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Product resolveSpecification(RequestSpecificationDTO requestDTO) {
        // Bloqueada: entre buscar el producto y crearlo, otra venta que pida la
        // misma pieza nueva espera y despues lo encuentra, en vez de duplicarlo.
        Family family = familyRepository.findByIdForUpdate(requestDTO.family())
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe la familia especificada"));
        if (family.getCategory() != FamilyCategory.SPECIAL) {
            throw new ValidationException(
                    "Solo se pueden solicitar piezas de una familia especial");
        }
        Model model = resolveModel(family, requestDTO);
        Map<String, Object> attributes = new HashMap<>(requestDTO.attributes());
        ProductAttributesValidator.validate(family, attributes);
        RequestedCurls.validateSpecified(attributes);

        String json = objectMapper.writeValueAsString(attributes);
        return productRepository
                .findTargetProduct(family.getId(), model == null ? null : model.getId(), json)
                .orElseGet(() -> productRepository.save(Product.builder()
                        .name(SpecialPieceRules.generatedName(family, attributes))
                        .sku(SpecialPieceRules.generatedSku())
                        .family(family)
                        .model(model)
                        .attributes(attributes)
                        .build()));
    }

    private Model resolveModel(Family family, RequestSpecificationDTO requestDTO) {
        if (!Boolean.TRUE.equals(family.getRequiresModel())) {
            if (requestDTO.model() != null) {
                throw new ValidationException(
                        "La familia especificada no lleva modelo");
            }
            return null;
        }
        if (requestDTO.model() == null) {
            throw new ValidationException(
                    "El modelo es obligatorio para esta familia");
        }
        return modelRepository.findById(requestDTO.model())
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el modelo especificado"));
    }

}
