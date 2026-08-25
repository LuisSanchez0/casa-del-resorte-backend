package com.cns.spring_house.transformation.handler;

import com.cns.spring_house.family.entity.FamilyCategory;
import com.cns.spring_house.inventory.entity.InventoryItem;
import com.cns.spring_house.transformation.dto.CreateTransformationRequestDTO;
import com.cns.spring_house.transformation.entity.Transformation;

public interface TransformationHandler {

    FamilyCategory getType();

    /**
     * Recibe la petición completa en lugar de una lista creciente de parámetros:
     * cada categoría usa un subconjunto distinto (CONTAINER lee `containedItems`,
     * SPECIAL lee los atributos y los colochos) y así añadir una intención nueva
     * no obliga a tocar la firma de todos los manejadores.
     *
     * `containedItems` son unidades físicas a liberar en un desarmado selectivo
     * de CONTAINER; nulo o vacío significa desarmado completo. Los manejadores
     * que no lo soportan lo ignoran.
     */
    void generate(
            Transformation transformation,
            InventoryItem inputItem,
            CreateTransformationRequestDTO requestDTO);

}
