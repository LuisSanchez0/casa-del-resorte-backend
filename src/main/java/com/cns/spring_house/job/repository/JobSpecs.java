package com.cns.spring_house.job.repository;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.data.jpa.domain.Specification;

import com.cns.spring_house.job.entity.Job;
import com.cns.spring_house.shared.util.LikePatterns;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class JobSpecs {
   
    /**
     * Servicio cuyo nombre CONTIENE el texto, sin distinguir mayusculas.
     *
     * El nombre de un servicio no vive en `Job` sino en `Job.product.name`, y la
     * consulta ponia en MAYUSCULAS la columna dejando el parametro tal cual: solo
     * encontraba algo si quien buscaba escribia ya en mayusculas. La caja lo
     * tapaba mandando el texto con `toUpperCase()` desde Angular, y la bitacora
     * —que manda lo que la persona escribio— no encontraba nada al teclear
     * "soldadura". El caso no lo cubria ninguna prueba porque las del frontend
     * simulan la respuesta HTTP.
     *
     * Ahora los dos lados van en minusculas, con el MISMO helper y el mismo
     * escape que la busqueda de producto, de modo que `%`, `_` y la barra son
     * texto literal y no comodines.
     */
    public static Specification<Job> nameContains(String name) {
        return (root, query, criteriaBuilder) -> name == null ? null
                : criteriaBuilder.like(
                        criteriaBuilder.lower(root.get("product").get("name")),
                        LikePatterns.containsIgnoreCase(name),
                        LikePatterns.ESCAPE_CHAR);
    }

    public static Specification<Job> priceBetween(BigDecimal min, BigDecimal max) {
        return (root, query, criteriaBuilder) -> {
            if (min != null && max != null) 
                    return criteriaBuilder.between(root.get("price"), min, max);
            if (max != null)
                    return criteriaBuilder.lessThanOrEqualTo(root.get("price"), max);
            if (min != null)
                    return criteriaBuilder.greaterThanOrEqualTo(root.get("price"), min);
            return null;
        };
    }

    public static Specification<Job> hasWorkshop(UUID workshop) {
        return (root, query, criteriaBuilder) -> workshop == null ? null
                : criteriaBuilder.equal(
                        root.get("workshop").get("id"),
                        workshop);
    }
    
}
