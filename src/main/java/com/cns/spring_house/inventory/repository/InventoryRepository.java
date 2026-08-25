package com.cns.spring_house.inventory.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.cns.spring_house.inventory.entity.Inventory;
import com.cns.spring_house.inventory.repository.projection.ProductSalesProjection;

import jakarta.persistence.LockModeType;

public interface InventoryRepository
        extends JpaRepository<Inventory, UUID>,
        JpaSpecificationExecutor<Inventory> {

    Optional<Inventory> findByProductIdAndWorkshopId(
            UUID product,
            UUID workshop);

    Optional<Inventory> findByIdAndWorkshopId(
            UUID id,
            UUID workshopId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT inventory
        FROM Inventory inventory
        JOIN FETCH inventory.product product
        JOIN FETCH product.family family
        WHERE inventory.id = :inventoryId
          AND inventory.workshop.id = :workshopId
    """)
    Optional<Inventory> findByIdAndWorkshopIdForUpdate(
            @Param("inventoryId") UUID inventoryId,
            @Param("workshopId") UUID workshopId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<Inventory> findAllByProductIdInAndWorkshopId(
            Iterable<UUID> productIds,
            UUID workshop);

    boolean existsByProductIdAndWorkshopId(
            UUID product,
            UUID workshop);

    boolean existsByProductIdAndWorkshopIdAndIdNot(
            UUID product,
            UUID workshop,
            UUID id);

    // ── Agregados del dashboard administrativo ────────────────────

    /**
     * Productos del taller que MENOS se venden, incluidos los que no se han
     * vendido nunca.
     *
     * El origen es `inventory` y no `sale_detail`: preguntando desde las ventas
     * solo pueden salir productos que YA se vendieron, y el producto que lleva
     * un ano parado —el que de verdad interesa detectar— no aparece en ninguna
     * fila. Partiendo del inventario del taller, ese producto existe y vale
     * cero.
     *
     * El LEFT JOIN va contra una subconsulta ya agregada, no contra
     * `sale_detail` en bruto: agregando primero, cada producto trae UNA fila con
     * su total y no hay que agrupar el inventario despues. Ambos lados llevan el
     * taller, de modo que las ventas de otro taller no pueden sumar aqui ni
     * siquiera para un producto compartido del catalogo.
     *
     * `sale.status = 'COMPLETED'` es el mismo criterio que el ranking de mas
     * vendidos: lo que se mide es mercaderia entregada. Si los dos rankings
     * usaran filtros distintos, un producto podria salir en los dos extremos.
     *
     * Solo entra lo que tiene inventario en ESTE taller: un producto del
     * catalogo que este taller no maneja no es stock muerto suyo.
     *
     * Los servicios quedan fuera POR CATEGORIA, con el mismo
     * `family.category <> 'JOB'` que usa el ranking de mas vendidos. Hoy
     * bastaria con partir de `inventory` —un servicio no reserva inventario, asi
     * que no tiene fila—, pero eso es una CONSECUENCIA del modelo, no una regla
     * que nadie haya escrito: nada impide crear una fila de inventario para un
     * producto de familia JOB. Declarando la exclusion en las dos consultas, los
     * dos rankings comparten universo por construccion y no por coincidencia, y
     * no pueden divergir si esa consecuencia deja de cumplirse.
     *
     * Un servicio poco solicitado, ademas, no es stock parado: no ocupa bodega.
     *
     * El desempate termina en `product.id` y no en el nombre. Con el nombre como
     * ultima clave el orden seguia sin estar definido: el catalogo REAL tiene
     * productos distintos que se llaman igual —dos "HOJA DE RESORTE 1200"—, y
     * empatados a cero unidades PostgreSQL podia devolverlos en cualquier orden,
     * de modo que el corte del top cambiaba entre dos peticiones identicas. El
     * id es unico, asi que el orden queda total.
     */
    @Query(nativeQuery = true, value = """
        SELECT product.id AS productId,
               product.name AS productName,
               COALESCE(sold.quantity, 0) AS quantity
        FROM inventory
        JOIN product ON product.id = inventory.product_id
        JOIN family ON family.id = product.family_id
        LEFT JOIN (
            SELECT detail.product_id AS product_id,
                   SUM(detail.quantity) AS quantity
            FROM sale_detail detail
            JOIN sale ON sale.id = detail.sale_id
            WHERE sale.workshop_id = :workshopId
              AND sale.status = 'COMPLETED'
            GROUP BY detail.product_id
        ) sold ON sold.product_id = product.id
        WHERE inventory.workshop_id = :workshopId
          AND family.category <> 'JOB'
        ORDER BY quantity ASC, product.name ASC, product.id ASC
        LIMIT :maxRows
    """)
    List<ProductSalesProjection> findLeastSellingProducts(
            @Param("workshopId") UUID workshopId,
            @Param("maxRows") int maxRows);

}
