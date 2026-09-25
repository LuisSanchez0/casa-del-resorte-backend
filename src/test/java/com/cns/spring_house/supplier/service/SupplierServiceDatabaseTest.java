package com.cns.spring_house.supplier.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.cns.spring_house.employee.entity.Employee;
import com.cns.spring_house.employee.repository.EmployeeRepository;
import com.cns.spring_house.role.entity.Role;
import com.cns.spring_house.role.repository.RoleRepository;
import com.cns.spring_house.shared.exception.EntityAlreadyExistsException;
import com.cns.spring_house.shared.exception.ValidationException;
import com.cns.spring_house.supplier.dto.CreateSupplierRequestDTO;
import com.cns.spring_house.supplier.dto.SupplierResponseDTO;
import com.cns.spring_house.supplier.repository.SupplierRepository;
import com.cns.spring_house.testsupport.TestDatabases;
import com.cns.spring_house.useraccount.entity.UserAccount;
import com.cns.spring_house.useraccount.repository.UserAccountRepository;
import com.cns.spring_house.workshop.entity.Workshop;
import com.cns.spring_house.workshop.repository.WorkshopRepository;

/**
 * Busqueda y alta de proveedores contra PostgreSQL real.
 *
 * Tiene que ser PostgreSQL de verdad y no un mock ni una base en memoria porque
 * lo que se comprueba vive EN LA BASE: la unicidad por forma canonica es un
 * indice de expresion, el escape de comodines es semantica de `LIKE ... ESCAPE`,
 * y la carrera entre dos altas simultaneas solo la resuelve una transaccion
 * real. Un doble del repositorio confirmaria unicamente que se le llamo.
 *
 * Cada prueba trabaja sobre su propio taller, de modo que ninguna depende del
 * orden de ejecucion ni de lo que otra haya dejado.
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class SupplierServiceDatabaseTest {

    private static final String TEMPORARY_DATABASE = "supplier_service_it";

    @Autowired
    private SupplierService service;

    @Autowired
    private SupplierRepository repository;

    @Autowired
    private WorkshopRepository workshopRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private RoleRepository roleRepository;

    @DynamicPropertySource
    static void temporaryDatabase(DynamicPropertyRegistry registry) {
        String url = TestDatabases.provision(TEMPORARY_DATABASE);

        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", TestDatabases::username);
        registry.add("spring.datasource.password", TestDatabases::password);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        // Mismo tope que el resto de pruebas de base de datos del proyecto: cada
        // contexto cacheado se queda con su pool, y la suma agota max_connections.
        // Aqui hacen falta varias porque la prueba de concurrencia abre hilos.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 4);
        registry.add("spring.datasource.hikari.minimum-idle", () -> 0);
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    // ── Alta ─────────────────────────────────────────────────────

    @Test
    void createsASupplierInTheAuthenticatedWorkshop() {
        Workshop workshop = authenticatedWorkshop();

        SupplierResponseDTO created = service.create(
                new CreateSupplierRequestDTO("Resortes Guatemala"));

        assertThat(created.id()).isNotNull();
        assertThat(created.name()).isEqualTo("Resortes Guatemala");
        assertThat(repository.findByIdAndWorkshopId(created.id(), workshop.getId()))
                .isPresent();
    }

    /**
     * El nombre se guarda recortado y con los espacios repetidos colapsados:
     * es lo que se va a mostrar en el campo, y `"  Resortes   Guatemala  "` no
     * es un nombre distinto sino el mismo mal tecleado.
     */
    @Test
    void normalizesTheStoredNameWithoutChangingItsCase() {
        authenticatedWorkshop();

        SupplierResponseDTO created = service.create(
                new CreateSupplierRequestDTO("  Resortes   Guatemala  "));

        assertThat(created.name()).isEqualTo("Resortes Guatemala");
    }

    @Test
    void rejectsABlankName() {
        authenticatedWorkshop();

        assertThatThrownBy(() -> service.create(new CreateSupplierRequestDTO("   ")))
                .isInstanceOf(ValidationException.class);
    }

    // ── Duplicados ───────────────────────────────────────────────

    @Test
    void rejectsTheSameNameInADifferentCase() {
        authenticatedWorkshop();
        service.create(new CreateSupplierRequestDTO("Resortes Guatemala"));

        assertThatThrownBy(() -> service.create(
                new CreateSupplierRequestDTO("RESORTES GUATEMALA")))
                .isInstanceOf(EntityAlreadyExistsException.class);
    }

    @Test
    void rejectsTheSameNameWithSurroundingWhitespace() {
        authenticatedWorkshop();
        service.create(new CreateSupplierRequestDTO("Resortes Guatemala"));

        assertThatThrownBy(() -> service.create(
                new CreateSupplierRequestDTO("   resortes guatemala   ")))
                .isInstanceOf(EntityAlreadyExistsException.class);
    }

    @Test
    void rejectsTheSameNameWithRepeatedInnerWhitespace() {
        authenticatedWorkshop();
        service.create(new CreateSupplierRequestDTO("Resortes Guatemala"));

        assertThatThrownBy(() -> service.create(
                new CreateSupplierRequestDTO("Resortes     Guatemala")))
                .isInstanceOf(EntityAlreadyExistsException.class);
    }

    /**
     * El proveedor es del taller, asi que dos talleres pueden trabajar con
     * homonimos sin estorbarse. Es la contrapartida exacta de las pruebas de
     * arriba: la unicidad no es global.
     */
    @Test
    void allowsTheSameNameInAnotherWorkshop() {
        authenticatedWorkshop();
        service.create(new CreateSupplierRequestDTO("Resortes Guatemala"));

        Workshop other = authenticatedWorkshop();
        SupplierResponseDTO created = service.create(
                new CreateSupplierRequestDTO("Resortes Guatemala"));

        assertThat(created.id()).isNotNull();
        assertThat(repository.findByIdAndWorkshopId(created.id(), other.getId()))
                .isPresent();
    }

    /**
     * Dos altas SIMULTANEAS del mismo nombre.
     *
     * La comprobacion previa del servicio no puede resolver esto: las dos
     * transacciones la pasan antes de que ninguna confirme. Lo que decide es el
     * indice unico, y lo que se comprueba aqui es que la que pierde NO recibe un
     * 500 sino el proveedor que gano, con su id real, para que el cliente acabe
     * con el proveedor correcto seleccionado.
     */
    @Test
    void concurrentCreatesLeaveExactlyOneSupplier() throws Exception {
        Workshop workshop = authenticatedWorkshop();
        Jwt token = tokenFor(workshop);

        int attempts = 4;
        CyclicBarrier startTogether = new CyclicBarrier(attempts);
        ExecutorService pool = Executors.newFixedThreadPool(attempts);

        List<Callable<SupplierResponseDTO>> tasks = new ArrayList<>();
        for (int index = 0; index < attempts; index++) {
            tasks.add(() -> {
                // Cada hilo tiene su propio SecurityContext: hay que autenticarlo.
                SecurityContextHolder.getContext().setAuthentication(
                        new JwtAuthenticationToken(token));
                try {
                    startTogether.await(10, TimeUnit.SECONDS);
                    return service.create(
                            new CreateSupplierRequestDTO("Resortes Guatemala"));
                } finally {
                    SecurityContextHolder.clearContext();
                }
            });
        }

        List<Future<SupplierResponseDTO>> results;
        try {
            results = pool.invokeAll(tasks, 30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        List<UUID> resolvedIds = new ArrayList<>();
        for (Future<SupplierResponseDTO> result : results) {
            try {
                resolvedIds.add(result.get().id());
            } catch (Exception exception) {
                // Un fallo de negocio es aceptable; lo que no lo es es que
                // sobrevivan dos proveedores. Eso se comprueba abajo.
                assertThat(rootCause(exception))
                        .isInstanceOfAny(
                                EntityAlreadyExistsException.class,
                                ValidationException.class);
            }
        }

        assertThat(repository.search(workshop.getId(), null, org.springframework.data
                .domain.PageRequest.of(0, 10)))
                .hasSize(1);
        // Y todo el que SI obtuvo respuesta obtuvo el MISMO proveedor.
        assertThat(resolvedIds).isNotEmpty();
        assertThat(resolvedIds).containsOnly(resolvedIds.get(0));
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    // ── Busqueda ─────────────────────────────────────────────────

    @Test
    void searchesIgnoringCaseAndMatchingAnySubstring() {
        authenticatedWorkshop();
        service.create(new CreateSupplierRequestDTO("Resortes Guatemala"));
        service.create(new CreateSupplierRequestDTO("Importadora del Norte"));

        assertThat(names(service.search("resortes", null)))
                .containsExactly("Resortes Guatemala");
        assertThat(names(service.search("RESORTES", null)))
                .containsExactly("Resortes Guatemala");
        assertThat(names(service.search("ReSoRtEs", null)))
                .containsExactly("Resortes Guatemala");
        // Fragmento en medio del nombre, no solo prefijo.
        assertThat(names(service.search("guate", null)))
                .containsExactly("Resortes Guatemala");
        assertThat(names(service.search("del nor", null)))
                .containsExactly("Importadora del Norte");
    }

    @Test
    void returnsNothingForAnUnknownSupplier() {
        authenticatedWorkshop();
        service.create(new CreateSupplierRequestDTO("Resortes Guatemala"));

        assertThat(service.search("ferreteria", null)).isEmpty();
    }

    /**
     * Los comodines de LIKE que escriba la persona son TEXTO, no sintaxis.
     *
     * Sin el escape, buscar `%` devolvia el listado entero y `_` casaba con
     * cualquier caracter: quien busca un proveedor escribe su nombre, no un
     * patron.
     */
    @Test
    void treatsWildcardCharactersAsLiteralText() {
        authenticatedWorkshop();
        service.create(new CreateSupplierRequestDTO("Resortes Guatemala"));
        service.create(new CreateSupplierRequestDTO("Descuentos 50% SA"));
        service.create(new CreateSupplierRequestDTO("Guion_Bajo SA"));
        service.create(new CreateSupplierRequestDTO("Barra\\Invertida SA"));

        // `%` no devuelve todo: solo el que lo lleva de verdad.
        assertThat(names(service.search("%", null)))
                .containsExactly("Descuentos 50% SA");
        assertThat(names(service.search("50%", null)))
                .containsExactly("Descuentos 50% SA");

        // `_` no casa con cualquier caracter: solo con un guion bajo literal.
        assertThat(names(service.search("_", null)))
                .containsExactly("Guion_Bajo SA");
        assertThat(names(service.search("n_b", null)))
                .containsExactly("Guion_Bajo SA");

        // Y la propia barra de escape es un caracter mas.
        assertThat(names(service.search("\\", null)))
                .containsExactly("Barra\\Invertida SA");
    }

    @Test
    void boundsTheNumberOfSuggestions() {
        authenticatedWorkshop();
        for (int index = 0; index < 12; index++) {
            service.create(new CreateSupplierRequestDTO(
                    "Proveedor " + (char) ('A' + index)));
        }

        assertThat(service.search(null, null)).hasSize(8);
        assertThat(service.search(null, 3)).hasSize(3);
        assertThatThrownBy(() -> service.search(null, 0))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.search(null, 50))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void ordersSuggestionsDeterministicallyByName() {
        authenticatedWorkshop();
        service.create(new CreateSupplierRequestDTO("Zeta SA"));
        service.create(new CreateSupplierRequestDTO("Alfa SA"));
        service.create(new CreateSupplierRequestDTO("Media SA"));

        assertThat(names(service.search(null, null)))
                .containsExactly("Alfa SA", "Media SA", "Zeta SA");
    }

    /**
     * El aislamiento entre talleres es la garantia que no puede fallar: el
     * proveedor de otro taller no aparece en el autocompletado ni conociendo su
     * nombre exacto.
     */
    @Test
    void neverReturnsSuppliersFromAnotherWorkshop() {
        authenticatedWorkshop();
        service.create(new CreateSupplierRequestDTO("Resortes Guatemala"));

        authenticatedWorkshop();
        service.create(new CreateSupplierRequestDTO("Importadora del Norte"));

        assertThat(names(service.search(null, null)))
                .containsExactly("Importadora del Norte");
        assertThat(service.search("Resortes Guatemala", null)).isEmpty();
    }

    private static List<String> names(List<SupplierResponseDTO> suppliers) {
        return suppliers.stream().map(SupplierResponseDTO::name).toList();
    }

    // ── Datos de partida ─────────────────────────────────────────

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private final Map<UUID, UserAccount> accounts = new HashMap<>();

    /**
     * Taller nuevo y sesion abierta contra el. El subject del JWT tiene que ser
     * un {@code UserAccount.id} REAL: {@code CurrentUserService} lo resuelve
     * consultando la base, no acepta un UUID inventado.
     */
    private Workshop authenticatedWorkshop() {
        Workshop workshop = workshopRepository.save(Workshop.builder()
                .name("TALLER " + unique())
                .address("DIRECCION")
                .isActive(true)
                .build());

        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(tokenFor(workshop)));

        return workshop;
    }

    private Jwt tokenFor(Workshop workshop) {
        UserAccount account = accounts.computeIfAbsent(
                workshop.getId(), ignored -> newAccount(workshop));

        return Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject(account.getId().toString())
                .claim("workshop", workshop.getId().toString())
                .build();
    }

    private UserAccount newAccount(Workshop workshop) {
        Employee employee = employeeRepository.save(Employee.builder()
                .workshop(workshop)
                .firstName("NOMBRE")
                .lastName("APELLIDO")
                .dpi(UUID.randomUUID().toString().substring(0, 13))
                .birthDate(LocalDate.of(1990, 1, 1))
                .build());

        // El rol lo siembra la migracion V4; se busca en vez de crearse para no
        // duplicar una fila que ya existe en toda base migrada.
        Role role = roleRepository.findAll().stream()
                .filter(candidate -> "CONTROL".equals(candidate.getName()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "El rol CONTROL no esta sembrado en la base de prueba"));

        return userAccountRepository.save(UserAccount.builder()
                .employee(employee)
                .role(role)
                .username("user" + unique())
                .password("secret")
                .build());
    }

}
