package com.cns.spring_house.supplier.service;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.cns.spring_house.shared.exception.EntityAlreadyExistsException;
import com.cns.spring_house.shared.exception.ValidationException;
import com.cns.spring_house.shared.security.CurrentUserService;
import com.cns.spring_house.shared.security.SecurityUtils;
import com.cns.spring_house.shared.util.LikePatterns;
import com.cns.spring_house.supplier.dto.CreateSupplierRequestDTO;
import com.cns.spring_house.supplier.dto.SupplierResponseDTO;
import com.cns.spring_house.supplier.entity.Supplier;
import com.cns.spring_house.supplier.repository.SupplierRepository;
import com.cns.spring_house.workshop.entity.Workshop;

import lombok.RequiredArgsConstructor;

/**
 * Busqueda y alta de proveedores del taller autenticado.
 *
 * Dos reglas gobiernan la clase:
 *
 * 1. El taller sale SIEMPRE del token. Ningun metodo acepta un identificador de
 *    taller, de modo que no hay forma de leer ni de crear en otro.
 *
 * 2. Un nombre de proveedor es el MISMO nombre aunque cambien las mayusculas o
 *    los espacios. Esa equivalencia se decide en un unico sitio
 *    ({@link #canonical}) y la base la vuelve a imponer por su cuenta con el
 *    indice `uq_supplier_canonical_name_per_workshop`, que usa exactamente la
 *    misma expresion.
 */
@Service
@RequiredArgsConstructor
public class SupplierServiceImpl implements SupplierService {

    /**
     * Sugerencias por defecto del autocompletado. Una lista con la que se elige
     * de un vistazo no mejora por ser mas larga: por encima de esto deja de ser
     * un buscador y vuelve a ser un listado. Mismo criterio y mismos numeros que
     * el buscador de facturas de {@code SaleServiceImpl}.
     */
    private static final int DEFAULT_SUGGESTIONS = 8;

    private static final int MAX_SUGGESTIONS = 10;

    /**
     * Nombre del indice unico. Se compara contra el mensaje de la violacion para
     * traducir la carrera en un error de negocio en vez de un 500; renombrar el
     * indice en una migracion futura sin tocar esto devolveria ese 500 sin que
     * nada avisara.
     */
    private static final String CANONICAL_NAME_INDEX =
            "uq_supplier_canonical_name_per_workshop";

    private final SupplierRepository repository;
    private final CurrentUserService currentUserService;

    /**
     * El alta se ejecuta en su PROPIA transaccion y no en la del metodo.
     *
     * No es una preferencia de estilo: cuando el INSERT viola el indice unico,
     * PostgreSQL ABORTA la transaccion entera. Cualquier consulta posterior
     * dentro de ella falla con "current transaction is aborted", de modo que
     * recuperarse leyendo el proveedor que gano la carrera es imposible ahi
     * dentro —medido: el intento devolvia el error original al confirmar—.
     *
     * Acotando el INSERT a su propia transaccion, esa se revierte sola y la
     * relectura ocurre en una limpia, que es lo que permite responder con el
     * proveedor correcto en vez de con un 500.
     */
    private final TransactionTemplate transactionTemplate;

    /**
     * Autocompletado del buscador de proveedores.
     *
     * Acotado en el propio SQL —por taller y por `LIMIT`—, nunca descargando el
     * listado entero para filtrarlo despues: el numero de proveedores de un
     * taller crece sin tope y el cliente solo pinta ocho.
     *
     * Una consulta vacia devuelve los primeros por orden alfabetico en lugar de
     * una lista vacia: es lo que permite abrir el desplegable y ver con quien se
     * ha trabajado sin tener que adivinar la primera letra.
     */
    @Override
    @Transactional(readOnly = true)
    public List<SupplierResponseDTO> search(String query, Integer limit) {
        int size = limit == null ? DEFAULT_SUGGESTIONS : limit;

        if (size < 1 || size > MAX_SUGGESTIONS) {
            throw new ValidationException(
                    "El numero de sugerencias debe estar entre 1 y " + MAX_SUGGESTIONS);
        }

        // El comodin es parte del VALOR, no de la consulta, y LikePatterns
        // neutraliza los que escriba la persona: buscar "%" no devuelve el
        // listado entero ni "_" casa con cualquier caracter.
        String pattern = query == null || query.isBlank()
                ? null
                : LikePatterns.containsIgnoreCase(query);

        return repository.search(
                SecurityUtils.getWorkshopId(),
                pattern,
                PageRequest.of(0, size));
    }

