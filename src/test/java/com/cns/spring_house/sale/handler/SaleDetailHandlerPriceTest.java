package com.cns.spring_house.sale.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.cns.spring_house.inventory.entity.Inventory;
import com.cns.spring_house.inventory.entity.InventoryItem;
import com.cns.spring_house.inventory.entity.InventoryItemStatus;
import com.cns.spring_house.inventory.repository.InventoryItemRepository;
import com.cns.spring_house.acquisition.service.InventoryLotService;
import com.cns.spring_house.inventory.repository.InventoryRepository;
import com.cns.spring_house.sale.repository.SaleDetailRepository;
import com.cns.spring_house.job.entity.Job;
import com.cns.spring_house.job.repository.JobRepository;
import com.cns.spring_house.product.entity.Product;
import com.cns.spring_house.sale.dto.AddSaleDetailRequestDTO;
import com.cns.spring_house.sale.entity.Sale;
import com.cns.spring_house.sale.entity.SaleDetail;
import com.cns.spring_house.shared.exception.EntityNotFoundException;
import com.cns.spring_house.shared.exception.ValidationException;
import com.cns.spring_house.workshop.entity.Workshop;

/**
 * El piso de precio es el COSTO DE ADQUISICION, no el precio de catalogo: rebajar
 * el precio es una decision comercial valida, vender bajo costo es perdida.
 *
 * La fuente del costo es `Inventory.cost`, la unica que el modelo registra hoy.
 */
@ExtendWith(MockitoExtension.class)
class SaleDetailHandlerPriceTest {

    @Mock
    private InventoryRepository inventoryRepository;

    @Mock
    private InventoryItemRepository inventoryItemRepository;

    @Mock
    private JobRepository jobRepository;

    /**
     * El libro de lotes es una dependencia nueva de los handlers de producto.
     * Aqui se simula porque lo que esta prueba mide es el PRECIO —el piso contra
     * el costo de referencia y el ajuste manual—, no el consumo FIFO, que tiene
     * sus propias pruebas contra PostgreSQL real.
     */
    @Mock
    private InventoryLotService lotService;

    @Mock
    private SaleDetailRepository saleDetailRepository;

    @InjectMocks
    private NormalSaleDetailHandler normalHandler;

    @InjectMocks
    private SpecialSaleDetailHandler specialHandler;

    @InjectMocks
    private ContainerSaleDetailHandler containerHandler;

    @InjectMocks
    private JobSaleDetailHandler jobHandler;

    private final UUID workshopId = UUID.randomUUID();
    private final Workshop workshop = Workshop.builder().id(workshopId).build();
    private final Product product = Product.builder()
            .id(UUID.randomUUID())
            .name("PRODUCTO")
            .build();

    private static final BigDecimal CATALOG_PRICE = new BigDecimal("100.00");
    private static final BigDecimal COST = new BigDecimal("60.00");

    @BeforeEach
    void setUpAuthentication() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject(UUID.randomUUID().toString())
                .claim("workshop", workshopId.toString())
                .build();
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    private Inventory inventory(BigDecimal price, BigDecimal cost) {
        return Inventory.builder()
                .id(UUID.randomUUID())
                .workshop(workshop)
                .product(product)
                .price(price)
                .cost(cost)
                .stock(10)
                .build();
    }

    private InventoryItem item(Inventory inventory) {
        InventoryItem item = InventoryItem.builder()
                .id(UUID.randomUUID())
                .status(InventoryItemStatus.AVAILABLE)
                .build();
        item.setInventory(inventory);
        return item;
    }

    private Sale sale() {
        return Sale.builder().id(UUID.randomUUID()).workshop(workshop).build();
    }

    private AddSaleDetailRequestDTO request(BigDecimal price, UUID itemId) {
        return new AddSaleDetailRequestDTO(
                product.getId(), itemId, null, 1, price, null);
    }

    // ---------------------------------------------------------------- NORMAL

    private SaleDetail addNormal(BigDecimal price) {
        when(inventoryRepository.findByProductIdAndWorkshopIdForUpdate(
                product.getId(), workshopId))
                .thenReturn(Optional.of(inventory(CATALOG_PRICE, COST)));
        return normalHandler.addDetail(sale(), request(price, null));
    }

