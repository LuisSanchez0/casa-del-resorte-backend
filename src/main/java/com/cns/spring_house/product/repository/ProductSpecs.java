package com.cns.spring_house.product.repository;

import java.math.BigDecimal;
import java.util.UUID;

import org.hibernate.query.criteria.HibernateCriteriaBuilder;
import org.hibernate.query.criteria.JpaExpression;
import org.springframework.data.jpa.domain.Specification;

import com.cns.spring_house.product.entity.Product;
import com.cns.spring_house.shared.util.LikePatterns;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Root;

public class ProductSpecs {

    private ProductSpecs() {

    }

    public static Specification<Product> hasFamily(UUID family) {
        return (root, query, criteriaBuilder) -> family == null ? null
                : criteriaBuilder.equal(
                        root.get("family").get("id"),
                        family);
    }

    public static Specification<Product> hasModel(UUID model) {
        return (root, query, criteriaBuilder) -> model == null ? null
                : criteriaBuilder.equal(
                        root.get("model").get("id"),
                        model);
    }

    /**
     * Productos de una MARCA, a traves del modelo al que pertenecen.
     *
     * La marca no cuelga del producto: el camino real es
     * `product.model.brand`, y por eso este filtro necesita el modelo para
     * resolverse. La union es explicita e INTERNA a proposito, porque eso es
     * exactamente lo que significa preguntar por una marca: una pieza sin
     * modelo no pertenece a ninguna, asi que no puede estar entre las de
     * Toyota.
     *
     * Lo que si importa es que esa union NO exista cuando no se filtra por
     * marca. Devolver `null` sin marca —igual que hacen el resto de
     * especificaciones— deja la consulta tal y como estaba, y es lo que impide
     * que anadir este criterio convierta la busqueda general en un INNER JOIN
     * que borraria del catalogo a todas las piezas sin modelo.
     *
     * `get("brand").get("id")` no anade una segunda union: la marca es un
     * `@ManyToOne` y su id vive en la columna `model.brand_id`.
     */
    public static Specification<Product> hasBrand(UUID brand) {
        return (root, query, criteriaBuilder) -> brand == null ? null
                : criteriaBuilder.equal(
                        root.join("model", JoinType.INNER)
                                .get("brand")
                                .get("id"),
                        brand);
    }

    /**
     * Caracteristica comparada como TEXTO. Es lo que corresponde a un atributo
     * STRING o BOOLEAN: el JSONB los serializa siempre igual (`"acero"`,
     * `true`), asi que su representacion textual es estable.
     *
     * NO sirve para los numericos: ver {@link #hasNumericAttribute}.
     */
    public static Specification<Product> hasAttribute(String key, Object value) {
        return (root, query, criteriaBuilder) -> criteriaBuilder.equal(
                attributeAsText(root, criteriaBuilder, key),
                String.valueOf(value));
    }

    /**
     * Caracteristica comparada como NUMERO, no como texto.
     *
     * El JSONB guarda el numero tal y como se serializo al crear el producto, y
     * eso NO es uniforme en el catalogo real: la misma medida aparece como
     * `15.0` en unas piezas y como `200` en otras, segun por que camino se
     * dieron de alta. Su texto (`"15.0"` frente a `"15"`) no coincide aunque el
     * valor sea el mismo, de modo que comparar cadenas dejaba sin encontrar
     * justo a las piezas guardadas con decimal: buscar largo=15 no devolvia la
     * hoja de largo 15.
     *
     * Convertir los dos lados a numero elimina esa diferencia de escritura
     * —`15.0 = 15` es cierto en `numeric`— y hace que el filtro signifique lo
     * que la familia declara que es: un NUMBER.
     *
     * Solo se aplica a los atributos que la familia declara NUMBER, y por eso la
     * conversion es segura: `ProductServiceImpl.validateAttributes` impide
     * guardar ahi cualquier cosa que no sea un numero.
     */
    public static Specification<Product> hasNumericAttribute(String key, BigDecimal value) {
        return (root, query, criteriaBuilder) -> {
            // El CAST tiene que llegar al SQL de verdad. `Expression.as(...)`
            // NO lo emite: se limita a reinterpretar el tipo en Java, asi que la
            // consulta salia comparando `text = numeric` y PostgreSQL la
            // rechazaba entera ("operator does not exist"). `cast` de Hibernate
            // si escribe `cast(... as numeric)`, que es lo unico que iguala
            // "15.0" con 15.
            HibernateCriteriaBuilder builder = (HibernateCriteriaBuilder) criteriaBuilder;
            JpaExpression<String> text = builder.function(
                    "jsonb_extract_path_text",
                    String.class,
                    root.get("attributes"),
                    builder.literal(key));

            return builder.equal(builder.cast(text, BigDecimal.class), value);
        };
    }

    private static Expression<String> attributeAsText(
            Root<Product> root,
            CriteriaBuilder criteriaBuilder,
            String key) {
        Expression<String> jsonb = root.get("attributes");
        return criteriaBuilder.function(
                "jsonb_extract_path_text",
                String.class,
                jsonb,
                criteriaBuilder.literal(key));
    }

    /**
     * Nombre que CONTIENE el texto, sin distinguir mayusculas.
     *
     * La consulta bajaba a minusculas la COLUMNA pero no el parametro, asi que
     * comparaba "muelle rectificado" contra "%MUELLE%" y no encontraba nada:
     * buscar en mayusculas —o con la primera letra en mayuscula, que es como
     * escribe cualquiera— devolvia cero resultados sobre un catalogo que si
     * tenia la pieza. Ahora los dos lados van en minusculas.
     *
     * El patron lo arma {@link LikePatterns}, que ademas neutraliza `%`, `_` y
     * la propia barra: un producto que se llama "LOTE_2026" se busca escribiendo
     * "lote_2026" y no casando con cualquier caracter en esa posicion. Por eso
     * se declara tambien el caracter de escape, en vez de confiar en el que
     * PostgreSQL usa por defecto.
     */
    public static Specification<Product> nameContains(String name) {
        return (root, query, criteriaBuilder) -> name == null ? null
                : criteriaBuilder.like(
                        criteriaBuilder.lower(root.get("name")),
                        LikePatterns.containsIgnoreCase(name),
                        LikePatterns.ESCAPE_CHAR);
    }

    /**
     * Mismo criterio para el SKU, y por el mismo motivo: el buscador de producto
     * manda UN texto que se compara contra nombre O sku, de modo que arreglar
     * solo uno dejaba la busqueda a medias en mayusculas.
     */
    public static Specification<Product> skuContains(String sku) {
        return (root, query, criteriaBuilder) -> sku == null ? null
                : criteriaBuilder.like(
                        criteriaBuilder.lower(root.get("sku")),
                        LikePatterns.containsIgnoreCase(sku),
                        LikePatterns.ESCAPE_CHAR);
    }

}
