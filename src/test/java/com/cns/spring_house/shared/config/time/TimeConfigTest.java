package com.cns.spring_house.shared.config.time;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.TimeZone;

import org.junit.jupiter.api.Test;

/**
 * La zona de negocio debe ser una zona concreta y conocida (America/Guatemala,
 * sin horario de verano), no la zona por defecto de donde sea que corra la
 * JVM. Ver el javadoc de {@link TimeConfig} y {@link BusinessTimeConfigurer}.
 */
class TimeConfigTest {

    @Test
    void businessZoneIsGuatemalaWithNoDaylightSavingOffset() {
        assertThat(TimeConfig.BUSINESS_ZONE).isEqualTo(ZoneId.of("America/Guatemala"));

        // Sin DST: el mismo desfase todo el ano, en enero y en julio.
        ZoneId zone = TimeConfig.BUSINESS_ZONE;
        assertThat(zone.getRules().getOffset(Instant.parse("2026-01-15T12:00:00Z")))
                .isEqualTo(zone.getRules().getOffset(Instant.parse("2026-07-15T12:00:00Z")));
    }

    @Test
    void systemClockUsesTheBusinessZoneRegardlessOfTheJvmDefaultZone() {
        Clock clock = new TimeConfig().systemClock();

        assertThat(clock.getZone()).isEqualTo(TimeConfig.BUSINESS_ZONE);
    }

    /**
     * Prueba directa del problema que motiva este bean: una venta completada
     * de noche en Guatemala cae en el dia siguiente en UTC. El reloj de
     * negocio no debe adelantar la fecha por ese cruce.
     */
    @Test
    void businessDateDoesNotAdvanceOnUtcRolloverAtNightInGuatemala() {
        // 19:00 del 15/08 en Guatemala (UTC-6) = 01:00 del 16/08 en UTC.
        Instant nightInGuatemala = Instant.parse("2026-08-16T01:00:00Z");
        Clock clock = Clock.fixed(nightInGuatemala, TimeConfig.BUSINESS_ZONE);

        assertThat(LocalDate.now(clock)).isEqualTo(LocalDate.of(2026, 8, 15));
    }

    /**
     * El mismo instante interpretado en UTC ya seria el dia siguiente: prueba
     * que el reloj de negocio, y no un reloj UTC, es el que decide "hoy".
     */
    @Test
    void theSameInstantWouldIncorrectlyAppearAsTheNextDayInUtc() {
        Instant nightInGuatemala = Instant.parse("2026-08-16T01:00:00Z");

        assertThat(LocalDate.now(Clock.fixed(nightInGuatemala, ZoneId.of("UTC"))))
                .isEqualTo(LocalDate.of(2026, 8, 16));
        assertThat(LocalDate.now(Clock.fixed(nightInGuatemala, TimeConfig.BUSINESS_ZONE)))
                .isEqualTo(LocalDate.of(2026, 8, 15));
    }

    @Test
    void pinningTheDefaultTimeZoneMakesBareLocalDateTimeNowMatchTheBusinessClock() {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of("UTC")));

            BusinessTimeConfigurer.pinDefaultTimeZone();

            assertThat(TimeZone.getDefault().toZoneId()).isEqualTo(TimeConfig.BUSINESS_ZONE);
            // Tras fijarla, Clock.systemDefaultZone() (lo que hay detras de un
            // LocalDateTime.now() desnudo) coincide con la zona de negocio.
            assertThat(Clock.systemDefaultZone().getZone()).isEqualTo(TimeConfig.BUSINESS_ZONE);
        } finally {
            TimeZone.setDefault(original);
        }
    }

}
