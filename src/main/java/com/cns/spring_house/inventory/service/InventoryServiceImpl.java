package com.cns.spring_house.inventory.service;

import java.util.List;
import java.util.UUID;

import com.cns.spring_house.inventory.dto.*;
import com.cns.spring_house.inventory.mapper.InventoryItemMapper;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cns.spring_house.family.entity.FamilyCategory;
import com.cns.spring_house.employee.entity.Employee;
import com.cns.spring_house.inventory.handler.StockEntryContext;
import com.cns.spring_house.supplier.entity.Supplier;
import com.cns.spring_house.supplier.repository.SupplierRepository;
import com.cns.spring_house.shared.security.CurrentUserService;
import com.cns.spring_house.shared.exception.EntityAlreadyExistsException;
import com.cns.spring_house.shared.exception.EntityNotFoundException;
import com.cns.spring_house.shared.exception.ValidationException;
import com.cns.spring_house.shared.security.SecurityUtils;
import com.cns.spring_house.inventory.mapper.InventoryMapper;
import com.cns.spring_house.product.entity.Product;
import com.cns.spring_house.inventory.entity.Inventory;
import com.cns.spring_house.inventory.handler.InventoryHandler;
import com.cns.spring_house.inventory.handler.InventoryHandlerRegistry;
import com.cns.spring_house.workshop.entity.Workshop;
import com.cns.spring_house.product.repository.ProductRepository;
import com.cns.spring_house.inventory.repository.InventoryRepository;
import com.cns.spring_house.inventory.repository.InventorySpecs;
import com.cns.spring_house.workshop.repository.WorkshopRepository;

import lombok.RequiredArgsConstructor;

@Service
@Transactional
@RequiredArgsConstructor
public class InventoryServiceImpl implements InventoryService {

    private final InventoryRepository repository;
    private final WorkshopRepository workshopRespository;
    private final ProductRepository productRepository;
    private final InventoryMapper mapper;
    private final InventoryHandlerRegistry registry;
    private final InventoryItemMapper itemMapper;
    private final SupplierRepository supplierRepository;
    private final CurrentUserService currentUserService;

    @Override
    @Transactional(readOnly = true)
    public InventoryResponseDTO get(UUID id) {
        UUID workshopId = SecurityUtils.getWorkshopId();

        Inventory inventory = repository
                .findByIdAndWorkshopId(id, workshopId)
                .orElseThrow(() ->
                        new EntityNotFoundException(
                                "No existe el registro solicitado"));

        return mapper.toResponse(inventory);
    }

    /**
     * Inventario del taller AUTENTICADO. El alcance sale del token y no de la
     * peticion, igual que en ventas y en servicios.
     *
     * Antes el taller era un filtro mas del `SearchInventoryRequestDTO`, o sea
     * un parametro de consulta: sin el, la respuesta traia el inventario de
     * todos los talleres, y con el UUID de otro traia el de ese otro. Ahora el
     * unico taller posible es el de quien pregunta, de modo que ni omitirlo ni
     * falsearlo cambia nada: no hay nada que omitir ni que falsear.
     *
     * El resto de filtros no se toca: se COMBINAN con el taller, nunca lo
     * sustituyen.
     */
    @Override
    @Transactional(readOnly = true)
    public List<InventoryResponseDTO> getAll(SearchInventoryRequestDTO requestDTO) {
        Specification<Inventory> specs = Specification.allOf(
                InventorySpecs.hasWorkshop(SecurityUtils.getWorkshopId()),
                InventorySpecs.hasProduct(requestDTO.product()),
                InventorySpecs.costBetween(requestDTO.minCost(), requestDTO.maxCost()),
                InventorySpecs.priceBetween(requestDTO.minPrice(), requestDTO.maxPrice()),
                InventorySpecs.isLowStock(requestDTO.isLowStock()));
        return repository.findAll(specs)
                .stream()
                .map(mapper::toResponse)
                .toList();
    }

