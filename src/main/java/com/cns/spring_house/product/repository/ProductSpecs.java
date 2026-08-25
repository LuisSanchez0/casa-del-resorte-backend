package com.cns.spring_house.product.repository;

import java.util.UUID;

import org.springframework.data.jpa.domain.Specification;

import com.cns.spring_house.product.entity.Product;
import com.cns.spring_house.shared.util.LikePatterns;

import jakarta.persistence.criteria.Expression;

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

    public static Specification<Product> hasAttribute(String key, Object value) {
        return (root, query, criteriaBuilder) -> {
            Expression<String> jsonb = root.get("attributes");
            return criteriaBuilder.equal(
                    criteriaBuilder.function(
                            "jsonb_extract_path_text",
                            String.class,
                            jsonb,
                            criteriaBuilder.literal(key)),
                    value);
        };
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
