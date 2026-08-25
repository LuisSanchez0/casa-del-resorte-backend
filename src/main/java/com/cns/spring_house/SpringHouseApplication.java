package com.cns.spring_house;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import com.cns.spring_house.shared.config.time.BusinessTimeConfigurer;

@SpringBootApplication
@EnableJpaAuditing
public class SpringHouseApplication {

	public static void main(String[] args) {
		// Antes de cualquier otra cosa: el dominio usa LocalDateTime.now() sin
		// zona explicita, asi que la zona por defecto de la JVM debe quedar fija
		// en la zona de negocio del taller antes de que Spring construya nada.
		BusinessTimeConfigurer.pinDefaultTimeZone();
		SpringApplication.run(SpringHouseApplication.class, args);
	}

}
