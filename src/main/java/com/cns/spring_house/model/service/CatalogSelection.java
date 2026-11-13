package com.cns.spring_house.model.service;

import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.cns.spring_house.brand.entity.Brand;
import com.cns.spring_house.brand.repository.BrandRepository;
import com.cns.spring_house.model.entity.Model;
import com.cns.spring_house.model.repository.ModelRepository;
import com.cns.spring_house.shared.exception.EntityNotFoundException;
import com.cns.spring_house.shared.exception.ValidationException;

import lombok.RequiredArgsConstructor;

/**
 * Unica puerta para ASIGNAR una marca o un modelo a algo nuevo: un producto
 * dado de alta o cambiado de modelo, una pieza pedida por especificacion, un
 * modelo creado o movido de marca. Asi ninguna de esas rutas puede aplicar una
 * regla distinta de "solo lo vigente".
 *
 * Lo que ya estaba asignado no pasa por aqui: un producto historico conserva su
 * modelo eliminado, y una pieza que sale de transformar otra hereda el modelo
 * de la pieza fisica, que no es una eleccion.
 *
 * Exige la transaccion de quien asigna: el cerrojo compartido que toma la
 * comprobacion dura hasta que esa transaccion termina, de modo que la
 * eliminacion simultanea espera y no puede dejar recien asignado algo que ya
 * no esta vigente.
 */
@Component
@RequiredArgsConstructor
public class CatalogSelection {

    static final String DELETED_MODEL_MESSAGE =
            "El modelo seleccionado fue eliminado: elija un modelo vigente";

    static final String DELETED_BRAND_MESSAGE =
            "La marca seleccionada fue eliminada: elija una marca vigente";

    private final ModelRepository modelRepository;
    private final BrandRepository brandRepository;

    /** El modelo, si el y su marca siguen vigentes. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Model requireSelectableModel(UUID modelId) {
        boolean selectable = modelRepository.lockSelectable(modelId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el modelo especificado"));
        if (!selectable) {
            throw new ValidationException(
                    DELETED_MODEL_MESSAGE, Map.of("model", DELETED_MODEL_MESSAGE));
        }
        return modelRepository.findById(modelId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el modelo especificado"));
    }

    /** La marca, si sigue vigente. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Brand requireSelectableBrand(UUID brandId) {
        boolean selectable = brandRepository.lockSelectable(brandId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe la marca especificada"));
        if (!selectable) {
            throw new ValidationException(
                    DELETED_BRAND_MESSAGE, Map.of("brand", DELETED_BRAND_MESSAGE));
        }
        return brandRepository.findById(brandId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe la marca especificada"));
    }

}
