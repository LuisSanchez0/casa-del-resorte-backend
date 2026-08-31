package com.cns.spring_house.family.repository;

import org.springframework.data.jpa.domain.Specification;

import com.cns.spring_house.family.entity.Family;
import com.cns.spring_house.family.entity.FamilyCategory;
import com.cns.spring_house.shared.util.LikePatterns;

public class FamilySpecs {

    private FamilySpecs() {

    }

    /**
     * Codigo que CONTIENE el texto, sin distinguir mayusculas.
     *
     * Mismo defecto que ya se corrigio en producto y en servicio: la consulta
     * bajaba a minusculas la COLUMNA y dejaba el parametro tal cual, asi que
     * `LOWER(code) LIKE '%HOJ%'` no casaba nunca. Solo encontraba algo quien
     * escribiera ya en minusculas, y el buscador de familias de la caja —que
     * manda lo que la persona teclea— se quedaba sin resultados al escribir
     * "Hoja" o "HOJA".
     *
     * El patron lo arma {@link LikePatterns}, el MISMO helper de producto y
     * servicio, de modo que `%`, `_` y la propia barra son texto literal: una
     * familia llamada "LOTE_A" se busca escribiendo "lote_a" y no casa con
     * cualquier caracter en esa posicion.
     */
    public static Specification<Family> codeContains(String code) {
        return (root, query, criteriaBuilder) -> code == null ? null
                : criteriaBuilder.like(
                        criteriaBuilder.lower(root.get("code")),
                        LikePatterns.containsIgnoreCase(code),
                        LikePatterns.ESCAPE_CHAR);
    }

    /**
     * Mismo criterio para el nombre, y por el mismo motivo: el buscador manda UN
     * texto que se compara contra codigo O nombre, de modo que arreglar solo uno
     * dejaba la busqueda a medias en mayusculas.
     */
    public static Specification<Family> nameContains(String name) {
        return (root, query, criteriaBuilder) -> name == null ? null
                : criteriaBuilder.like(
                        criteriaBuilder.lower(root.get("name")),
                        LikePatterns.containsIgnoreCase(name),
                        LikePatterns.ESCAPE_CHAR);
    }

    public static Specification<Family> requiresModel(Boolean requiresModel) {
        return (root, query, criteriaBuilder) -> requiresModel == null ? null
                : criteriaBuilder.equal(
                        root.get("requiresModel"),
                        requiresModel);
    }

    public static Specification<Family> hasCategory(FamilyCategory category) {
        return (root, query, criteriaBuilder) -> category == null ? null
                : criteriaBuilder.equal(
                        root.get("category"),
                        category);
    }

}