    /**
     * Alta explicita de un proveedor que no existia.
     *
     * La proteccion contra duplicados es DOBLE y las dos capas hacen falta:
     *
     * - La consulta previa da un mensaje entendible ("ya existe un proveedor con
     *   ese nombre") en el caso normal, que es alguien que no vio la sugerencia.
     * - El indice unico es lo unico que sostiene el caso concurrente: dos altas
     *   simultaneas del mismo nombre pasan LAS DOS por el `exists`, y sin el
     *   indice quedarian dos proveedores que son el mismo para siempre.
     *
     * Cuando gana la otra transaccion no se responde un error a secas: se vuelve
     * a buscar por forma canonica y se devuelve EL PROVEEDOR QUE GANO, con su
     * id real. Asi el cliente que perdio la carrera acaba con el proveedor
     * correcto seleccionado en vez de con un mensaje y un formulario a medias.
     */
    @Override
    public SupplierResponseDTO create(CreateSupplierRequestDTO requestDTO) {
        Workshop workshop = currentUserService.getCurrentWorkshop();
        UUID workshopId = workshop.getId();

        String displayName = normalize(requestDTO.name());

        if (displayName.isEmpty()) {
            throw new ValidationException(
                    "El nombre del proveedor es obligatorio");
        }

        String canonicalName = canonical(displayName);

        repository.findByCanonicalName(workshopId, canonicalName)
                .ifPresent(existing -> {
                    throw new EntityAlreadyExistsException(
                            "Ya existe un proveedor con ese nombre en este taller");
                });

        Supplier supplier = Supplier.builder()
                .workshop(workshop)
                .name(displayName)
                .build();

        Supplier candidate = supplier;

        try {
            // `saveAndFlush` y no `save`: hace falta que el INSERT llegue a
            // PostgreSQL DENTRO de esta transaccion acotada. Con el flush
            // diferido al commit, la violacion del indice explotaria fuera del
            // bloque y el cliente recibiria un 500.
            Supplier saved = transactionTemplate.execute(
                    status -> repository.saveAndFlush(candidate));
            return toResponse(saved);
        } catch (DataIntegrityViolationException exception) {
            if (!isCanonicalNameViolation(exception)) {
                throw exception;
            }
            // La transaccion del INSERT ya se revirtio: esta lectura corre en una
            // limpia y SI puede ver el proveedor que confirmo la otra peticion.
            return repository.findByCanonicalName(workshopId, canonicalName)
                    .map(SupplierServiceImpl::toResponse)
                    .orElseThrow(() -> new EntityAlreadyExistsException(
                            "Ya existe un proveedor con ese nombre en este taller"));
        }
    }

    /**
     * Lo que se GUARDA y se muestra: sin espacios sobrantes en los extremos y
     * sin espacios repetidos dentro. Conserva las mayusculas que escribio la
     * persona, porque el nombre comercial es suyo; lo unico que se normaliza es
     * el espaciado, que nunca es intencionado.
     */
    private static String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    /**
     * La forma con la que se decide si dos nombres son EL MISMO proveedor.
     *
     * Reproduce literalmente la expresion del indice
     * `uq_supplier_canonical_name_per_workshop`:
     * {@code UPPER(REGEXP_REPLACE(BTRIM(name), '\s+', ' ', 'g'))}. Las dos
     * tienen que coincidir: si el servicio considerara distinto lo que la base
     * considera igual, el alta llegaria hasta la violacion del indice y
     * responderia un 500 en un caso perfectamente normal.
     *
     * {@code Locale.ROOT} y no el del sistema: con la configuracion regional
     * turca, {@code "i".toUpperCase()} produce una I sin punto y el mismo nombre
     * dejaria de casar consigo mismo segun donde corriera el servidor.
     */
    private static String canonical(String value) {
        return normalize(value).toUpperCase(Locale.ROOT);
    }

    /**
     * Si la violacion es la del nombre duplicado y no otra cualquiera. Se
     * comprueba el NOMBRE del indice y no el texto del mensaje entero: cualquier
     * otra restriccion tiene que seguir subiendo como el fallo que es.
     */
    private static boolean isCanonicalNameViolation(
            DataIntegrityViolationException exception) {
        Throwable cause = exception;
        while (cause != null) {
            String message = cause.getMessage();
            if (message != null && message.contains(CANONICAL_NAME_INDEX)) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private static SupplierResponseDTO toResponse(Supplier supplier) {
        return new SupplierResponseDTO(supplier.getId(), supplier.getName());
    }

}
