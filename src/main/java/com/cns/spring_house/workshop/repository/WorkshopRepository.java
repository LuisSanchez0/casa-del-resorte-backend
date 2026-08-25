package com.cns.spring_house.workshop.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.cns.spring_house.workshop.entity.Workshop;

import jakarta.persistence.LockModeType;

public interface WorkshopRepository extends JpaRepository<Workshop, UUID>, JpaSpecificationExecutor<Workshop> {

    /**
     * Toma un cerrojo de escritura sobre la FILA del taller (un
     * {@code SELECT ... FOR UPDATE}) que dura hasta el final de la transaccion.
     *
     * Existe para serializar dos operaciones que compiten por la misma fecha
     * operativa: confirmar el cierre de caja y crear una venta nueva. Comprobar
     * `existsByWorkshopIdAndBusinessDate(...)` no basta por si solo —con el
     * aislamiento READ COMMITTED de PostgreSQL, la venta no ve el cierre que
     * otra transaccion todavia no ha confirmado, y el cierre tampoco ve la
     * venta—, asi que sin cerrojo las dos pasarian su comprobacion y la venta
     * quedaria persistida DESPUES de un cierre ya confirmado, ademas de fuera
     * del snapshot.
     *
     * Con ambos flujos tomando este cerrojo antes de comprobar, solo caben dos
     * ordenes y los dos son correctos: si gana la venta, se inserta y el cierre
     * posterior la incluye en sus cifras; si gana el cierre, la venta encuentra
     * el cierre confirmado y se rechaza.
     *
     * El cerrojo es por taller y no por (taller, fecha), lo cual es mas
     * restrictivo de lo estrictamente necesario y por tanto seguro. La
     * contencion es irrelevante: solo lo piden estas dos operaciones, ninguna
     * lectura se bloquea, y crear una venta es un unico INSERT.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT workshop FROM Workshop workshop WHERE workshop.id = :id")
    Optional<Workshop> lockById(@Param("id") UUID id);

}
