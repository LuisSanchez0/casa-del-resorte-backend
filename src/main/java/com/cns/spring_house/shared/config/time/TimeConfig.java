package com.cns.spring_house.shared.config.time;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Reloj inyectable de la aplicacion y zona horaria de negocio del taller.
 *
 * El modulo de movimientos de vehiculos trabaja con {@code LocalDateTime} como
 * hora de pared del servidor ({@code entry} y {@code exit} se fijan con
 * {@code LocalDateTime.now()}), sin zona horaria explicita en ningun punto. Lo
 * mismo hace {@code SaleServiceImpl} con {@code completedAt}/{@code paidAt}.
 * La "fecha de negocio" del dashboard debe seguir exactamente esa misma
 * referencia para que el KPI de hoy cuente lo mismo que se escribio.
 *
 * Antes este bean devolvia {@code Clock.systemDefaultZone()}: internamente
 * consistente con esos {@code LocalDateTime.now()} (misma zona implicita
 * siempre), pero la zona en si dependia por completo de como estuviera
 * configurado el sistema operativo o el contenedor donde corre el proceso. Un
 * contenedor con la zona por defecto en UTC (habitual en imagenes minimas)
 * haria que una venta completada a las 19:00 hora de Guatemala se registrara
 * como la 01:00 UTC del dia siguiente: el limite de "hoy" se correria un dia
 * respecto al horario real del taller, aunque la agregacion siguiera siendo
 * matematicamente coherente consigo misma.
 *
 * {@link BusinessTimeConfigurer} fija la zona por defecto de la JVM a
 * {@link #BUSINESS_ZONE} al arrancar la aplicacion (antes de que Spring
 * construya nada), asi que tanto este reloj como cualquier
 * {@code LocalDateTime.now()} desnudo del resto del dominio —incluida la
 * auditoria JPA {@code @CreatedDate}/{@code @LastModifiedDate}— quedan
 * anclados a la misma zona real del negocio sin importar el sistema operativo
 * o el contenedor de despliegue.
 *
 * El bean sigue existiendo (en lugar de llamadas directas a
 * {@code LocalDate.now()}) para que la fecha de negocio se pueda fijar en las
 * pruebas: sin el, el limite del dia dependeria del reloj real de la maquina y
 * una prueba que corriera a las 23:59 seria intermitente.
 */
@Configuration
public class TimeConfig {

    /**
     * Guatemala no observa horario de verano, asi que el desfase respecto a
     * UTC es constante (-06:00) todo el ano: no hace falta lidiar con
     * transiciones de DST en los limites de "hoy".
     */
    public static final ZoneId BUSINESS_ZONE = ZoneId.of("America/Guatemala");

    @Bean
    Clock systemClock() {
        return Clock.system(BUSINESS_ZONE);
    }

}
