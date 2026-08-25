-- V18 indexaba `plate` tal cual, asi que la unicidad de la placa abierta dependia
-- de que la capa de aplicacion normalizara antes de escribir: 'p123abc' y
-- 'P123ABC' convivian abiertas en el mismo taller si alguna escritura no pasaba
-- por el servicio (SQL manual, carga de datos, un endpoint futuro).
--
-- Ahora el servicio normaliza (trim + upper) y la base lo garantiza por su cuenta
-- indexando UPPER(plate). Se conserva el mismo nombre de indice porque el servicio
-- lo usa para traducir la violacion concurrente a un 400 de negocio.
--
-- Comprobado antes de aplicar: vehicle_movement no tiene filas ni duplicados
-- case-insensitive, por lo que el indice no puede fallar sobre datos existentes.

DROP INDEX uq_vehicle_movement_open_plate_per_workshop;

CREATE UNIQUE INDEX uq_vehicle_movement_open_plate_per_workshop
    ON vehicle_movement(workshop_id, UPPER(plate))
    WHERE exit IS NULL;
