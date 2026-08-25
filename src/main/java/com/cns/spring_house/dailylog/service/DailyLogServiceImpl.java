package com.cns.spring_house.dailylog.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cns.spring_house.dailylog.dto.CreateDailyLogRequestDTO;
import com.cns.spring_house.dailylog.dto.DailyLogProductRequestDTO;
import com.cns.spring_house.dailylog.dto.DailyLogResponseDTO;
import com.cns.spring_house.dailylog.dto.DailyLogServiceRequestDTO;
import com.cns.spring_house.dailylog.dto.SearchDailyLogRequestDTO;
import com.cns.spring_house.dailylog.dto.UpdateDailyLogRequestDTO;
import com.cns.spring_house.dailylog.entity.DailyLog;
import com.cns.spring_house.dailylog.entity.DailyLogProduct;
import com.cns.spring_house.dailylog.mapper.DailyLogMapper;
import com.cns.spring_house.dailylog.repository.DailyLogRepository;
import com.cns.spring_house.dailylog.repository.DailyLogSpecs;
import com.cns.spring_house.employee.entity.Employee;
import com.cns.spring_house.job.entity.Job;
import com.cns.spring_house.job.repository.JobRepository;
import com.cns.spring_house.product.entity.Product;
import com.cns.spring_house.product.repository.ProductRepository;
import com.cns.spring_house.sale.entity.Sale;
import com.cns.spring_house.sale.entity.SaleStatus;
import com.cns.spring_house.sale.repository.SaleRepository;
import com.cns.spring_house.shared.exception.EntityAlreadyExistsException;
import com.cns.spring_house.shared.exception.EntityNotFoundException;
import com.cns.spring_house.shared.exception.ValidationException;
import com.cns.spring_house.shared.security.CurrentUserService;
import com.cns.spring_house.shared.security.SecurityUtils;

import lombok.RequiredArgsConstructor;

/**
 * Levanta el registro historico de una factura ya emitida.
 *
 * El nucleo de esta clase es una asimetria deliberada: consulta el catalogo
 * para VALIDAR y guarda TEXTO. {@code ProductRepository} y {@code JobRepository}
 * solo responden a "existe algo que se llame asi"; la entidad encontrada no se
 * asigna nunca al registro y su id no se guarda por ninguna via. Ver
 * {@code DailyLogProduct} y {@code DailyLogService}.
 *
 * El nombre de la clase de entidad de una linea de servicio coincide con el de
 * esta interfaz de servicio ({@code DailyLogService} en dos paquetes distintos),
 * asi que el {@code implements} va cualificado: importar la entidad ocultaria el
 * tipo del mismo paquete.
 */
@Transactional
@Service
@RequiredArgsConstructor
public class DailyLogServiceImpl implements com.cns.spring_house.dailylog.service.DailyLogService {

    private final DailyLogRepository repository;
    private final DailyLogMapper mapper;
    private final CurrentUserService currentUserService;
    private final SaleRepository saleRepository;
    private final ProductRepository productRepository;
    private final JobRepository jobRepository;

    @Override
    public List<DailyLogResponseDTO> getAll(SearchDailyLogRequestDTO requestDTO) {
        Specification<DailyLog> specs = Specification.allOf(
                DailyLogSpecs.hasWorkshop(SecurityUtils.getWorkshopId()),
                DailyLogSpecs.hasEmployee(requestDTO.employee()),
                DailyLogSpecs.hasSale(requestDTO.sale()),
                DailyLogSpecs.performedDateBetween(requestDTO.minDate(), requestDTO.maxDate()));
        return repository.findAll(specs)
                .stream()
                .map(mapper::toResponse)
                .toList();
    }

