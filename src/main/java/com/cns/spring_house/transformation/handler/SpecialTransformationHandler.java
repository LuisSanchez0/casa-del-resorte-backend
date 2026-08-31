package com.cns.spring_house.transformation.handler;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.cns.spring_house.family.entity.Attribute;
import com.cns.spring_house.family.entity.AttributeDataType;
import com.cns.spring_house.acquisition.service.InventoryLotService;
import com.cns.spring_house.family.entity.Family;
import com.cns.spring_house.family.entity.FamilyCategory;
import com.cns.spring_house.inventory.entity.Inventory;
import com.cns.spring_house.inventory.entity.InventoryItem;
import com.cns.spring_house.inventory.entity.InventoryItemStatus;
import com.cns.spring_house.inventory.repository.InventoryItemRepository;
import com.cns.spring_house.inventory.repository.InventoryRepository;
import com.cns.spring_house.model.entity.Model;
import com.cns.spring_house.product.entity.Product;
import com.cns.spring_house.product.repository.ProductRepository;
import com.cns.spring_house.shared.exception.EntityNotFoundException;
import com.cns.spring_house.shared.exception.ValidationException;
import com.cns.spring_house.shared.security.SecurityUtils;
import com.cns.spring_house.transformation.dto.CreateTransformationRequestDTO;
import com.cns.spring_house.transformation.entity.Transformation;
import com.cns.spring_house.transformation.entity.TransformationItem;
import com.cns.spring_house.transformation.entity.TransformationItemRole;
import com.cns.spring_house.workshop.entity.Workshop;
import com.cns.spring_house.workshop.repository.WorkshopRepository;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class SpecialTransformationHandler implements TransformationHandler {

    /**
     * Nombres de los atributos definidos para la familia HOJA DE RESORTE
     * (tabla `attribute`). Se buscan ignorando mayúsculas y minúsculas, porque
     * cada familia elige libremente cómo escribir el nombre del atributo y el
     * Map de atributos del producto sí distingue el caso.
     */
    private static final String LENGTH_ATTRIBUTE = "LARGO";
    private static final String HOLE_ATTRIBUTE = "tieneOrificio";
    /**
     * Cantidad de colochos de la hoja. A diferencia de los dos anteriores, este
     * sí se valida contra el atributo declarado en la familia: así una petición
     * con otra grafía se guarda bajo la clave real y no nacen propiedades
     * paralelas en el JSONB.
     */
    private static final String CURL_COUNT_ATTRIBUTE = "cantidadColochos";

    private final ProductRepository productRepository;
    private final InventoryRepository inventoryRepository;
    private final InventoryItemRepository itemRepository;
    private final WorkshopRepository workshopRepository;
    private final ObjectMapper objectMapper;
    private final InventoryLotService lotService;

    @Override
    public FamilyCategory getType() {
        return FamilyCategory.SPECIAL;
    }

    /**
     * El sobrante se decide ANTES de materializar nada: si la petición no encaja
     * con lo que la pieza va a producir, debe fallar sin haber creado productos ni
     * unidades a medias.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Override
    public void generate(
            Transformation transformation,
            InventoryItem inputItem,
            CreateTransformationRequestDTO requestDTO) {
        Product inputProduct = inputItem.getInventory().getProduct();
        boolean supportsCurlCount = supportsCurlCount(inputProduct.getFamily());
        validateCurlCountSupport(supportsCurlCount, requestDTO);
        Product selectedProduct = requestDTO.outputProduct() == null
                ? null
                : resolveTargetProduct(requestDTO.outputProduct());
        if (selectedProduct != null) {
            validateSelectedProduct(inputProduct, selectedProduct);
        }
        Map<String, Object> outputAttributes =
                resolveOutputAttributes(inputProduct, selectedProduct, requestDTO);
        Optional<Double> remainderLength =
                resolveRemainderLength(inputProduct, outputAttributes);
        validateRemainderCurlCount(
                supportsCurlCount,
                remainderLength.isPresent(),
                requestDTO.remainderCurlCount());

        transformation.addItem(generateOutputItem(
                inputItem, selectedProduct, outputAttributes, requestDTO.outputName()));
        remainderLength
                .map(length -> generateRemainder(
                        inputProduct, length, requestDTO.remainderCurlCount()))
                .ifPresent(transformation::addItem);
        transformation.addItem(processInputItem(inputItem));
    }

    /**
     * Un producto de salida solo puede describir OTRA pieza de la misma familia:
     * la transformación cambia las características de la unidad, nunca lo que la
     * unidad es. Sin esta comprobación, elegir un producto de otra familia
     * cambiaba en silencio la familia de la pieza resultante, porque el producto
     * elegido era también el origen de familia y modelo.
     *
     * Sus características, además, deben ser las que la familia declara: copiar
     * una clave que la familia no admite propagaría a un producto nuevo un dato
     * inconsistente de otro registro.
     */
    private void validateSelectedProduct(Product inputProduct, Product selectedProduct) {
        Family family = inputProduct.getFamily();
        UUID familyId = family == null ? null : family.getId();
        Family selectedFamily = selectedProduct.getFamily();
        UUID selectedFamilyId = selectedFamily == null ? null : selectedFamily.getId();
        if (familyId == null || !familyId.equals(selectedFamilyId)) {
            throw new ValidationException(
                    "El producto de salida debe pertenecer a la misma familia que la pieza de entrada");
        }
        validateDeclaredAttributes(family, selectedProduct.getAttributes());
    }

    /**
     * A diferencia del PATCH, aquí se valida lo que otro Product tiene GUARDADO,
     * no lo que pide el cliente. Por eso solo se comprueba contra familias que
     * declaran atributos: una familia sin ninguno no ofrece contra qué comparar,
     * y rechazar entonces cualquier característica dejaría sin transformar a las
     * piezas que ya existen.
     */
    private void validateDeclaredAttributes(
            Family family, Map<String, Object> attributes) {
        List<Attribute> declared = family.getAttributes();
        if (declared == null || declared.isEmpty() || attributes == null) {
            return;
        }
        for (String key : attributes.keySet()) {
            if (declaredAttribute(family, key).isEmpty()) {
                throw new ValidationException(
                        "El producto de salida tiene una caracteristica que la familia no declara: "
                                + key);
            }
        }
    }

    /**
     * El sobrante es una pieza física distinta de la principal, así que su conteo
     * de colochos se exige explícitamente en lugar de heredarlo. Informarlo cuando
     * no habrá sobrante también se rechaza: significa que el cliente está
     * configurando una pieza que no va a existir.
     */
    private void validateRemainderCurlCount(
            boolean supportsCurlCount,
            boolean hasRemainder,
            Integer remainderCurlCount) {
        if (!supportsCurlCount) {
            // Sin la capacidad declarada el campo ya se rechazó por nulo.
            return;
        }
        if (hasRemainder && remainderCurlCount == null) {
            throw new ValidationException(
                    "Debes indicar la cantidad de colochos del sobrante");
        }
        if (!hasRemainder && remainderCurlCount != null) {
            throw new ValidationException(
                    "La transformacion no genera un sobrante al cual asignar colochos");
        }
    }

    private TransformationItem processInputItem(InventoryItem inputItem) {
        inputItem.setStatus(InventoryItemStatus.CONSUMED);
        inputItem.getInventory().removeStock(1);
        // La pieza de entrada desaparece del inventario, asi que su lote se
        // consume igual que en una venta. Sin esto, el inventario quedaria con
        // menos existencias que remanente de lote y el FIFO acabaria costeando
        // contra unidades que ya no estan.
        lotService.consumeWithoutTrace(inputItem.getInventory(), 1);
        return TransformationItem.builder()
                .item(inputItem)
                .role(TransformationItemRole.INPUT)
                .build();
    }

    /**
     * Resuelve la pieza de salida por id o, si no se indicó, a partir de las
     * características pedidas. En el modo personalizado la familia y el modelo
     * se toman SIEMPRE del producto de entrada, nunca del cliente, y se reutiliza
     * un producto equivalente si ya existe en lugar de duplicarlo.
     */
    /**
     * Características efectivas de la pieza principal. Se calculan antes de tocar
     * el catálogo porque también deciden si habrá sobrante.
     *
     * La precedencia es siempre la misma, de menor a mayor autoridad:
     *
     * <pre>
     * atributos de la ENTRADA
     *   -> atributos del outputProduct elegido  (modo producto existente)
     *   -> outputAttributes pedidos             (modo personalizado)
     *   -> outputCurlCount
     * </pre>
     *
     * Los dos modos intermedios son excluyentes por contrato (ver
     * TransformationServiceImpl), así que solo uno de ellos interviene en cada
     * petición. `outputCurlCount` se aplica el último en ambos: es la única
     * fuente de la cantidad de colochos, de modo que elegir un producto
     * existente no obliga a que ya tenga la cantidad deseada.
     */
    private Map<String, Object> resolveOutputAttributes(
            Product inputProduct,
            Product selectedProduct,
            CreateTransformationRequestDTO requestDTO) {
        Map<String, Object> attributes = selectedProduct != null
                ? mergeSelectedProduct(inputProduct, selectedProduct)
                : resolveCustomAttributes(inputProduct, requestDTO);
        if (requestDTO.outputCurlCount() != null) {
            putCanonical(
                    attributes,
                    resolveCurlCountKey(inputProduct.getFamily()),
                    requestDTO.outputCurlCount());
        }
        return attributes;
    }

    /**
     * La pieza resultante conserva lo que la entrada ya era y adopta lo que el
     * producto elegido describe. Partir del producto elegido a secas —como se
     * hacía antes— borraba del resultado cualquier característica que solo
     * tuviera la entrada, así que el operador estaba obligado a buscar un
     * producto idéntico salvo en lo que quería cambiar.
     *
     * Una clave ausente en el producto elegido NO significa "quitar el
     * atributo": significa que ese producto no dice nada al respecto y manda la
     * entrada. Las claves que sí trae pisan a la entrada, incluidas las que la
     * entrada no tenía.
     */
    private Map<String, Object> mergeSelectedProduct(
            Product inputProduct, Product selectedProduct) {
        Map<String, Object> attributes = copyOf(inputProduct.getAttributes());
        normalizeCurlCount(attributes);
        Map<String, Object> selected = selectedProduct.getAttributes();
        if (selected == null) {
            return attributes;
        }
        Family family = inputProduct.getFamily();
        for (Map.Entry<String, Object> entry : selected.entrySet()) {
            putCanonical(
                    attributes,
                    canonicalKey(family, attributes, entry.getKey()),
                    entry.getValue());
        }
        return attributes;
    }

    /**
     * Nombre con el que la característica debe quedar guardada: el que declara la
     * familia si lo declara, y si no el que ya usa el mapa. Así una petición y un
     * producto que escriban `LARGO` y `largo` no crean dos propiedades paralelas
     * en el JSONB.
     */
    private String canonicalKey(
            Family family, Map<String, Object> attributes, String key) {
        return declaredAttribute(family, key)
                .map(Attribute::getName)
                .orElseGet(() -> findAttributeKeyIgnoreCase(attributes, key));
    }

    /**
     * Escribe bajo el nombre canónico y retira cualquier variante que solo
     * difiera en mayúsculas, para que la clave no acabe duplicada.
     */
    private void putCanonical(
            Map<String, Object> attributes, String key, Object value) {
        attributes.keySet()
                .removeIf(current -> current != null
                        && !current.equals(key)
                        && current.equalsIgnoreCase(key));
        attributes.put(key, value);
    }

    private Product resolveTargetProduct(UUID outputProduct) {
        return productRepository
                .findById(outputProduct)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el producto especificado"));
    }

    private Map<String, Object> resolveCustomAttributes(
            Product inputProduct, CreateTransformationRequestDTO requestDTO) {
        Map<String, Object> outputAttributes = requestDTO.outputAttributes();
        boolean hasAttributes = outputAttributes != null && !outputAttributes.isEmpty();
        if (!hasAttributes && requestDTO.outputCurlCount() == null) {
            throw new ValidationException(
                    "Debes indicar el producto de salida o las características de la pieza resultante");
        }

        // El PATCH se valida ENTERO antes de tocar nada: así una característica
        // inválida no deja el mapa a medio aplicar ni llega a materializarse.
        Map<String, Object> patch = hasAttributes
                ? validatePatch(inputProduct.getFamily(), outputAttributes)
                : Map.of();

        // Partimos de los atributos de la entrada y solo pisamos los indicados,
        // así la salida conserva el resto de características de la familia.
        Map<String, Object> attributes = copyOf(inputProduct.getAttributes());
        // La entrada puede traer el conteo como 2.0 y la petición como 2: se
        // normaliza para no tratarlos como piezas distintas.
        normalizeCurlCount(attributes);
        for (Map.Entry<String, Object> requested : patch.entrySet()) {
            putCanonical(attributes, requested.getKey(), requested.getValue());
        }
        return attributes;
    }

    /**
     * Convierte las características pedidas por el cliente en un PATCH de
     * confianza: cada clave existe de verdad en la familia, cada valor respeta
     * el tipo declarado y la clave queda con la grafía canónica.
     *
     * El cliente no es una fuente fiable de nombres de atributo. Antes bastaba
     * con que una clave no coincidiera con ninguna de la entrada para que se
     * guardara tal cual, de modo que un `colorPintura` inventado acababa en el
     * JSONB de un Product nuevo. Aquí la única autoridad es
     * `Family.attributes`: lo que no declare la familia se rechaza en lugar de
     * copiarse.
     *
     * `null` se rechaza igual que cualquier otro tipo inválido —lo dice
     * `AttributeDataType.isValid`, la misma regla que aplica el alta de
     * productos—, de modo que este PATCH nunca elimina una característica: solo
     * añade o sustituye. Por eso tampoco puede dejar sin un atributo obligatorio
     * a la pieza resultante, y no hace falta revalidar los `isRequired` que la
     * entrada ya traía.
     */
    private Map<String, Object> validatePatch(
            Family family, Map<String, Object> requested) {
        Map<String, Object> patch = new HashMap<>();

        for (Map.Entry<String, Object> entry : requested.entrySet()) {
            String key = entry.getKey();

            if (key != null && key.equalsIgnoreCase(CURL_COUNT_ATTRIBUTE)) {
                throw new ValidationException(
                        "La cantidad de colochos se indica en outputCurlCount, no dentro de outputAttributes");
            }

            Attribute attribute = declaredAttribute(family, key)
                    .orElseThrow(() -> new ValidationException(
                            "La característica '" + key
                                    + "' no está declarada para la familia '"
                                    + familyName(family) + "'",
                            Map.of(String.valueOf(key), "Característica no declarada")));

            Object value = entry.getValue();
            if (!attribute.getDataType().isValid(value)) {
                throw new ValidationException(
                        "La característica '" + attribute.getName()
                                + "' debe ser de tipo " + attribute.getDataType(),
                        Map.of(attribute.getName(),
                                "Tipo inválido, se espera un " + attribute.getDataType()));
            }

            patch.put(attribute.getName(), value);
        }

        return patch;
    }

    private String familyName(Family family) {
        String name = family == null ? null : family.getName();
        return name == null ? "" : name;
    }

    /**
     * La pieza principal solo se materializa aquí. El producto elegido se
     * reutiliza tal cual mientras las características efectivas coincidan con las
     * suyas; en cuanto difieren —porque la entrada aportó algo que él no tenía o
     * porque los colochos cambian— se resuelve otro producto equivalente. NUNCA
     * se modifica el elegido, que puede estar en uso por inventario e historial.
     *
     * Familia y modelo salen del producto que sirve de referencia, como hasta
     * ahora. La familia ya está comprobada como la misma de la entrada, así que
     * la pieza resultante no puede cambiar de familia por esta vía.
     */
    private TransformationItem generateOutputItem(
            InventoryItem inputItem,
            Product selectedProduct,
            Map<String, Object> attributes,
            String outputName) {
        Product inputProduct = inputItem.getInventory().getProduct();
        Product reference = selectedProduct != null ? selectedProduct : inputProduct;
        if (selectedProduct == null && attributes.equals(
                normalizedAttributes(reference))) {
            throw new ValidationException(
                    "La pieza resultante debe diferenciarse del producto de entrada");
        }
        Product targetProduct = attributes.equals(reference.getAttributes())
                ? reference
                : findOrCreateProduct(
                        reference.getFamily(), reference.getModel(), attributes, outputName);

        // La pieza resultante sale de la que se consumio, asi que arrastra su
        // costo. El cliente no lo envia: se lee del inventario de origen.
        InventoryItem item = findOrCreateItem(
                targetProduct, inputItem.getInventory().getCost());
        return TransformationItem.builder()
                .item(item)
                .role(TransformationItemRole.OUTPUT)
                .build();
    }

    private Map<String, Object> normalizedAttributes(Product product) {
        Map<String, Object> attributes = copyOf(product.getAttributes());
        normalizeCurlCount(attributes);
        return attributes;
    }

    /** Un producto puede no tener atributos; copiarlo directo reventaba con null. */
    private Map<String, Object> copyOf(Map<String, Object> attributes) {
        return attributes == null ? new HashMap<>() : new HashMap<>(attributes);
    }

    /**
     * Una familia maneja colochos si los declara como atributo, no por ser
     * SPECIAL: hay piezas especiales (material liso, por ejemplo) que se
     * transforman sin que el concepto exista. Así, habilitar la capacidad en otra
     * familia solo requiere declararle el atributo, sin tocar código.
     */
    private boolean supportsCurlCount(Family family) {
        return curlCountAttribute(family).isPresent();
    }

    private Optional<Attribute> curlCountAttribute(Family family) {
        return declaredAttribute(family, CURL_COUNT_ATTRIBUTE);
    }

    /**
     * El atributo que la familia declara con ese nombre, ignorando mayúsculas.
     * Única puerta de entrada para decidir si una característica existe: cada
     * familia elige cómo escribirla y el JSONB sí distingue el caso.
     */
    private Optional<Attribute> declaredAttribute(Family family, String name) {
        List<Attribute> declared = family == null ? null : family.getAttributes();
        if (declared == null || name == null) {
            return Optional.empty();
        }
        return declared.stream()
                .filter(candidate -> candidate.getName() != null
                        && candidate.getName().equalsIgnoreCase(name))
                .findFirst();
    }

    /**
     * Cuando la familia maneja colochos, la cantidad de la pieza principal se
     * exige siempre: `0` significa sin colochos y omitirla dejaría una tercera
     * lectura implícita ("conservar lo que trajera la entrada") que el operador
     * nunca decidió. Cuando no los maneja, configurarlos no tiene sentido.
     */
    private void validateCurlCountSupport(
            boolean supportsCurlCount, CreateTransformationRequestDTO requestDTO) {
        if (supportsCurlCount) {
            if (requestDTO.outputCurlCount() == null) {
                throw new ValidationException(
                        "Debes indicar la cantidad de colochos de la pieza resultante");
            }
            return;
        }
        if (requestDTO.outputCurlCount() != null
                || requestDTO.remainderCurlCount() != null) {
            throw new ValidationException(
                    "La familia del producto no admite cantidad de colochos");
        }
    }

    /** Nombre real con el que la familia declara el atributo. */
    private String resolveCurlCountKey(Family family) {
        Attribute attribute = curlCountAttribute(family)
                .orElseThrow(() -> new ValidationException(
                        "La familia del producto no admite cantidad de colochos"));
        if (attribute.getDataType() != AttributeDataType.NUMBER) {
            throw new ValidationException(
                    "El atributo " + CURL_COUNT_ATTRIBUTE + " debe ser numerico");
        }
        return attribute.getName();
    }

    /**
     * La cantidad de colochos es un entero no negativo. Los booleanos y los
     * textos numéricos se rechazan: para la familia el atributo es NUMBER, y
     * aceptar "2" guardaría un tipo distinto en el JSONB.
     */
    private Integer toCurlCount(Object value) {
        if (!(value instanceof Number number)) {
            throw new ValidationException(
                    "La cantidad de colochos debe ser un numero entero mayor o igual a cero");
        }
        BigDecimal decimal;
        try {
            decimal = new BigDecimal(number.toString());
        } catch (NumberFormatException exception) {
            throw new ValidationException(
                    "La cantidad de colochos debe ser un numero entero mayor o igual a cero");
        }
        // stripTrailingZeros deja 2.0 y 2.00 con escala <= 0; 1.5 conserva escala.
        if (decimal.signum() < 0 || decimal.stripTrailingZeros().scale() > 0) {
            throw new ValidationException(
                    "La cantidad de colochos debe ser un numero entero mayor o igual a cero");
        }
        return decimal.intValueExact();
    }

    /**
     * Deja el conteo de la entrada en su forma entera antes de comparar y de
     * persistir. Un valor histórico que no encaje se respeta tal cual: esta tarea
     * no reescribe productos ya existentes.
     */
    private void normalizeCurlCount(Map<String, Object> attributes) {
        String key = attributes.keySet()
                .stream()
                .filter(candidate -> candidate != null
                        && candidate.equalsIgnoreCase(CURL_COUNT_ATTRIBUTE))
                .findFirst()
                .orElse(null);
        if (key == null) {
            return;
        }
        try {
            attributes.put(key, toCurlCount(attributes.get(key)));
        } catch (ValidationException exception) {
            // Valor heredado fuera de contrato: no se toca ni bloquea la pieza.
        }
    }

    /**
     * Decide si hay sobrante y de qué largo, sin crear nada. Las reglas son las
     * mismas de siempre —pieza con orificio no deja sobrante, y el recorte debe
     * llegar al menos a la mitad—; solo se separan de la construcción para poder
     * validar el contrato de colochos antes de materializar productos.
     */
    private Optional<Double> resolveRemainderLength(
            Product inputProduct, Map<String, Object> outputAttributes) {
        Map<String, Object> inputAttributes = inputProduct.getAttributes();
        boolean hasHole = Boolean.TRUE.equals(
                getBooleanAttribute(inputAttributes, HOLE_ATTRIBUTE));
        if (hasHole) {
            return Optional.empty();
        }
        Double inputLength = getNumericAttribute(
                inputAttributes, LENGTH_ATTRIBUTE);
        Double outputLength = getNumericAttribute(
                outputAttributes, LENGTH_ATTRIBUTE);
        if (inputLength == null || outputLength == null) {
            return Optional.empty();
        }
        double difference = (inputLength - outputLength);
        if (difference < inputLength / 2) {
            return Optional.empty();
        }
        return Optional.of(difference);
    }

    /**
     * El sobrante hereda las características de la entrada salvo el largo y los
     * colochos, que son propios: el operador decide cuántos lleva esta pieza con
     * independencia de la principal.
     */
    private TransformationItem generateRemainder(
            Product inputProduct, double length, Integer remainderCurlCount) {
        Map<String, Object> attributes = copyOf(inputProduct.getAttributes());
        String lengthKey = findAttributeKeyIgnoreCase(
                attributes, LENGTH_ATTRIBUTE);
        attributes.put(lengthKey, length);
        // Solo las familias que manejan colochos llegan aquí con un valor; el
        // resto conserva el sobrante tal y como se construía antes.
        if (remainderCurlCount != null) {
            attributes.put(
                    resolveCurlCountKey(inputProduct.getFamily()),
                    remainderCurlCount);
        }
        Product productRemainder = findOrCreateProduct(
                inputProduct.getFamily(),
                inputProduct.getModel(),
                attributes,
                null);
        // El sobrante conserva su regla contable actual: no hereda el costo de
        // la entrada, que ya se atribuyo por completo a la pieza principal.
        InventoryItem item = findOrCreateItem(productRemainder, BigDecimal.ZERO);
        return TransformationItem.builder()
                .role(TransformationItemRole.REMAINDER)
                .item(item)
                .build();
    }

    /**
     * Busca el atributo primero por coincidencia exacta y, si no existe, por
     * nombre equivalente ignorando mayúsculas y minúsculas. No modifica ni
     * renombra las claves del producto.
     */
    private Object getAttributeIgnoreCase(
            Map<String, Object> attributes, String attributeName) {
        if (attributes == null || attributeName == null) {
            return null;
        }
        if (attributes.containsKey(attributeName)) {
            return attributes.get(attributeName);
        }
        return attributes.entrySet()
                .stream()
                .filter(entry -> entry.getKey() != null
                        && entry.getKey().equalsIgnoreCase(attributeName))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    /**
     * Devuelve la clave real con la que el producto guarda el atributo, para que
     * el sobrante conserve el nombre original y no se dupliquen claves como
     * "largo" y "LARGO" en el JSONB.
     */
    private String findAttributeKeyIgnoreCase(
            Map<String, Object> attributes, String attributeName) {
        if (attributes == null || attributeName == null) {
            return attributeName;
        }
        if (attributes.containsKey(attributeName)) {
            return attributeName;
        }
        return attributes.keySet()
                .stream()
                .filter(key -> key != null
                        && key.equalsIgnoreCase(attributeName))
                .findFirst()
                .orElse(attributeName);
    }

    /**
     * Los valores del JSONB llegan al Map como Integer, Long, Double o
     * BigDecimal según el literal almacenado ({@code "largo": 500} produce un
     * Integer), por lo que un cast directo a Double lanzaba ClassCastException.
     * Los textos numéricos no se convierten: para la familia el atributo sería
     * de tipo STRING y no una longitud válida.
     */
    private Double getNumericAttribute(
            Map<String, Object> attributes, String attributeName) {
        Object value = getAttributeIgnoreCase(attributes, attributeName);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return null;
    }

    private Boolean getBooleanAttribute(
            Map<String, Object> attributes, String attributeName) {
        Object value = getAttributeIgnoreCase(attributes, attributeName);
        return value instanceof Boolean booleanValue ? booleanValue : null;
    }

    /**
     * Materializa la pieza resultante: reutiliza una equivalente si la hay y, si
     * no, crea el Product NUEVO ya con su nombre definitivo. Nunca renombra a
     * nadie: el nombre de una pieza pertenece a los inventarios e historiales
     * que ya la usan.
     */
    private Product findOrCreateProduct(
            Family family, Model model, Map<String, Object> attributes,
            String outputName) {
        UUID modelId = model != null ? model.getId() : null;
        String attrs = objectMapper.writeValueAsString(attributes);
        return findEquivalentProduct(family, modelId, attrs, outputName)
                .orElseGet(() -> productRepository.save(
                        Product.builder()
                                .name(resolveOutputName(outputName, family, attributes))
                                .sku(buildGeneratedSku())
                                .family(family)
                                .model(model)
                                .attributes(attributes)
                                .build()));
    }

    /**
     * Qué pieza ya existente sirve como resultado.
     *
     * Sin nombre pedido, cualquiera con las mismas características: es el
     * comportamiento de siempre y el nombre lo pone el sistema.
     *
     * Con nombre pedido, solo una que YA se llame así. Buscar por características
     * a secas hacía que el nombre escrito se perdiera en silencio en cuanto una
     * transformación anterior hubiera creado la misma pieza con el nombre
     * automático: se reutilizaba aquella y el operador veía su resultado llamarse
     * "<familia> <largo>" pese a haber escrito otra cosa. Reutilizar solo la que
     * coincide también en el nombre respeta su decisión sin tocar la ajena.
     */
    private Optional<Product> findEquivalentProduct(
            Family family, UUID modelId, String attributes, String outputName) {
        UUID familyId = family.getId();
        return outputName == null
                ? productRepository.findTargetProduct(familyId, modelId, attributes)
                : productRepository.findTargetProductNamed(
                        familyId, modelId, attributes, outputName);
    }

    /**
     * Nombre con el que nace la pieza: el que pidio el operador o, si no pidio
     * ninguno, el que arma el sistema. Se resuelve al construir el Product, no
     * despues: una pieza nunca se crea con un nombre para renombrarla luego.
     */
    private String resolveOutputName(
            String requestedName, Family family, Map<String, Object> attributes) {
        return requestedName != null
                ? requestedName
                : buildGeneratedName(family, attributes);
    }

    /**
     * Nombre legible para las piezas que el sistema crea solo (sobrantes y
     * transformaciones personalizadas). Antes todas se llamaban igual
     * ("PRODUCTO RESULTANTE"), lo que las hacía indistinguibles en el catálogo.
     */
    private String buildGeneratedName(Family family, Map<String, Object> attributes) {
        Double length = getNumericAttribute(attributes, LENGTH_ATTRIBUTE);
        String base = family.getName() == null ? "PRODUCTO RESULTANTE" : family.getName();

        if (length == null) {
            return base;
        }

        String formattedLength = length == Math.rint(length)
                ? String.valueOf((long) (double) length)
                : String.valueOf((double) length);

        return base + " " + formattedLength;
    }

    /**
     * `sku` es UNIQUE y hasta ahora estas piezas se guardaban con SKU nulo, lo
     * que además rompía cualquier búsqueda que lo leyera. Se genera uno corto y
     * único; la reutilización de productos equivalentes NO depende del SKU sino
     * de familia + modelo + atributos (findTargetProduct).
     */
    private String buildGeneratedSku() {
        return "AUTO-" + UUID.randomUUID()
                .toString()
                .replace("-", "")
                .substring(0, 12)
                .toUpperCase();
    }

    /**
     * `cost` solo se usa al crear el inventario. Si el taller ya tiene uno para
     * ese producto se respeta el costo que ya tuviera: la transformacion agrega
     * una unidad, no reescribe la contabilidad de un inventario existente.
     */
    private InventoryItem findOrCreateItem(Product targetProduct, BigDecimal cost) {
        Inventory inventory = inventoryRepository
                .findByProductIdAndWorkshopId(
                        targetProduct.getId(),
                        SecurityUtils.getWorkshopId())
                .orElseGet(() -> createInventory(targetProduct, cost));
        InventoryItem item = InventoryItem.builder()
                .status(InventoryItemStatus.AVAILABLE)
                .build();
        inventory.addItem(item);
        inventory.addStock(1);
        // La pieza resultante entra como PRODUCTION: se fabrico a partir de
        // material propio, no se le compro a nadie. No se le traslada el costo
        // del material de entrada porque este sistema no modela costeo de
        // manufactura, y repartirlo a ojo daria una cifra indefendible. Lo que
        // se venda de aqui queda marcado como linea sin costo historico, que es
        // la verdad, en lugar de como costo cero, que seria ganancia inventada.
        lotService.registerProduction(inventory, 1);
        // Hibernate asigna el id al persistir. Sin este save la unidad seguía
        // siendo transitoria cuando se guardaba la Transformation, y el insert de
        // transformation_item viajaba con item_id nulo (violando el NOT NULL).
        return itemRepository.save(item);
    }

    private Inventory createInventory(Product targetProduct, BigDecimal cost) {
        Workshop workshop = workshopRepository  
                .findById(SecurityUtils.getWorkshopId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el taller especificado"));
        Inventory newInventory = Inventory.builder()
                .product(targetProduct)
                .workshop(workshop)
                .price(BigDecimal.ZERO)
                .cost(cost == null ? BigDecimal.ZERO : cost)
                .minStock(0)
                .build();
        return inventoryRepository.save(newInventory);
    }

}
