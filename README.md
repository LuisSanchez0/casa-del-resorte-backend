# La Casa del Resorte

Aqui se encuentran las instrucciones necesarias para que levantar el backend correctamente.

## Requisitos previos

Se necesita tener las siguientes herramientas de forma local:

- **Docker** ≥ 24.x
- **Docker Compose** ≥ 2.x

Verificar las versiones con:

```bash
docker --version
docker compose version
```

## Variables de entorno

Se pueden configurar las variables de dos formas:

- Crea un archivo `.env` en la raiz del repositorio siguiendo el formato el archivo `.env.template` y configura las variables según el entorno.
- Configura las variables directamente en el OS basandote en el archivo `.env.template`.

## Levantar para desarrollo

Una vez configuradas las variables de entorno, ejecuta:

```bash
mvn spring-boot:run
```

Esto hará que se levante la base de datos de desarrollo.

## Pruebas

```bash
./mvnw test
```

### Las pruebas NUNCA tocan la base de desarrollo

Cada prueba que necesita base de datos crea la SUYA, la migra con Flyway, la usa
y la retira al terminar. Ninguna escribe en `spring_house_db`.

Que eso se cumpla no depende de recordarlo:

- **`TestDatabases`** es el unico sitio que ejecuta `CREATE`/`DROP DATABASE`, y
  comprueba el nombre antes de tocar el servidor.
- **`TestDatabaseGuard`** se aplica a todos los contextos de Spring de la suite
  y aborta el arranque —antes de Flyway— si el datasource resuelto no es una
  base de pruebas.
- **`TestDatabaseIsolationTest`** falla si alguien anade un `@SpringBootTest` o
  un `@DataJpaTest` sin `@DynamicPropertySource`.

El mensaje cuando algo se sale de sitio es explicito:

```txt
Refusing to run tests against non-test database: spring_house_db
```

Motivo: `application.yml` importa el `.env` del desarrollador, asi que un
contexto de prueba que no redefina el datasource resuelve `DB_NAME` y Flyway
migra la base de trabajo. No falla nada, la suite pasa en verde y el unico
rastro queda en `flyway_schema_history`. Ya ocurrio una vez.

### Convencion de nombres

Toda base de integracion termina en `_it`, con un sufijo opcional cuando una
misma clase levanta varias:

```txt
sale_service_it
cash_closure_service_it
cash_closure_dimensions_it_backfill
```

Es lo que el guardia acepta. Cualquier otro nombre se rechaza.

### Variables de entorno de las pruebas

Las pruebas necesitan un PostgreSQL accesible y reutilizan las mismas variables
de conexion que el desarrollo, **salvo `DB_NAME`**, que nunca se usa: el nombre
de cada base lo decide la propia prueba.

```txt
DB_HOST   por defecto localhost
DB_PORT   por defecto 5432
DB_USER   por defecto postgres
DB_PASS   obligatoria
```

La variable de entorno gana sobre el `.env`, asi que en CI basta con exportarlas
y no hace falta crear ni editar ningun fichero del repositorio. El usuario debe
poder crear y borrar bases de datos.
