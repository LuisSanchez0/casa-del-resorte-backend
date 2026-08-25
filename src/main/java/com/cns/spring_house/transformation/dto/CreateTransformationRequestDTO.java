package com.cns.spring_house.transformation.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.cns.spring_house.shared.constants.ColumnLimits;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateTransformationRequestDTO(

        @NotNull(message = "El item de entrada es obligatorio")
        UUID inputItem,
        Double newLength,
        UUID outputProduct,
        /**
         * Solo para desarmado selectivo de CONTAINER: identificadores de
         * InventoryItem (unidades físicas ya contenidas en el contenedor), no de
         * Product ni de Inventory. Nulo o vacío significa desarmar por completo.
         * No aplica a SPECIAL.
         */
        List<UUID> containedItems,
        /**
         * Transformación SPECIAL personalizada: características deseadas de la
         * pieza de salida cuando no existe todavía un producto que las cumpla.
         * Solo se usa si `outputProduct` viene nulo. La familia, el modelo y la
         * categoría NO viajan aquí: el backend las deriva del producto de
         * entrada para que el cliente no pueda cambiarlas.
         *
         * `cantidadColochos` NO se acepta dentro de este mapa: su fuente oficial
         * es `outputCurlCount`, y admitir las dos permitiría enviar valores
         * contradictorios para la misma pieza.
         */
        Map<String, Object> outputAttributes,
        /**
         * Colochos de la pieza principal. `0` significa que no llevará colochos;
         * un valor mayor, cuántos. Es independiente de `outputProduct` y de
         * `outputAttributes`: manda sobre ambos, de modo que el operador no tiene
         * que buscar un producto que ya tenga exactamente esa cantidad.
         */
        @Min(value = 0, message = "La cantidad de colochos de la pieza resultante debe ser un entero mayor o igual a cero")
        Integer outputCurlCount,
        /**
         * Colochos del sobrante, cuando la transformación genera uno. Es una pieza
         * física distinta de la principal, así que su cantidad se decide aparte y
         * nunca se copia de la salida ni de la entrada.
         *
         * Obligatorio si hay sobrante; rechazado si no lo hay.
         */
        @Min(value = 0, message = "La cantidad de colochos del sobrante debe ser un entero mayor o igual a cero")
        Integer remainderCurlCount,
        /**
         * Nombre de la pieza resultante cuando el operador quiere ponerlo el.
         * Nulo o en blanco conserva el nombre automatico que arma el manejador a
         * partir de la familia y el largo, que es el comportamiento de siempre.
         *
         * Es el nombre de la pieza resultante, no una etiqueta aparte: se aplica
         * al Product que se crea. Ningun producto ya existente se renombra por
         * esta via; solo se reutiliza uno equivalente que YA se llame asi, y si
         * no lo hay nace uno nuevo con este nombre.
         */
        @Size(max = ColumnLimits.PRODUCT_NAME,
                message = "El nombre de la pieza resultante no puede superar los {max} caracteres")
        String outputName

) {

    /**
     * Un nombre en blanco no es un nombre: se normaliza a null para que caiga en
     * el camino del nombre automatico en lugar de guardar una cadena vacia.
     */
    public CreateTransformationRequestDTO {
        outputName = outputName == null || outputName.isBlank() ? null : outputName.trim();
    }

}
