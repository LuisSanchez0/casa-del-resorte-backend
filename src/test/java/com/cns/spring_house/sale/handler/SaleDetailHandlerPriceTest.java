package com.cns.spring_house.sale.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.AuthorityUtils;
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
import com.cns.spring_house.shared.exception.PriceConflictException;
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
        // El vendedor: el rol que decide importes, como en la caja.
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(jwt, AuthorityUtils.createAuthorityList("ROLE_SELLER")));
    }

    private void actingAs(String role) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject(UUID.randomUUID().toString())
                .claim("workshop", workshopId.toString())
                .build();
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(jwt, AuthorityUtils.createAuthorityList("ROLE_" + role)));
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

    /** Con el catalogo que el vendedor tenia a la vista: el que tienen los stubs. */
    private AddSaleDetailRequestDTO request(BigDecimal price, UUID itemId) {
        return new AddSaleDetailRequestDTO(
                product.getId(), itemId, null, 1, price, null, null, null, null, null, CATALOG_PRICE);
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

    /** Sin cargo expreso: el cero se acepta si la pieza no costo nada. */
    @Test
    void normalAllowsZeroOnlyWhenTheCostIsAlsoZero() {
        when(inventoryRepository.findByProductIdAndWorkshopIdForUpdate(
                product.getId(), workshopId))
                .thenReturn(Optional.of(inventory(CATALOG_PRICE, BigDecimal.ZERO)));

        SaleDetail detail = normalHandler.addDetail(sale(), noCharge(BigDecimal.ZERO));

        assertThat(detail.getPrice()).isEqualByComparingTo("0.00");
        assertThat(detail.getSubTotal()).isEqualByComparingTo("0.00");
    }

    private AddSaleDetailRequestDTO noCharge(BigDecimal price) {
        return noCharge(price, CATALOG_PRICE);
    }

    private AddSaleDetailRequestDTO noCharge(BigDecimal price, BigDecimal seenCatalog) {
        return new AddSaleDetailRequestDTO(product.getId(), null, null, 1, price,
                null, null, null, null, null, seenCatalog, true);
    }

    /** Q0 (a). Un cero suelto —sin la intencion expresa— se rechaza. */
    @Test
    void aZeroWithoutTheExplicitNoChargeIsRejected() {
        when(inventoryRepository.findByProductIdAndWorkshopIdForUpdate(
                product.getId(), workshopId))
                .thenReturn(Optional.of(inventory(CATALOG_PRICE, BigDecimal.ZERO)));

        assertThatThrownBy(() -> normalHandler.addDetail(sale(), request(BigDecimal.ZERO, null)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("sin cargo");
    }

    /** Q0 (b). "Sin cargo" no puede ir pegado a otro importe. */
    @Test
    void noChargeOverANonZeroPriceIsRejected() {
        when(inventoryRepository.findByProductIdAndWorkshopIdForUpdate(
                product.getId(), workshopId))
                .thenReturn(Optional.of(inventory(CATALOG_PRICE, COST)));

        assertThatThrownBy(() -> normalHandler.addDetail(sale(), noCharge(new BigDecimal("80.00"))))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Q0.00");
        // Ni siquiera con el precio de catalogo implicito.
        assertThatThrownBy(() -> normalHandler.addDetail(sale(), noCharge(null)))
                .isInstanceOf(ValidationException.class);
    }

    /** Q0 (c). "Sin cargo" no salta el costo: regalar una pieza que costo es perdida. */
    @Test
    void noChargeCannotGoBelowTheCost() {
        when(inventoryRepository.findByProductIdAndWorkshopIdForUpdate(
                product.getId(), workshopId))
                .thenReturn(Optional.of(inventory(CATALOG_PRICE, COST)));

        assertThatThrownBy(() -> normalHandler.addDetail(sale(), noCharge(BigDecimal.ZERO)))
                .isInstanceOf(ValidationException.class)
                .hasMessage("El precio de venta no puede ser menor al costo de adquisicion del producto");
    }

    /** Q0 (d). Sin precio de catalogo, el cero es "no tiene precio", nunca "sin cargo". */
    @Test
    void noChargeIsRejectedWhenTheProductHasNoCatalogPrice() {
        Inventory unpriced = inventory(BigDecimal.ZERO, BigDecimal.ZERO);
        when(inventoryRepository.findByProductIdAndWorkshopIdForUpdate(
                product.getId(), workshopId))
                .thenReturn(Optional.of(unpriced));

        assertThatThrownBy(() -> normalHandler.addDetail(sale(), noCharge(BigDecimal.ZERO, BigDecimal.ZERO)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("no tiene precio de catalogo");
        assertThat(unpriced.getPrice()).isEqualByComparingTo("0.00");
    }

    @Test
    void theSubTotalIsThePriceTimesTheQuantity() {
        when(inventoryRepository.findByProductIdAndWorkshopIdForUpdate(
                product.getId(), workshopId))
                .thenReturn(Optional.of(inventory(CATALOG_PRICE, COST)));

        SaleDetail detail = normalHandler.addDetail(sale(),
                new AddSaleDetailRequestDTO(
                        product.getId(), null, null, 3, new BigDecimal("70.00"), null,
                        null, null, null, null, CATALOG_PRICE));

        assertThat(detail.getSubTotal()).isEqualByComparingTo("210.00");
    }

    @Test
    void theAlternativeInvoiceDescriptionTravelsToTheDetail() {
        when(inventoryRepository.findByProductIdAndWorkshopIdForUpdate(
                product.getId(), workshopId))
                .thenReturn(Optional.of(inventory(CATALOG_PRICE, COST)));

        SaleDetail detail = normalHandler.addDetail(sale(),
                new AddSaleDetailRequestDTO(
                        product.getId(), null, null, 1, null, "Maleta de resorte",
                        null, null, null, null, CATALOG_PRICE));

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

    /**
     * Un servicio no tiene costo en el modelo. Desconocido no es cero: ni un
     * cero suelto ni un "sin cargo" expreso lo dejan gratis. Rebajarlo sigue
     * permitido (no hay costo que cubrir).
     */
    @Test
    void aJobNeverGoesForFreeBecauseItsCostIsUnknown() {
        Job job = Job.builder().id(UUID.randomUUID()).workshop(workshop).product(product)
                .price(CATALOG_PRICE).build();
        when(jobRepository.findByProductIdAndWorkshopIdForUpdate(product.getId(), workshopId))
                .thenReturn(Optional.of(job));

        assertThatThrownBy(() -> jobHandler.addDetail(sale(), request(BigDecimal.ZERO, null)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("sin cargo");
        assertThatThrownBy(() -> jobHandler.addDetail(sale(), noCharge(BigDecimal.ZERO)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("costo desconocido no es cero");
        assertThat(job.getPrice()).isEqualByComparingTo(CATALOG_PRICE);
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


    // ------------------------------------------------- catalogo que vio el vendedor

    /**
     * M. El precio se confirma mirando el catalogo de ESE momento. Si cambio,
     * la decision ya no significa lo mismo y se rechaza con conflicto. Sin el
     * dato no hay linea: omitirlo ya no salta la comprobacion (antes si).
     */
    @Test
    void aStaleCatalogPriceIsAConflictAndAMatchingOneIsNot() {
        BigDecimal current = new BigDecimal("100.00");

        assertThatThrownBy(() -> SaleDetailHandler.requireCatalogPriceAsSeen(null, current))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("precio de catalogo que tenia a la vista");
        assertThatThrownBy(() -> SaleDetailHandler.requireCatalogPriceAsSeen(null, null))
                .isInstanceOf(ValidationException.class);
        assertThatCode(() -> SaleDetailHandler.requireCatalogPriceAsSeen(new BigDecimal("100"), current))
                .doesNotThrowAnyException();
        assertThatCode(() -> SaleDetailHandler.requireCatalogPriceAsSeen(BigDecimal.ZERO, BigDecimal.ZERO))
                .doesNotThrowAnyException();
        assertThatCode(() -> SaleDetailHandler.requireCatalogPriceAsSeen(BigDecimal.ZERO, null))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> SaleDetailHandler.requireCatalogPriceAsSeen(new BigDecimal("90.00"), current))
                .isInstanceOf(PriceConflictException.class)
                .hasMessageContaining("Q 100.00");
        assertThatThrownBy(() -> SaleDetailHandler.requireCatalogPriceAsSeen(BigDecimal.ZERO, current))
                .isInstanceOf(PriceConflictException.class);
        assertThatThrownBy(() -> SaleDetailHandler.requireCatalogPriceAsSeen(current, BigDecimal.ZERO))
                .isInstanceOf(PriceConflictException.class)
                .hasMessageContaining("ya no tiene precio");
    }

    // --------------------------------------------- precio corregido de una linea

    /**
     * La misma regla de Q0 y de costo al corregir el precio que al agregar la
     * linea, y el mismo formato de dinero.
     */
    @Test
    void anEditedPriceFollowsTheSameRulesAsAddingTheLine() {
        BigDecimal catalog = new BigDecimal("500.00");
        BigDecimal floor = new BigDecimal("100.00");

        assertThatCode(() -> SaleDetailHandler.validateEditedPrice(new BigDecimal("100"), false, catalog, floor))
                .doesNotThrowAnyException();
        assertThatCode(() -> SaleDetailHandler.validateEditedPrice(new BigDecimal("450.50"), false, catalog, floor))
                .doesNotThrowAnyException();
        // Bajo el costo vigente.
        assertThatThrownBy(() -> SaleDetailHandler.validateEditedPrice(new BigDecimal("99.99"), false, catalog, floor))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Q 100.00");
        // Negativo, tres decimales, nulo.
        assertThatThrownBy(() -> SaleDetailHandler.validateEditedPrice(new BigDecimal("-1"), false, catalog, null))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> SaleDetailHandler.validateEditedPrice(new BigDecimal("120.001"), false, catalog, floor))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> SaleDetailHandler.validateEditedPrice(null, false, catalog, floor))
                .isInstanceOf(ValidationException.class);
        // La regla del servicio va por VALOR: 120.000 es 120.00. (Por HTTP, el
        // formato de dinero del DTO ya rechaza tres decimales escritos.)
        assertThatCode(() -> SaleDetailHandler.validateEditedPrice(new BigDecimal("120.000"), false, catalog, floor))
                .doesNotThrowAnyException();
    }

    @Test
    void anEditedZeroNeedsTheExplicitNoChargeACatalogAndNoCost() {
        BigDecimal catalog = new BigDecimal("500.00");

        // Sin la intencion expresa: rechazado, aunque el costo sea cero.
        assertThatThrownBy(() -> SaleDetailHandler.validateEditedPrice(BigDecimal.ZERO, false, catalog, BigDecimal.ZERO))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("sin cargo");
        // Con ella, y sin costo (o costo cero): aceptado.
        assertThatCode(() -> SaleDetailHandler.validateEditedPrice(new BigDecimal("0.00"), true, catalog, BigDecimal.ZERO))
                .doesNotThrowAnyException();
        assertThatCode(() -> SaleDetailHandler.validateEditedPrice(BigDecimal.ZERO, true, catalog, null))
                .doesNotThrowAnyException();
        // "Sin cargo" con otro importe.
        assertThatThrownBy(() -> SaleDetailHandler.validateEditedPrice(BigDecimal.TEN, true, catalog, null))
                .isInstanceOf(ValidationException.class);
        // No salta el costo ni el minimo de una revision de precio.
        assertThatThrownBy(() -> SaleDetailHandler.validateEditedPrice(BigDecimal.ZERO, true, catalog, new BigDecimal("260.00")))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Q 260.00");
        // Sin catalogo, el cero es "sin precio": nunca "sin cargo".
        assertThatThrownBy(() -> SaleDetailHandler.validateEditedPrice(BigDecimal.ZERO, true, BigDecimal.ZERO, null))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("no tiene precio de catalogo");
        assertThatThrownBy(() -> SaleDetailHandler.validateEditedPrice(BigDecimal.ZERO, true, null, null))
                .isInstanceOf(ValidationException.class);
    }

    /** I. Sin el catalogo visto, ningun handler llega a tocar stock ni precio. */
    @Test
    void aLineWithoutTheSeenCatalogIsRejectedBeforeTouchingAnything() {
        Inventory inventory = inventory(BigDecimal.ZERO, COST);
        when(inventoryRepository.findByProductIdAndWorkshopIdForUpdate(
                product.getId(), workshopId))
                .thenReturn(Optional.of(inventory));

        assertThatThrownBy(() -> normalHandler.addDetail(sale(), new AddSaleDetailRequestDTO(
                product.getId(), null, null, 1, new BigDecimal("90.00"), null)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("precio de catalogo que tenia a la vista");
        // El precio confirmado NO paso a ser el de catalogo. (Que el stock
        // tampoco se mueva lo prueba la base: la transaccion se deshace.)
        assertThat(inventory.getPrice()).isEqualByComparingTo("0.00");
    }

    // ------------------------------------------------------ quien pide "sin cargo"

    /**
     * E. Pedir "sin cargo" es decidir un importe: solo los roles que deciden
     * importes en la caja (vendedor, Administracion), con el rol del token.
     * Control, el Operador, Secretaria o Policia: 403, al agregar y al corregir.
     */
    @Test
    void onlyTheRolesThatDecideAmountsMayRequestNoCharge() {
        when(inventoryRepository.findByProductIdAndWorkshopIdForUpdate(
                product.getId(), workshopId))
                .thenReturn(Optional.of(inventory(CATALOG_PRICE, BigDecimal.ZERO)));

        for (String role : new String[] {"CONTROL", "OPERATOR", "SECRETARY", "POLICE"}) {
            actingAs(role);
            assertThatThrownBy(() -> normalHandler.addDetail(sale(), noCharge(BigDecimal.ZERO)))
                    .as(role).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> SaleDetailHandler.validateEditedPrice(
                    BigDecimal.ZERO, true, CATALOG_PRICE, BigDecimal.ZERO))
                    .as(role).isInstanceOf(AccessDeniedException.class);
        }
        actingAs("ADMIN");
        assertThat(normalHandler.addDetail(sale(), noCharge(BigDecimal.ZERO)).getPrice())
                .isEqualByComparingTo("0.00");
        // Sin pedir "sin cargo" el rol no importa: el resto de reglas de siempre.
        actingAs("CONTROL");
        assertThat(normalHandler.addDetail(sale(), request(new BigDecimal("90.00"), null)).getPrice())
                .isEqualByComparingTo("90.00");
    }

    /**
     * D. "Sin cargo" exige el costo REGISTRADO de las unidades de la linea en
     * cero: una solicitud (sin unidad todavia), un costo sin registrar (lote de
     * produccion) o uno positivo lo impiden.
     */
    @Test
    void noChargeNeedsARecordedZeroCostForTheLine() {
        SaleDetail recordedZero = SaleDetail.builder().costTotal(BigDecimal.ZERO).build();
        SaleDetail unknown = SaleDetail.builder().costTotal(null).build();
        SaleDetail positive = SaleDetail.builder().costTotal(new BigDecimal("0.01")).build();
        SaleDetail request = SaleDetail.builder().costTotal(BigDecimal.ZERO)
                .fulfillment(com.cns.spring_house.sale.entity.Fulfillment.pending(
                        com.cns.spring_house.sale.entity.FulfillmentType.STOCK_RELEASE))
                .build();

        assertThatCode(() -> SaleDetailHandler.requireRecordedZeroCost(recordedZero)).doesNotThrowAnyException();
        assertThatThrownBy(() -> SaleDetailHandler.requireRecordedZeroCost(unknown))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("costo desconocido no es cero");
        assertThatThrownBy(() -> SaleDetailHandler.requireRecordedZeroCost(positive))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("tienen costo");
        assertThatThrownBy(() -> SaleDetailHandler.requireRecordedZeroCost(request))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("solicitud al taller");
    }

    @Test
    void aNormalLineOverAStaleCatalogTouchesNoStock() {
        Inventory inventory = inventory(CATALOG_PRICE, COST);
        when(inventoryRepository.findByProductIdAndWorkshopIdForUpdate(
                product.getId(), workshopId))
                .thenReturn(Optional.of(inventory));

        assertThatThrownBy(() -> normalHandler.addDetail(sale(), new AddSaleDetailRequestDTO(
                product.getId(), null, null, 1, new BigDecimal("90.00"), null, null, null, null, null,
                new BigDecimal("90.00"))))
                .isInstanceOf(PriceConflictException.class);
        // El stock se descuenta despues de resolver el precio: la transaccion
        // se deshace igual, pero aqui ni siquiera llega a moverse.
        assertThat(inventory.getPrice()).isEqualByComparingTo(CATALOG_PRICE);
    }

}
