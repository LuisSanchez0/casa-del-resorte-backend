package com.cns.spring_house.family.repository;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.cns.spring_house.family.entity.Family;

import jakarta.persistence.LockModeType;

public interface FamilyRepository extends JpaRepository<Family, UUID>, JpaSpecificationExecutor<Family> {

    boolean existsByCode(String code);

    boolean existsByCodeAndIdNot(String code, UUID id);

    /**
     * La familia bloqueada para escritura. Serializa el alta de una pieza pedida
     * por sus caracteristicas: dos ventas que piden a la vez la misma pieza
     * nueva reutilizan un solo producto en lugar de crear dos iguales (el
     * catalogo no tiene un indice unico por familia, modelo y caracteristicas).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT family FROM Family family WHERE family.id = :id")
    Optional<Family> findByIdForUpdate(@Param("id") UUID id);

    /**
     * De estas familias, las que declaran la caracteristica `lowerCaseName`
     * (comparada en minusculas, porque cada familia elige su grafia). Una sola
     * consulta para toda una cola, en vez de cargar los atributos de cada
     * familia.
     */
    @Query("""
        SELECT DISTINCT attribute.family.id
        FROM Attribute attribute
        WHERE attribute.family.id IN :familyIds
          AND LOWER(attribute.name) = :lowerCaseName
    """)
    Set<UUID> findIdsDeclaringAttribute(
            @Param("familyIds") Collection<UUID> familyIds,
            @Param("lowerCaseName") String lowerCaseName);

}
