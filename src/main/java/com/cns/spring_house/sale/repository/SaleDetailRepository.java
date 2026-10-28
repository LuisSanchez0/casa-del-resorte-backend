package com.cns.spring_house.sale.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.cns.spring_house.sale.entity.FulfillmentStatus;
import com.cns.spring_house.sale.entity.FulfillmentType;
import com.cns.spring_house.sale.entity.SaleDetail;
import com.cns.spring_house.sale.repository.projection.FulfillmentTaskRow;
import com.cns.spring_house.sale.repository.projection.SaleAreaProgressRow;

public interface SaleDetailRepository extends JpaRepository<SaleDetail, UUID> {

    /** El detalle solo es alcanzable si su venta pertenece al taller del token. */
    Optional<SaleDetail> findByIdAndSaleWorkshopId(UUID id, UUID workshopId);

    /**
     * Un WorkResult solo puede reservarse en un detalle. La base ya lo impide con
     * uk_sale_detail_work_result (V15); comprobarlo antes permite responder un
     * error de negocio en lugar de una violacion de integridad.
     */
    boolean existsByWorkResultId(UUID workResultId);


    /**
     * La venta de una solicitud, acotada al taller, sin cargar ninguna entidad.
     * Es el primer paso de toda accion de Control u Operador: con el id de la
     * venta se toma su cerrojo antes de leerla.
     */
    @Query("""
        SELECT detail.sale.id
        FROM SaleDetail detail
        WHERE detail.id = :id
          AND detail.sale.workshop.id = :workshopId
    """)
    Optional<UUID> findSaleIdByIdAndWorkshopId(
            @Param("id") UUID id,
            @Param("workshopId") UUID workshopId);

    /**
     * Cola de Control o del Operador en UNA consulta, a cualquier tamano: todo
     * lo que se muestra de cada solicitud llega como columnas de la misma fila.
     * Solo ventas abiertas: lo de una venta anulada o ya facturada no es
     * trabajo pendiente de nadie.
     */
    @Query("""
        SELECT new com.cns.spring_house.sale.repository.projection.FulfillmentTaskRow(
            detail.id,
            sale.id,
            sale.taxMode,
            sale.customerName,
            customer.name,
            customer.nit,
            sale.plate,
            seller.firstName,
            seller.lastName,
            detail.fulfillment.type,
            detail.fulfillment.status,
            detail.fulfillment.note,
            detail.fulfillment.blockReason,
            detail.fulfillment.dispatchedAt,
            detail.fulfillment.startedAt,
            detail.fulfillment.completedAt,
            worker.firstName,
            worker.lastName,
            product.id,
            product.name,
            product.sku,
            product.attributes,
            family.id,
            family.name,
            model.id,
            model.name,
            brand.id,
            brand.name,
            detail.quantity,
            detail.price,
            detail.instructions,
            detail.requestedCurls,
            detail.requestedCurlCount,
            item.id,
            transformation.id,
            jobLog.id)
        FROM SaleDetail detail
        JOIN detail.sale sale
        LEFT JOIN sale.customer customer
        LEFT JOIN sale.employee seller
        JOIN detail.product product
        JOIN product.family family
        LEFT JOIN product.model model
        LEFT JOIN model.brand brand
        LEFT JOIN detail.item item
        LEFT JOIN detail.fulfillment.employee worker
        LEFT JOIN detail.fulfillment.transformation transformation
        LEFT JOIN detail.fulfillment.jobLog jobLog
        WHERE sale.workshop.id = :workshopId
          AND sale.status = com.cns.spring_house.sale.entity.SaleStatus.PENDING
          AND detail.fulfillment.type IN :types
          AND detail.fulfillment.status IN :statuses
        ORDER BY detail.fulfillment.dispatchedAt ASC, detail.id ASC
    """)
    List<FulfillmentTaskRow> findFulfillmentQueue(
            @Param("workshopId") UUID workshopId,
            @Param("types") Collection<FulfillmentType> types,
            @Param("statuses") Collection<FulfillmentStatus> statuses,
            Pageable pageable);

    /** Una sola solicitud con la misma forma que la cola. */
    @Query("""
        SELECT new com.cns.spring_house.sale.repository.projection.FulfillmentTaskRow(
            detail.id,
            sale.id,
            sale.taxMode,
            sale.customerName,
            customer.name,
            customer.nit,
            sale.plate,
            seller.firstName,
            seller.lastName,
            detail.fulfillment.type,
            detail.fulfillment.status,
            detail.fulfillment.note,
            detail.fulfillment.blockReason,
            detail.fulfillment.dispatchedAt,
            detail.fulfillment.startedAt,
            detail.fulfillment.completedAt,
            worker.firstName,
            worker.lastName,
            product.id,
            product.name,
            product.sku,
            product.attributes,
            family.id,
            family.name,
            model.id,
            model.name,
            brand.id,
            brand.name,
            detail.quantity,
            detail.price,
            detail.instructions,
            detail.requestedCurls,
            detail.requestedCurlCount,
            item.id,
            transformation.id,
            jobLog.id)
        FROM SaleDetail detail
        JOIN detail.sale sale
        LEFT JOIN sale.customer customer
        LEFT JOIN sale.employee seller
        JOIN detail.product product
        JOIN product.family family
        LEFT JOIN product.model model
        LEFT JOIN model.brand brand
        LEFT JOIN detail.item item
        LEFT JOIN detail.fulfillment.employee worker
        LEFT JOIN detail.fulfillment.transformation transformation
        LEFT JOIN detail.fulfillment.jobLog jobLog
        WHERE detail.id = :id
          AND sale.workshop.id = :workshopId
          AND detail.fulfillment.status IS NOT NULL
    """)
    Optional<FulfillmentTaskRow> findFulfillmentTask(
            @Param("id") UUID id,
            @Param("workshopId") UUID workshopId);

    /**
     * Cuantas solicitudes de cada venta tiene un area y cuantas estan cumplidas,
     * para las ventas de una pagina de la cola, en UNA consulta. Cuenta las
     * enviadas al taller y no anuladas (`statuses`), asi que la cola pendiente
     * puede decir "1 de 3 listas" sin traer las ya cumplidas.
     */
    @Query("""
        SELECT new com.cns.spring_house.sale.repository.projection.SaleAreaProgressRow(
            detail.sale.id,
            COUNT(detail),
            SUM(CASE WHEN detail.fulfillment.status
                    = com.cns.spring_house.sale.entity.FulfillmentStatus.FULFILLED
                THEN 1 ELSE 0 END))
        FROM SaleDetail detail
        WHERE detail.sale.workshop.id = :workshopId
          AND detail.sale.id IN :saleIds
          AND detail.fulfillment.type IN :types
          AND detail.fulfillment.status IN :statuses
        GROUP BY detail.sale.id
    """)
    List<SaleAreaProgressRow> countFulfillmentBySale(
            @Param("workshopId") UUID workshopId,
            @Param("saleIds") Collection<UUID> saleIds,
            @Param("types") Collection<FulfillmentType> types,
            @Param("statuses") Collection<FulfillmentStatus> statuses);

}
