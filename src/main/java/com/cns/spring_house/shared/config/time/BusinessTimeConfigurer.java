package com.cns.spring_house.shared.config.time;

import java.util.TimeZone;

/**
 * Fija la zona horaria por defecto de la JVM a la zona de negocio del taller
 * ({@link TimeConfig#BUSINESS_ZONE}) antes de que Spring construya el
 * contexto.
 *
 * El dominio de ventas y de movimientos de vehiculos escribe timestamps con
 * {@code LocalDateTime.now()} desnudo, sin zona explicita: ese metodo resuelve
 * la hora de pared usando la zona por defecto de la JVM en ese instante, la
 * cual normalmente depende del sistema operativo o del contenedor donde corre
 * el proceso. Sin este ajuste, desplegar en un contenedor con la zona por
 * defecto en UTC (habitual en imagenes minimas) correria el limite de "hoy"
 * hasta 6 horas respecto al horario real del taller, aunque la agregacion del
 * dashboard siguiera siendo coherente consigo misma.
 *
 * Debe llamarse una unica vez, muy al principio de {@code main(...)}, antes de
 * {@code SpringApplication.run(...)}: una vez que algun {@code LocalDateTime}
 * ya se calculo con la zona antigua no hay forma de corregirlo despues.
 */
public final class BusinessTimeConfigurer {

    private BusinessTimeConfigurer() {
    }

    public static void pinDefaultTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone(TimeConfig.BUSINESS_ZONE));
    }

}
