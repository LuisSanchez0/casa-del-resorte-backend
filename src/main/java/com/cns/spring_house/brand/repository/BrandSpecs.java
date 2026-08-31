package com.cns.spring_house.brand.repository;

import org.springframework.data.jpa.domain.Specification;

import com.cns.spring_house.brand.entity.Brand;
import com.cns.spring_house.shared.util.LikePatterns;

public class BrandSpecs {

    private BrandSpecs() {
        
    }
    
    /**
     * Nombre que CONTIENE el texto, sin distinguir mayusculas.
     *
     * Aqui el caso ya se trataba —los dos lados bajaban a minusculas—, pero el
     * patron se armaba a mano y por tanto `%` y `_` seguian siendo comodines:
     * buscar "%" devolvia TODAS las marcas, que es lo contrario de buscar. Se
     * pasa al MISMO helper que producto, servicio, familia y modelo para que los
     * cinco buscadores traten el texto igual.
     */
    public static Specification<Brand> nameContains(String name) {
        return (root, query, criteriaBuilder) -> name == null ? null
                : criteriaBuilder.like(
                        criteriaBuilder.lower(root.get("name")),
                        LikePatterns.containsIgnoreCase(name),
                        LikePatterns.ESCAPE_CHAR);
    }

}
