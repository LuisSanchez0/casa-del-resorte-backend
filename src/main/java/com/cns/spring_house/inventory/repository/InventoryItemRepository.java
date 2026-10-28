package com.cns.spring_house.inventory.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.cns.spring_house.inventory.entity.Inventory;
import com.cns.spring_house.inventory.entity.InventoryItem;
import com.cns.spring_house.inventory.entity.InventoryItemStatus;

import jakarta.persistence.LockModeType;

public interface InventoryItemRepository
        extends JpaRepository<InventoryItem, UUID> {

    int countByInventoryAndStatus(
            Inventory inventory,
            InventoryItemStatus status);

    /**
     * Item acotado al taller del usuario. Buscar solo por id permitia vender
     * manualmente una unidad del inventario de otro taller.
     */
    Optional<InventoryItem> findByIdAndInventoryWorkshopId(
            UUID id,
            UUID workshopId);

    /**
     * Unidades físicas que siguen dentro de un contenedor concreto. Consulta el
     * lado propietario de la relación (`container_id`), que es el único que
     * refleja la verdad tras liberar componentes.
     */
    long countByContainerId(UUID containerId);

    /**
     * Unidades FISICAS que todavia pueden operarse, contadas por inventario y
     * para TODO el taller de una vez.
     *
     * Replica exactamente el predicado que decide si una unidad puede entrar en
     * una transformacion (`TransformationServiceImpl.isProcessable`): disponible,
     * o una maleta a medio desarmar que aun guarda componentes. No se cuenta
     * `inventory.stock`, que es un contador con su propia vida: un contenedor
     * parcialmente desarmado sigue sumando existencia aunque ya no sea vendible,
     * y un inventario puede conservar su fila con todas sus unidades consumidas.
     *
     * Es UNA consulta con `GROUP BY` y un `EXISTS`, no una por inventario: la
     * pantalla de transformaciones lista decenas de inventarios y preguntar por
     * cada uno seria un N+1.
     *
     * Devuelve solo los inventarios con al menos una unidad: los que no aparecen
     * cuentan cero.
     */
    @Query("""
        SELECT item.inventory.id, COUNT(item)
        FROM InventoryItem item
        WHERE item.inventory.workshop.id = :workshopId
          AND (
                item.status = com.cns.spring_house.inventory.entity.InventoryItemStatus.AVAILABLE
                OR (
                     item.status = com.cns.spring_house.inventory.entity.InventoryItemStatus.UNAVAILABLE
                     AND item.inventory.product.family.category
                         = com.cns.spring_house.family.entity.FamilyCategory.CONTAINER
                     AND EXISTS (
                           SELECT 1 FROM InventoryItem child
                           WHERE child.container = item)
                   )
              )
        GROUP BY item.inventory.id
    """)
    List<Object[]> countProcessableUnitsByWorkshop(@Param("workshopId") UUID workshopId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT item
        FROM InventoryItem item
        WHERE item.inventory.id = :inventoryId
          AND item.status = :status
        ORDER BY item.createdAt ASC
    """)
    List<InventoryItem> findItemsForUpdate(
            @Param("inventoryId") UUID inventoryId,
            @Param("status") InventoryItemStatus status,
            Pageable pageable);

    /**
     * Unidades de un inventario en un estado, de la mas antigua a la mas nueva,
     * con su producto y familia en el mismo viaje (los lee el mapeo de cada
     * unidad). Sin cerrojo: es lo que Control MIRA antes de elegir; la eleccion
     * vuelve a comprobarse con el inventario bloqueado.
     */
    @Query("""
        SELECT item
        FROM InventoryItem item
        JOIN FETCH item.inventory inventory
        JOIN FETCH inventory.product product
        JOIN FETCH product.family
        WHERE inventory.id = :inventoryId
          AND item.status = :status
        ORDER BY item.createdAt ASC, item.id ASC
    """)
    List<InventoryItem> findByInventoryIdAndStatus(
            @Param("inventoryId") UUID inventoryId,
            @Param("status") InventoryItemStatus status,
            Pageable pageable);

    /**
     * Materia prima para fabricar una pieza pedida: unidades especiales
     * DISPONIBLES del taller cuya familia puede transformarse en la del
     * resultado, excluyendo el propio producto pedido (eso no es fabricar, es
     * entregar, y lo hace Control).
     *
     * Disponible ya excluye lo reservado para otra venta (SOLD), lo consumido y
     * los componentes metidos en una maleta (UNAVAILABLE). El resto de reglas
     * —largo, orificio, colochos— las aplica la transformacion al ejecutarse;
     * aqui solo se acota lo que tiene sentido ofrecer.
     *
     * La busqueda (`pattern`, de {@code LikePatterns.containsIgnoreCaseAndAccents})
     * se aplica AQUI, antes del tope del `Pageable`: buscar encuentra cualquier
     * pieza elegible del taller, no solo las primeras que cupieran en la pagina.
     * Compara sin mayusculas ni acentos contra nombre, SKU, familia, modelo,
     * marca, caracteristicas y el id de la unidad (su codigo corto son los
     * primeros caracteres). `scannedItem` es la unidad de una etiqueta escaneada.
     * Con los dos nulos no filtra nada. Buscar nunca amplia lo elegible: las
     * condiciones de taller, estado y familia se aplican igual.
     */
    @Query("""
        SELECT item
        FROM InventoryItem item
        JOIN FETCH item.inventory inventory
        JOIN FETCH inventory.product product
        JOIN FETCH product.family family
        LEFT JOIN FETCH product.model model
        LEFT JOIN FETCH model.brand brand
        WHERE inventory.workshop.id = :workshopId
          AND item.status = com.cns.spring_house.inventory.entity.InventoryItemStatus.AVAILABLE
          AND item.container IS NULL
          AND family.id IN :familyIds
          AND product.id <> :excludedProductId
          AND ((:pattern IS NULL AND :scannedItem IS NULL)
            OR item.id = :scannedItem
            OR translate(LOWER(CONCAT(
                    product.name, ' ',
                    COALESCE(product.sku, ''), ' ',
                    family.name, ' ',
                    COALESCE(model.name, ''), ' ',
                    COALESCE(brand.name, ''), ' ',
                    COALESCE(CAST(product.attributes AS String), ''), ' ',
                    CAST(item.id AS String))),
                :accented, :unaccented) LIKE :pattern ESCAPE '\\')
        ORDER BY item.createdAt ASC, item.id ASC
    """)
    List<InventoryItem> findTransformationSources(
            @Param("workshopId") UUID workshopId,
            @Param("familyIds") java.util.Collection<UUID> familyIds,
            @Param("excludedProductId") UUID excludedProductId,
            @Param("pattern") String pattern,
            @Param("scannedItem") UUID scannedItem,
            @Param("accented") String accented,
            @Param("unaccented") String unaccented,
            Pageable pageable);


    /**
     * Producto de una unidad del taller SIN cargar la unidad. Permite bloquear
     * su inventario antes de leerla: una unidad ya cargada no se relee por
     * haber esperado el cerrojo, y la comprobacion de disponibilidad quedaria
     * decidida sobre una copia vieja.
     */
    @Query("""
        SELECT item.inventory.product.id
        FROM InventoryItem item
        WHERE item.id = :itemId
          AND item.inventory.workshop.id = :workshopId
    """)
    Optional<UUID> findProductIdByIdAndWorkshopId(
            @Param("itemId") UUID itemId,
            @Param("workshopId") UUID workshopId);

}