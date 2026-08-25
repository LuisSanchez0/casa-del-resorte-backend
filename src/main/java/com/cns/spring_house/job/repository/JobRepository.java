    package com.cns.spring_house.job.repository;

    import java.util.Optional;
    import java.util.UUID;

    import org.springframework.data.jpa.repository.JpaRepository;
    import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
    import org.springframework.data.jpa.repository.Query;

    import com.cns.spring_house.job.entity.Job;

    public interface JobRepository extends JpaRepository<Job, UUID>, JpaSpecificationExecutor<Job> {

        boolean existsByWorkshopIdAndProductId(UUID workshop, UUID product);

        Optional<Job> findByIdAndWorkshopId(UUID id, UUID workshopId);

        /**
         * La venta identifica un servicio por su PRODUCTO, igual que el resto de
         * categorias. La tabla tiene UNIQUE(product_id, workshop_id), asi que el
         * par resuelve un unico Job.
         */
        Optional<Job> findByProductIdAndWorkshopId(UUID productId, UUID workshopId);

        /**
         * Servicio por nombre dentro de un taller, sin distinguir mayusculas ni
         * espacios sobrantes. Lo usa el Daily Log para constatar que el nombre
         * del servicio existe y para quedarse con su grafia canonica.
         *
         * El nombre de un servicio no vive en `job`: `Job` solo tiene producto,
         * taller y precio, asi que hay que ir a `product.name` por el JOIN. Es
         * tambien la razon de validar los servicios SOLO por aqui y nunca contra
         * ProductRepository: el catalogo de productos contiene piezas que no son
         * servicios de este taller, y darlas por buenas dejaria registrar como
         * servicio cualquier repuesto.
         *
         * Se acota al taller porque un Job es de un taller —cada uno declara sus
         * servicios y su precio— y es exactamente lo que el autocompletado
         * ofrece: `JobServiceImpl.getAll` ya filtra por el taller del token.
         *
         * `LIMIT 1` porque `product.name` no es unico; para constatar existencia
         * cualquier coincidencia sirve.
         */
        @Query(nativeQuery = true, value = """
            SELECT j.*
            FROM job j
            JOIN product p ON p.id = j.product_id
            WHERE j.workshop_id = :workshopId
                AND UPPER(TRIM(p.name)) = UPPER(TRIM(:name))
            ORDER BY j.created_at, j.id
            LIMIT 1
        """)
        Optional<Job> findByNameIgnoringCaseAndSpaces(UUID workshopId, String name);

    }
