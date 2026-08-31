package com.cns.spring_house.product.dto;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import com.cns.spring_house.shared.exception.ValidationException;

/**
 * Criterios de `GET /v1/products`.
 *
 * El endpoint no declara sus parametros uno a uno: recibe el mapa entero de la
 * query y trata como filtro de CARACTERISTICA todo lo que no reconozca. Por eso
 * esta clase es el unico sitio donde se decide que nombres estan reservados, y
 * por eso cada nuevo criterio tiene que retirarse aqui del mapa: un `brand` que
 * no se retirase seguiria viaje como atributo dinamico y acabaria en un
 * `jsonb_extract_path_text(attributes, 'brand') = '<uuid>'`, es decir en una
 * busqueda que no devuelve nada en lugar de en la que se pidio.
 *
 * `extractUUID` retira la clave SIEMPRE, incluso cuando el texto no es un UUID:
 * un criterio reservado no puede degradarse a caracteristica pase lo que pase.
 * Lo que ya NO hace es tragarselo. Un `brand=toyota` se rechaza con 400 en vez
 * de buscar como si nadie hubiera pedido una marca: devolver el catalogo entero
 * ante un identificador ilegible es indistinguible desde la caja de haber
 * buscado bien, y el vendedor se lleva a la pantalla piezas de todas las marcas
 * creyendo que son las de una. Un identificador que no se entiende es un error
 * de quien lo manda, no un filtro menos.
 */
public record SearchProductRequestDTO(
        String q,
        UUID family,
        UUID brand,
        UUID model,
        Map<String, Object> attributes) {

    /**
     * Forma canonica 8-4-4-4-12. Se comprueba antes de `UUID.fromString`
     * porque ese metodo es MAS permisivo de lo que aparenta: acepta grupos
     * cortos como "1-1-1-1-1" y los rellena de ceros, de modo que un texto que
     * nadie reconoceria como identificador entraria como marca valida y la
     * busqueda responderia 200 con la lista vacia. Con el patron delante, lo
     * que no tiene forma de UUID se rechaza como lo que es.
     */
    private static final Pattern CANONICAL_UUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
                    + "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    public static SearchProductRequestDTO from(Map<String, Object> filters) {
        String q = extractString(filters, "q");
        UUID family = extractUUID(filters, "family", "de la familia");
        UUID brand = extractUUID(filters, "brand", "de la marca");
        UUID model = extractUUID(filters, "model", "del modelo");

        return new SearchProductRequestDTO(q, family, brand, model, filters);
    }

    private static String extractString(Map<String, Object> map, String key) {
        Object value = map.remove(key);
        return value != null ? String.valueOf(value) : null;
    }

    /**
     * @param subject como nombrar el criterio en el mensaje de error, ya
     *                declinado ("de la marca", "del modelo").
     */
    private static UUID extractUUID(Map<String, Object> map, String key, String subject) {
        Object value = map.remove(key);
        if (value == null) {
            return null;
        }

        // Un parametro opcional escrito pero vacio (`?brand=`) es un parametro
        // que no se ha puesto, igual que en el resto de DTOs del proyecto. Es
        // lo que manda un formulario que no ha elegido nada, no un error.
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }

        if (!CANONICAL_UUID.matcher(text).matches()) {
            throw new ValidationException(
                    "El identificador " + subject + " no es válido.");
        }

        return UUID.fromString(text);
    }
}
