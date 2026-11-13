package com.cns.spring_house.model.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.cns.spring_house.model.entity.Model;

import jakarta.persistence.LockModeType;

public interface ModelRepository extends JpaRepository<Model, UUID>, JpaSpecificationExecutor<Model> {

    /**
     * La misma combinacion marca/nombre/años, VIGENTE O ELIMINADA: la UNIQUE
     * de la tabla la sigue reservando aunque el modelo se haya eliminado.
     */
    Optional<Model> findByNameAndYearFromAndYearToAndBrandId(
            String name, Integer yearFrom, Integer yearTo, UUID brand);

    /** Para editarlo o eliminarlo. Ver {@code BrandRepository#findByIdForUpdate}. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT model FROM Model model WHERE model.id = :id")
    Optional<Model> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Si el modelo se puede asignar a algo nuevo —el y su marca vigentes—,
     * leido bajo FOR SHARE sobre las DOS filas: sin FOR ... OF, PostgreSQL
     * bloquea las filas de todas las tablas del FROM. Eliminar el modelo o su
     * marca a la vez espera a que termine la asignacion; y si la eliminacion
     * ya se confirmo, esta lectura la ve. Vacio si el modelo no existe.
     */
    @Query(value = """
            SELECT m.deleted_at IS NULL AND b.deleted_at IS NULL
            FROM model m
            JOIN brand b ON b.id = m.brand_id
            WHERE m.id = :id
            FOR SHARE""",
            nativeQuery = true)
    Optional<Boolean> lockSelectable(@Param("id") UUID id);

}
