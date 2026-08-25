package com.cns.spring_house.dashboard.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.cns.spring_house.dashboard.dto.AdminDashboardOverviewResponseDTO;
import com.cns.spring_house.dashboard.dto.SalesGranularity;
import com.cns.spring_house.dashboard.dto.SalesTrendResponseDTO;
import com.cns.spring_house.dashboard.dto.VehicleOccupancyResponseDTO;
import com.cns.spring_house.dashboard.service.AdminDashboardService;

import lombok.RequiredArgsConstructor;

/**
 * Panel analitico del administrador.
 *
 * NINGUN endpoint acepta un identificador de taller. No es una omision: es la
 * garantia de aislamiento. El taller lo resuelve el servicio desde el token, de
 * modo que un administrador no puede pedir las cifras de otro taller ni
 * manipulando la URL, y no hace falta comprobar despues que el taller pedido
 * coincida con el suyo porque nunca hay un taller pedido.
 *
 * `@PreAuthorize` a nivel de clase, con `@EnableMethodSecurity` ya activo en
 * {@code SecurityConfig}. La cadena de filtros solo exige estar autenticado
 * (`anyRequest().authenticated()`), asi que sin esto cualquier rol con sesion
 * valida —un vendedor, un policia— leeria la rentabilidad del taller entero.
 * El prefijo `ROLE_` lo pone {@code JwtConfig} al convertir la claim `role`, de
 * ahi que la expresion use `hasRole` y no `hasAuthority`.
 *
 * Se anota SOLO este controller: el resto de la API sigue como estaba, porque
 * repartir autorizacion por metodo en endpoints ajenos es un cambio de alcance
 * distinto y con su propio riesgo.
 */
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/v1/dashboard")
@RestController
public class AdminDashboardController {

    private final AdminDashboardService service;

    /**
     * KPI y rankings del taller, en una sola peticion.
     *
     * Sin parametros: nada de lo que devuelve depende del periodo que el
     * administrador tenga seleccionado en las graficas, asi que cambiar de
     * granularidad no tiene por que recalcular seis rankings.
     */
    @GetMapping("/overview")
    public ResponseEntity<AdminDashboardOverviewResponseDTO> getOverview() {
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(service.getOverview());
    }

    /**
     * Serie de VENTAS GENERADAS.
     *
     * La ruta dice `sales-trend` y no `revenue-trend` porque lo que agrega es
     * venta emitida, no dinero cobrado: una cambiaria pendiente suma aqui. El
     * nombre anterior invitaba a leerlo como caja, que es otra cifra con otra
     * regla y otro dueno (el cierre diario).
     *
     * `granularity` es un enum, de modo que un valor no contemplado lo rechaza
     * Spring al convertir y nunca llega a la consulta.
     *
     * `period` es el intervalo pedido y su formato depende de la granularidad:
     * `AAAA-MM` con DAY, `AAAA` con MONTH, ignorado con YEAR —cuyo rango es
     * toda la historia del taller y no se elige—. Se omite para tomar el
     * intervalo en curso.
     */
    @GetMapping("/sales-trend")
    public ResponseEntity<SalesTrendResponseDTO> getSalesTrend(
            @RequestParam(defaultValue = "DAY") SalesGranularity granularity,
            @RequestParam(required = false) String period) {
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(service.getSalesTrend(granularity, period));
    }

    /**
     * Ocupacion del taller al cierre de cada dia de la ventana.
     *
     * `days` cuenta hacia atras incluyendo hoy. El servicio valida el rango; un
     * valor fuera de el responde 400 con mensaje de negocio, no una serie
     * recortada en silencio.
     */
    @GetMapping("/vehicle-occupancy")
    public ResponseEntity<VehicleOccupancyResponseDTO> getVehicleOccupancy(
            @RequestParam(defaultValue = "30") int days) {
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(service.getVehicleOccupancy(days));
    }

}
