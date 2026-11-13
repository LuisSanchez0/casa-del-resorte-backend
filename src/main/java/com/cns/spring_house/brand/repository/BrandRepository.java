package com.cns.spring_house.brand.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.cns.spring_house.brand.entity.Brand;

import jakarta.persistence.LockModeType;

public interface BrandRepository extends JpaRepository<Brand, UUID>, JpaSpecificationExecutor<Brand> {

    /**
     * Por nombre exacto, VIGENTE O ELIMINADA: el nombre de una marca eliminada
     * no queda libre (lo sigue ocupando la UNIQUE de la tabla), y quien da de
     * alta o renombra tiene que saber que choca con ella.
     */
    Optional<Brand> findByName(String name);

    /**
     * Para editarla o eliminarla: FOR UPDATE. Editar y eliminar a la vez quedan
     * en fila; sin el cerrojo, un PATCH que leyo la marca vigente podia
     * guardarla DESPUES de la eliminacion y escribir `deleted_at = NULL`
     * (Hibernate actualiza todas las columnas), reviviendola.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT brand FROM Brand brand WHERE brand.id = :id")
    Optional<Brand> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Si la marca se puede elegir para algo nuevo, leido BAJO cerrojo
     * compartido (FOR SHARE) que se mantiene hasta el final de la transaccion
     * de quien la asigna. Una eliminacion simultanea espera; y si se confirmo
     * antes, esta lectura ya la ve. Varias asignaciones no se bloquean entre
     * si. Vacio si la marca no existe.
     *
     * Devuelve el valor de la propia sentencia, no una entidad: una entidad ya
     * cargada en el contexto no se relee y podria ser anterior al cerrojo.
     */
    @Query(value = "SELECT b.deleted_at IS NULL FROM brand b WHERE b.id = :id FOR SHARE",
            nativeQuery = true)
    Optional<Boolean> lockSelectable(@Param("id") UUID id);

}