    @Test
    void normalUsesTheCatalogPriceWhenNoSpecialPriceIsSent() {
        assertThat(addNormal(null).getPrice()).isEqualByComparingTo(CATALOG_PRICE);
    }

    @Test
    void normalAllowsAPriceBelowCatalogButAboveCost() {
        assertThat(addNormal(new BigDecimal("80.00")).getPrice())
                .isEqualByComparingTo("80.00");
    }

    @Test
    void normalAllowsSellingExactlyAtCost() {
        assertThat(addNormal(COST).getPrice()).isEqualByComparingTo(COST);
    }

    @Test
    void normalRejectsAPriceBelowCost() {
        assertThatThrownBy(() -> addNormal(new BigDecimal("59.99")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("El precio de venta no puede ser menor al costo de adquisicion del producto");
    }

    @Test
    void normalRejectsANegativePrice() {
        assertThatThrownBy(() -> addNormal(new BigDecimal("-100.00")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("El precio de venta no puede ser negativo");
    }

    @Test
    void normalAllowsZeroOnlyWhenTheCostIsAlsoZero() {
        when(inventoryRepository.findByProductIdAndWorkshopIdForUpdate(
                product.getId(), workshopId))
                .thenReturn(Optional.of(inventory(CATALOG_PRICE, BigDecimal.ZERO)));

        SaleDetail detail = normalHandler.addDetail(
                sale(), request(BigDecimal.ZERO, null));

        assertThat(detail.getPrice()).isEqualByComparingTo("0.00");
    }

    @Test
    void theSubTotalIsThePriceTimesTheQuantity() {
        when(inventoryRepository.findByProductIdAndWorkshopIdForUpdate(
                product.getId(), workshopId))
                .thenReturn(Optional.of(inventory(CATALOG_PRICE, COST)));

        SaleDetail detail = normalHandler.addDetail(sale(),
                new AddSaleDetailRequestDTO(
                        product.getId(), null, null, 3, new BigDecimal("70.00"), null));

        assertThat(detail.getSubTotal()).isEqualByComparingTo("210.00");
    }

    @Test
    void theAlternativeInvoiceDescriptionTravelsToTheDetail() {
        when(inventoryRepository.findByProductIdAndWorkshopIdForUpdate(
                product.getId(), workshopId))
                .thenReturn(Optional.of(inventory(CATALOG_PRICE, COST)));

        SaleDetail detail = normalHandler.addDetail(sale(),
                new AddSaleDetailRequestDTO(
                        product.getId(), null, null, 1, null, "Maleta de resorte"));

        assertThat(detail.getInvoiceDescription()).isEqualTo("Maleta de resorte");
        assertThat(detail.resolveInvoiceDescription()).isEqualTo("Maleta de resorte");
        // El producto real no se toca.
        assertThat(detail.getProduct().getName()).isEqualTo("PRODUCTO");
    }

    // --------------------------------------------------------------- SPECIAL

    private SaleDetail addSpecial(BigDecimal price) {
        Inventory owner = inventory(CATALOG_PRICE, COST);
        InventoryItem item = item(owner);
        when(inventoryItemRepository.findByIdAndInventoryWorkshopId(
                item.getId(), workshopId))
                .thenReturn(Optional.of(item));
        // El handler resuelve el inventario POR EL ITEM para poder bloquearlo en
        // la misma consulta que lo carga.
        when(inventoryRepository.findByItemIdForUpdate(item.getId()))
                .thenReturn(Optional.of(owner));
        return specialHandler.addDetail(sale(), request(price, item.getId()));
    }

    @Test
    void specialAllowsAPriceBetweenCostAndCatalog() {
        assertThat(addSpecial(new BigDecimal("75.00")).getPrice())
                .isEqualByComparingTo("75.00");
    }

    @Test
    void specialRejectsAPriceBelowCost() {
        assertThatThrownBy(() -> addSpecial(new BigDecimal("10.00")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("El precio de venta no puede ser menor al costo de adquisicion del producto");
    }

    @Test
    void specialRejectsANegativePrice() {
        assertThatThrownBy(() -> addSpecial(new BigDecimal("-1.00")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("El precio de venta no puede ser negativo");
    }

    /** Un item de otro taller no debe ser vendible ni conociendo su UUID. */
    @Test
    void specialCannotSellAnItemOfAnotherWorkshop() {
        UUID foreignItemId = UUID.randomUUID();
        when(inventoryItemRepository.findByIdAndInventoryWorkshopId(
                foreignItemId, workshopId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> specialHandler.addDetail(
                sale(), request(null, foreignItemId)))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessage("No existe el item especificado");
    }

    // ------------------------------------------------------------- CONTAINER

    private SaleDetail addContainer(BigDecimal price) {
        Inventory owner = inventory(CATALOG_PRICE, COST);
        InventoryItem item = item(owner);
        when(inventoryItemRepository.findByIdAndInventoryWorkshopId(
                item.getId(), workshopId))
                .thenReturn(Optional.of(item));
        // Igual que SPECIAL: el inventario se resuelve por el item para
        // bloquearlo en la misma consulta.
        when(inventoryRepository.findByItemIdForUpdate(item.getId()))
                .thenReturn(Optional.of(owner));
        return containerHandler.addDetail(sale(), request(price, item.getId()));
    }

    @Test
    void containerAllowsAPriceBetweenCostAndCatalog() {
        assertThat(addContainer(new BigDecimal("90.00")).getPrice())
                .isEqualByComparingTo("90.00");
    }

    @Test
    void containerRejectsAPriceBelowCost() {
        assertThatThrownBy(() -> addContainer(new BigDecimal("5.00")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("El precio de venta no puede ser menor al costo de adquisicion del producto");
    }

    @Test
    void containerCannotSellAnItemOfAnotherWorkshop() {
        UUID foreignItemId = UUID.randomUUID();
        when(inventoryItemRepository.findByIdAndInventoryWorkshopId(
                foreignItemId, workshopId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> containerHandler.addDetail(
                sale(), request(null, foreignItemId)))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessage("No existe el item especificado");
    }

    // ------------------------------------------------------------------- JOB

    private SaleDetail addJob(BigDecimal price) {
        Job job = Job.builder()
                .id(UUID.randomUUID())
                .workshop(workshop)
                .product(product)
                .price(CATALOG_PRICE)
                .build();
        when(jobRepository.findByProductIdAndWorkshopIdForUpdate(
                product.getId(), workshopId))
                .thenReturn(Optional.of(job));
        return jobHandler.addDetail(sale(), request(price, null));
    }

    @Test
    void jobUsesTheCatalogPriceWhenNoSpecialPriceIsSent() {
        assertThat(addJob(null).getPrice()).isEqualByComparingTo(CATALOG_PRICE);
    }

    /**
     * Un servicio no tiene costo de adquisicion en el modelo, asi que Job.price es
     * solo catalogo y se puede rebajar. Tratarlo como costo bloquearia descuentos
     * legitimos sobre mano de obra.
     */
    @Test
    void jobAllowsAPriceBelowTheCatalogPriceBecauseThereIsNoRealCost() {
        assertThat(addJob(new BigDecimal("1.00")).getPrice())
                .isEqualByComparingTo("1.00");
    }

    @Test
    void jobRejectsANegativePrice() {
        assertThatThrownBy(() -> addJob(new BigDecimal("-50.00")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("El precio de venta no puede ser negativo");
    }

    /**
     * El bug: el servicio resolvia `product` como Product.id y este handler lo
     * usaba como Job.id. Con ids distintos —como en produccion— ningun valor
     * satisfacia a los dos y la venta de servicios era inalcanzable.
     */
    @Test
    void jobIsResolvedByProductIdAndNotByJobId() {
        UUID jobId = UUID.randomUUID();
        Job job = Job.builder()
                .id(jobId)
                .workshop(workshop)
                .product(product)
                .price(CATALOG_PRICE)
                .build();
        assertThat(jobId).isNotEqualTo(product.getId());
        when(jobRepository.findByProductIdAndWorkshopIdForUpdate(
                product.getId(), workshopId))
                .thenReturn(Optional.of(job));

        SaleDetail detail = jobHandler.addDetail(sale(), request(null, null));

        // El detalle apunta al PRODUCTO del servicio, que es lo que WorkResult
        // valida despues contra el JobLog.
        assertThat(detail.getProduct().getId()).isEqualTo(product.getId());
    }

    @Test
    void jobCannotUseAServiceOfAnotherWorkshop() {
        when(jobRepository.findByProductIdAndWorkshopIdForUpdate(
                product.getId(), workshopId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> jobHandler.addDetail(sale(), request(null, null)))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessage("No existe el servicio especificado");
    }

}
