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
