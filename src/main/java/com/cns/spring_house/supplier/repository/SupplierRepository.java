package com.cns.spring_house.supplier.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.cns.spring_house.supplier.dto.SupplierResponseDTO;
import com.cns.spring_house.supplier.entity.Supplier;

public interface SupplierRepository extends JpaRepository<Supplier, UUID> {

    /**
     * Un proveedor concreto, SIEMPRE acotado al taller. Buscar solo por id
     * permitiria adjuntar a un lote el proveedor de otro taller conociendo su
     * UUID, que es exactamente el cruce que el modelo prohibe.
     */
    Optional<Supplier> findByIdAndWorkshopId(UUID id, UUID workshopId);

    /**
     * Sugerencias del autocompletado: proveedores del taller cuyo nombre
     * CONTIENE el texto, sin distinguir mayusculas y sin que los comodines del
     * usuario actuen como tales.
     *
     * El patron lo arma {@link com.cns.spring_house.shared.util.LikePatterns} en
     * el servicio y no la consulta: `%` forma parte del VALOR del parametro, y
     * meterlo en el JPQL obligaria a concatenar sobre la sentencia. Ese mismo
     * helper neutraliza los comodines que escriba la persona, de modo que buscar
     * `%` no devuelve el listado entero y buscar `_` no casa con cualquier
     * caracter. El `ESCAPE` se declara aqui explicitamente en vez de confiar en
     * el valor por defecto del motor.
     *
     * Devuelve DTO y no entidades: es una lista de sugerencias, y arrastrar
     * entidades gestionadas para pintar un desplegable no aporta nada.
     *
     * El tope lo impone el `Pageable` del servicio: un autocompletado no mejora
     * por devolver el proveedor numero doscientos.
     *
     * Orden por nombre y despues por id. El id no es decorativo: dos filas con
     * el mismo nombre —imposibles hoy dentro de un taller, posibles el dia que
     * el indice canonico cambie— podrian alternarse entre peticiones identicas.
     */
    @Query("""
        SELECT new com.cns.spring_house.supplier.dto.SupplierResponseDTO(
            supplier.id,
            supplier.name)
        FROM Supplier supplier
        WHERE supplier.workshop.id = :workshopId
          AND (:pattern IS NULL OR LOWER(supplier.name) LIKE :pattern ESCAPE '\\')
        ORDER BY supplier.name ASC, supplier.id ASC
    """)
    List<SupplierResponseDTO> search(
            @Param("workshopId") UUID workshopId,
            @Param("pattern") String pattern,
            Pageable pageable);

    /**
     * El proveedor del taller cuyo nombre es EL MISMO una vez canonizado.
     *
     * La expresion reproduce literalmente la del indice
     * `uq_supplier_canonical_name_per_workshop`. Se escribe en SQL nativo y no
     * en JPQL porque `REGEXP_REPLACE` no existe en JPQL, y separarla del indice
     * —comparando por ejemplo solo con LOWER— dejaria al servicio dando por
     * nuevo un nombre que la base rechazaria despues con un 500.
     *
     * La usa el alta para responder un error de negocio entendible, y la
     * recuperacion de la carrera para devolver el proveedor que gano.
     */
    @Query(nativeQuery = true, value = """
        SELECT *
        FROM supplier
        WHERE workshop_id = :workshopId
          AND UPPER(REGEXP_REPLACE(BTRIM(name), '\\s+', ' ', 'g')) = :canonicalName
    """)
    Optional<Supplier> findByCanonicalName(
            @Param("workshopId") UUID workshopId,
            @Param("canonicalName") String canonicalName);

}