    @Override
    public InventoryResponseDTO create(CreateInventoryRequestDTO requestDTO) {
        UUID workshopId = SecurityUtils.getWorkshopId();
        boolean alreadyExists = repository.existsByProductIdAndWorkshopId(
                requestDTO.product(), workshopId);
        if (alreadyExists) {
            throw new EntityAlreadyExistsException(
                    "Ya existe un registro para ese producto en ese taller");
        }
        Workshop workshop = workshopRespository
                .findById(workshopId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el taller especificado"));
        Product product = productRepository
                .findById(requestDTO.product())
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el producto especificado"));
        if (product.getFamily().getCategory() == FamilyCategory.JOB) {
            throw new ValidationException(
                    "No se puede crear un inventario de un servicio");
        }
        Inventory inventory = mapper.toInventory(requestDTO);
        inventory.setWorkshop(workshop);
        inventory.setProduct(product);
        inventory = repository.save(inventory);
        return mapper.toResponse(inventory);
    }

    @Override
    public InventoryResponseDTO update(
            UUID id,
            UpdateInventoryRequestDTO requestDTO) {

        UUID workshopId = SecurityUtils.getWorkshopId();

        Inventory inventory = repository
                .findByIdAndWorkshopId(id, workshopId)
                .orElseThrow(() ->
                        new EntityNotFoundException(
                                "No existe el registro especificado"));

        mapper.updateInventoryFromDTO(
                requestDTO,
                inventory);

        inventory = repository.save(inventory);

        return mapper.toResponse(inventory);
    }

    /**
     * Entrada de inventario: UNA transaccion, o queda todo o no queda nada.
     *
     * Dentro de la misma frontera transaccional ocurren cuatro cosas que antes
     * no podian estar todas: se valida que el proveedor sea de este taller, se
     * crea el LOTE con su costo, se incrementan las existencias y se actualiza
     * el costo de referencia. Si cualquiera falla, no queda ni lote huerfano ni
     * stock incrementado sin respaldo.
     *
     * La comprobacion de proveedor y costo se hace AQUI y no en el DTO porque
     * depende de la categoria: una compra los exige, el armado de una maleta no
     * los tiene. Bean Validation no puede expresar "obligatorio salvo si el
     * producto es de tipo contenedor" sin partir el DTO en dos.
     */
    @Override
    public InventoryResponseDTO addStock(
            UUID id,
            AddStockRequestDTO requestDTO) {

        UUID workshopId = SecurityUtils.getWorkshopId();

        // El cerrojo se toma sobre el inventario ANTES de nada. Es lo que
        // serializa dos entradas simultaneas del mismo producto: sin el, las dos
        // leerian el mismo stock y la segunda pisaria el incremento de la
        // primera.
        Inventory inventory = repository
                .findByIdAndWorkshopIdForUpdate(
                        id,
                        workshopId)
                .orElseThrow(() ->
                        new EntityNotFoundException(
                                "No existe el inventario especificado"));

        FamilyCategory category = inventory.getProduct()
                .getFamily()
                .getCategory();

        InventoryHandler handler = registry.get(category);

        StockEntryContext context = resolveStockEntryContext(
                category, requestDTO, workshopId);

        handler.addStock(
                inventory,
                requestDTO,
                context);

        // El costo de referencia pasa a ser el de la ULTIMA compra. Es solo eso:
        // una referencia para el catalogo y para el piso de precio de venta. La
        // verdad historica vive en los lotes, que no se reescriben nunca. Ver
        // la documentacion de `Inventory.cost`.
        if (context.supplier() != null && requestDTO.unitCost() != null) {
            inventory.setCost(requestDTO.unitCost());
        }

        inventory = repository.save(inventory);

        return mapper.toResponse(inventory);
    }

    /**
     * Resuelve lo que aporta el servidor a la entrada.
     *
     * Armar una maleta no lleva proveedor: sus componentes ya eran del taller.
     * Cualquier otra entrada es una compra y exige proveedor, tipo y costo, los
     * tres. El proveedor se busca SIEMPRE acotado al taller del token, de modo
     * que un UUID de otro taller responde igual que uno inexistente.
     */
    private StockEntryContext resolveStockEntryContext(
            FamilyCategory category,
            AddStockRequestDTO requestDTO,
            UUID workshopId) {

        Employee employee = currentUserService.getCurrentEmployee();

        if (category == FamilyCategory.CONTAINER) {
            if (requestDTO.supplier() != null
                    || requestDTO.acquisitionType() != null
                    || requestDTO.unitCost() != null) {
                throw new ValidationException(
                        "Armar un contenedor no es una compra: no admite proveedor, "
                        + "tipo de adquisicion ni costo unitario");
            }
            return new StockEntryContext(null, employee);
        }

        if (requestDTO.supplier() == null) {
            throw new ValidationException("El proveedor es obligatorio");
        }
        if (requestDTO.acquisitionType() == null) {
            throw new ValidationException(
                    "El tipo de adquisicion es obligatorio");
        }
        if (requestDTO.unitCost() == null) {
            throw new ValidationException("El costo unitario es obligatorio");
        }

        Supplier supplier = supplierRepository
                .findByIdAndWorkshopId(requestDTO.supplier(), workshopId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el proveedor especificado"));

        return new StockEntryContext(supplier, employee);
    }

    @Override
    @Transactional(readOnly = true)
    public List<InventoryItemResponseDTO> getItems(
            UUID inventoryId) {

        UUID workshopId = SecurityUtils.getWorkshopId();

        Inventory inventory = repository
                .findByIdAndWorkshopId(
                        inventoryId,
                        workshopId)
                .orElseThrow(() ->
                        new EntityNotFoundException(
                                "No existe el inventario especificado"));

        return itemMapper.toResponseList(
                inventory.getItems());
    }

}
