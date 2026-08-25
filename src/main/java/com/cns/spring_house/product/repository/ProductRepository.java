package com.cns.spring_house.product.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import com.cns.spring_house.product.entity.Product;


public interface ProductRepository extends JpaRepository<Product, UUID>, JpaSpecificationExecutor<Product> {
    
    boolean existsBySku(String sku);

    boolean existsBySkuAndIdNot(String sku, UUID id);

    /**
     * Pieza equivalente: misma familia, mismo modelo y exactamente las mismas
     * características. El nombre no entra en la comparación, así que sirve para
     * el nombre automático, que no lo elige nadie.
     *
     * `ORDER BY ... LIMIT 1` no es cosmético: dos productos pueden compartir
     * familia, modelo y atributos y diferenciarse solo en el nombre —el catálogo
     * los admite y `findTargetProductNamed` los crea cuando el operador pide un
     * nombre propio—. Sin el límite, la consulta devolvería más de una fila y
     * Spring Data reventaría el `Optional` con un error de tamaño de resultado.
     */
    @Query(nativeQuery = true, value = """
        SELECT *
        from product p
        WHERE p.family_id = :familyId
            AND p.model_id = :modelId
            AND p.attributes = cast(:attributes as jsonb)
        ORDER BY p.created_at, p.id
        LIMIT 1
    """)
    Optional<Product> findTargetProduct(UUID familyId, UUID modelId, String attributes);

    /**
     * Producto por nombre, sin distinguir mayusculas ni espacios sobrantes. Lo
     * usa el Daily Log para constatar que el nombre que escribe la secretaria
     * existe en el catalogo y para quedarse con la grafia canonica —"alineacion"
     * entra, "ALINEACION" es lo que se guarda—.
     *
     * `product.name` NO es unico en el esquema (solo lo es el SKU), asi que el
     * mismo nombre puede pertenecer a varias piezas. Para esta comprobacion da
     * igual cual: lo unico que se pregunta es si el nombre existe, y de la fila
     * elegida solo se lee el texto. `ORDER BY ... LIMIT 1` esta por la misma
     * razon que en `findTargetProduct`: sin el, un nombre repetido reventaria el
     * `Optional` con un error de tamano de resultado.
     */
    @Query(nativeQuery = true, value = """
        SELECT *
        FROM product p
        WHERE UPPER(TRIM(p.name)) = UPPER(TRIM(:name))
        ORDER BY p.created_at, p.id
        LIMIT 1
    """)
    Optional<Product> findByNameIgnoringCaseAndSpaces(String name);

    /**
     * La misma pieza equivalente, pero además con el nombre que se pide. Cuando
     * el operador escribe un nombre, ese nombre forma parte de lo que está
     * pidiendo: reutilizar una pieza equivalente que se llama de otra forma
     * ignoraría su decisión, y renombrarla cambiaría una pieza que otros
     * inventarios e historiales ya usan.
     */
    @Query(nativeQuery = true, value = """
        SELECT *
        from product p
        WHERE p.family_id = :familyId
            AND p.model_id = :modelId
            AND p.attributes = cast(:attributes as jsonb)
            AND p.name = :name
        ORDER BY p.created_at, p.id
        LIMIT 1
    """)
    Optional<Product> findTargetProductNamed(
            UUID familyId, UUID modelId, String attributes, String name);

}