    @Override
    public DailyLogResponseDTO get(UUID id) {
        DailyLog dailyLog = repository
                .findByIdAndWorkshopId(id, SecurityUtils.getWorkshopId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el log solicitado"));
        return mapper.toResponse(dailyLog);
    }

    /**
     * Todo ocurre dentro de la transaccion de este metodo: si la linea numero
     * ocho nombra un servicio inexistente, la excepcion revienta el POST entero
     * y no queda ni el registro ni las siete lineas anteriores.
     */
    @Override
    public DailyLogResponseDTO create(CreateDailyLogRequestDTO requestDTO) {
        List<DailyLogProductRequestDTO> products = requestDTO.products() == null
                ? List.of()
                : requestDTO.products();
        List<DailyLogServiceRequestDTO> services = requestDTO.services() == null
                ? List.of()
                : requestDTO.services();

        if (products.isEmpty() && services.isEmpty()) {
            throw new ValidationException(
                    "El registro debe incluir al menos un producto o un servicio");
        }

        // SecurityUtils.getId() es UserAccount.id, no Employee.id.
        Employee employee = currentUserService.getCurrentEmployee();
        Sale sale = requireCompletedSale(requestDTO.sale());

        DailyLog dailyLog = DailyLog.builder()
                .sale(sale)
                .employee(employee)
                .workshop(employee.getWorkshop())
                .description(trimToNull(requestDTO.description()))
                .build();

        for (DailyLogProductRequestDTO line : products) {
            validateAmounts(line.unitPrice(), line.quantity(), line.subtotal());
            dailyLog.addProduct(DailyLogProduct.builder()
                    .name(canonicalProductName(line.name()))
                    .unitPrice(line.unitPrice())
                    .quantity(line.quantity())
                    .subtotal(line.subtotal())
                    .build());
        }

        for (DailyLogServiceRequestDTO line : services) {
            validateAmounts(line.unitPrice(), line.quantity(), line.subtotal());
            dailyLog.addService(com.cns.spring_house.dailylog.entity.DailyLogService.builder()
                    .name(canonicalServiceName(line.name()))
                    .unitPrice(line.unitPrice())
                    .quantity(line.quantity())
                    .subtotal(line.subtotal())
                    .build());
        }

        dailyLog = repository.save(dailyLog);
        return mapper.toResponse(dailyLog);
    }

    @Override
    public DailyLogResponseDTO update(UUID id, UpdateDailyLogRequestDTO requestDTO) {
        DailyLog dailyLog = repository
                .findByIdAndWorkshopId(id, SecurityUtils.getWorkshopId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el log solicitado"));
        if (requestDTO.description() != null) {
            dailyLog.setDescription(requestDTO.description().trim());
        }
        dailyLog = repository.save(dailyLog);
        return mapper.toResponse(dailyLog);
    }

    // ── Venta de referencia ──────────────────────────────────────

    /**
     * La venta debe existir, ser del taller autenticado y estar COMPLETED.
     *
     * COMPLETED es el estado que significa "el documento se emitio", y es lo
     * unico que se exige del cobro: una factura cambiaria COMPLETED con
     * PaymentStatus.PENDING es perfectamente registrable, porque la factura ya
     * salio aunque el cliente todavia no haya pagado. PENDING (carrito abierto)
     * y CANCELLED/REVERSED (nunca se emitio, o se deshizo) no describen ninguna
     * venta que la secretaria pueda estar copiando de una factura en la mano.
     */
    private Sale requireCompletedSale(UUID saleId) {
        Sale sale = saleRepository
                .findByIdAndWorkshopId(saleId, SecurityUtils.getWorkshopId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe la venta especificada"));

        if (sale.getStatus() != SaleStatus.COMPLETED) {
            throw new ValidationException(
                    "La venta debe estar completada para registrarla en la bitacora");
        }

        if (repository.existsBySaleId(sale.getId())) {
            throw new EntityAlreadyExistsException(
                    "Esta venta ya tiene un registro en la bitacora");
        }

        return sale;
    }

    // ── Nombres: se validan contra el catalogo, se guardan como texto ──

    /**
     * Constata que existe un producto con ese nombre y devuelve SU nombre, no el
     * que llego en la peticion: asi el historico queda con la grafia del
     * catalogo ("alineacion" -> "ALINEACION") en vez de con lo que cada quien
     * escriba.
     *
     * Devuelve el nombre y no el {@code Product} a proposito: quien llama no
     * puede quedarse con la entidad ni con su id aunque quiera.
     */
    private String canonicalProductName(String name) {
        Product product = productRepository
                .findByNameIgnoringCaseAndSpaces(name.trim())
                .orElseThrow(() -> new ValidationException(
                        "No existe un producto con el nombre indicado: " + name.trim()));
        return product.getName();
    }

    /**
     * Lo mismo para servicios, contra {@code JobRepository} y acotado al taller
     * autenticado. El nombre de un servicio es el de su producto asociado.
     */
    private String canonicalServiceName(String name) {
        Job job = jobRepository
                .findByNameIgnoringCaseAndSpaces(
                        SecurityUtils.getWorkshopId(), name.trim())
                .orElseThrow(() -> new ValidationException(
                        "No existe un servicio con el nombre indicado: " + name.trim()));
        return job.getProduct().getName();
    }

    // ── Importes ─────────────────────────────────────────────────

    /**
     * El subtotal se recalcula y se compara con el enviado. Sin esta
     * comprobacion, una peticion con precio 100, cantidad 2 y subtotal 900
     * entraba tal cual y el historico quedaba con una cuenta que no cuadra.
     *
     * La comparacion es con {@code compareTo} y no con {@code equals}: 900 y
     * 900.00 son el mismo importe pero distinto {@code BigDecimal}, y rechazar
     * el segundo seria rechazar al cliente por como formatea el JSON.
     *
     * Lo que NO se valida es el precio contra el catalogo: el registro guarda el
     * precio al que se declara la venta, y el catalogo pudo cambiar despues.
     */
    private void validateAmounts(BigDecimal unitPrice, Integer quantity, BigDecimal subtotal) {
        BigDecimal expected = unitPrice.multiply(BigDecimal.valueOf(quantity));

        if (subtotal.compareTo(expected) != 0) {
            throw new ValidationException(
                    "El subtotal no coincide con el precio unitario por la cantidad");
        }
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

}
