-- La unicidad de la placa ABIERTA indexaba UPPER(plate) (V19), que normaliza
-- mayusculas pero NO los espacios. La aplicacion, en cambio, canoniza con
-- `trim().toUpperCase(Locale.ROOT)` en el constructor compacto de
-- CreateVehicleMovementRequestDTO. Las dos formas canonicas no eran la misma.
--
-- Medido contra PostgreSQL antes de escribir esta migracion, con 'ZZ999ZZ' ya
-- abierta en el taller:
--
--   'zz999zz'      -> RECHAZADA   (UPPER ya lo cubria)
--   ' ZZ999ZZ '    -> ACEPTADA    <-- hueco
--   'ZZ999ZZ '     -> ACEPTADA    <-- hueco
--   '  zz999zz  '  -> ACEPTADA    <-- hueco
--
-- Por la API ese hueco no es alcanzable: el @Pattern de la placa
-- (^[A-Z]{1,2}\d{3}[A-Z]{3}$) corre DESPUES de la normalizacion y rechaza
-- cualquier resto de espacio, y `plate` no se puede modificar despues —el
-- mapper de actualizacion lo ignora explicitamente y registrar la salida solo
-- escribe `exit`—. El unico camino de escritura es el alta, y esta normalizado.
--
-- Se cierra igualmente porque ese es el criterio que V19 ya fijo para si misma:
-- "el servicio normaliza y la base lo garantiza POR SU CUENTA", precisamente
-- para que una escritura que no pase por el servicio —SQL manual, una carga de
-- datos, un endpoint futuro— no pueda dejar dos entradas abiertas del mismo
-- vehiculo. Con UPPER a secas esa garantia no existia para los espacios.
--
-- Comprobado antes de aplicar: 0 grupos en conflicto agrupando por
-- (workshop_id, UPPER(BTRIM(plate))) sobre movimientos abiertos, y 0 filas cuyo
-- `plate` difiera de su forma canonica. La migracion no puede fallar sobre los
-- datos existentes y no reescribe ninguna fila: el historico no se toca.
--
-- Se CONSERVA el nombre del indice. El servicio lo compara para traducir la
-- violacion concurrente en un 400 de negocio
-- (VehicleMovementServiceImpl.isOpenPlateViolation); renombrarlo convertiria ese
-- caso en un 500 sin que ninguna prueba de compilacion lo advirtiera.
--
-- BTRIM recorta espacios; `String.trim()` de Java recorta ademas cualquier
-- caracter <= U+0020 (tabuladores, saltos de linea). La diferencia solo podria
-- notarse en un valor que ya es invalido para el patron y que, por tanto, no
-- puede llegar por la API. Se deja BTRIM por legibilidad en lugar de una clase
-- de caracteres que nadie podria leer de un vistazo.

DROP INDEX uq_vehicle_movement_open_plate_per_workshop;

CREATE UNIQUE INDEX uq_vehicle_movement_open_plate_per_workshop
    ON vehicle_movement(workshop_id, UPPER(BTRIM(plate)))
    WHERE exit IS NULL;
