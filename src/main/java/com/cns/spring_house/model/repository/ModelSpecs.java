package com.cns.spring_house.model.repository;

import java.util.UUID;

import org.springframework.data.jpa.domain.Specification;

import com.cns.spring_house.model.entity.Model;
import com.cns.spring_house.shared.util.LikePatterns;

public class ModelSpecs {
    
    private ModelSpecs() {

    }

    public static Specification<Model> hasBrand(UUID brand) {
        return (root, query, criteriaBuilder) -> brand == null ? null
                : criteriaBuilder.equal(root.get("brand").get("id"), brand);
    }

    /**
     * Modelos cuyo intervalo CUBRE el año preguntado, no los que empiezan en
     * ese año: un 2023 tiene que encontrar tanto al modelo 2023 como al
     * 2020-2025, y no al 2019. Con los dos extremos iguales la comparación
     * degenera en la igualdad de antes, así que un modelo de un solo año
     * sigue respondiendo a su propio año y a ningún otro.
     */
    public static Specification<Model> coversYear(Integer year) {
        return (root, query, criteriaBuilder) -> year == null ? null
                : criteriaBuilder.and(
                        criteriaBuilder.lessThanOrEqualTo(root.get("yearFrom"), year),
                        criteriaBuilder.greaterThanOrEqualTo(root.get("yearTo"), year));
    }

    /**
     * Nombre que CONTIENE el texto, sin distinguir mayusculas.
     *
     * Mismo defecto que ya se corrigio en producto y en servicio: la consulta
     * bajaba a minusculas la COLUMNA y dejaba el parametro tal cual, de modo que
     * `LOWER(name) LIKE '%Hilux%'` no casaba nunca con "hilux". Quien escribe el
     * modelo de un vehiculo lo escribe como se escribe —con mayuscula inicial—,
     * asi que el buscador se quedaba vacio justo en el caso normal.
     *
     * El patron lo arma {@link LikePatterns}, el MISMO helper del resto de
     * buscadores, que ademas neutraliza `%`, `_` y la propia barra.
     */
    public static Specification<Model> nameContains(String name) {
        return (root, query, criteriaBuilder) -> name == null ? null
                : criteriaBuilder.like(
                        criteriaBuilder.lower(root.get("name")),
                        LikePatterns.containsIgnoreCase(name),
                        LikePatterns.ESCAPE_CHAR);
    }

}
