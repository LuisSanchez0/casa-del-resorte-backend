-- V28 canonizo la placa abierta con UPPER(BTRIM(plate)), y BTRIM sin segundo
-- argumento recorta UNICAMENTE espacios. `String.trim()` de Java —la forma
-- canonica de la aplicacion— recorta cualquier caracter <= U+0020, tabuladores y
-- saltos de linea incluidos.
--
-- La prueba parametrizada de VehicleMovementRepositoryTest lo detecto: con
-- 'P123ABC' ya abierta, la variante E'\tP123ABC' seguia entrando como un
-- vehiculo distinto mientras ' P123ABC ' ya se rechazaba. Es el mismo tipo de
-- hueco que V28 vino a cerrar, un caracter mas alla.
--
-- Se nombra el conjunto de caracteres en lugar de confiar en el de por defecto:
-- espacio, tabulador, salto de linea, retorno de carro, avance de pagina y
-- tabulador vertical. Con eso la identidad de la base coincide con la de la
-- aplicacion para todo espacio en blanco que pueda aparecer de verdad en una
-- carga de datos o en SQL manual.
--
-- Queda FUERA el resto de caracteres de control por debajo de U+0020 (E'\x01' y
-- companiia), que Java si recortaria. No se persiguen: no son espacio en blanco
-- en ningun formato de intercambio real, y por la API son inalcanzables porque
-- el @Pattern de la placa (^[A-Z]{1,2}\d{3}[A-Z]{3}$) corre despues de
-- normalizar y rechaza cualquiera de ellos. Enumerarlos convertiria la
-- expresion del indice en algo ilegible a cambio de nada.
--
-- Comprobado antes de aplicar: 0 grupos en conflicto agrupando movimientos
-- abiertos por (workshop_id, UPPER(BTRIM(plate, <clase>))) y 0 filas cuyo
-- `plate` difiera de su forma canonica. No se reescribe ninguna fila.
--
-- Se conserva el nombre del indice por el mismo motivo que en V28: el servicio
-- lo compara para traducir la violacion concurrente en un 400 de negocio.

DROP INDEX uq_vehicle_movement_open_plate_per_workshop;

CREATE UNIQUE INDEX uq_vehicle_movement_open_plate_per_workshop
    ON vehicle_movement(workshop_id, UPPER(BTRIM(plate, E' \t\n\r\f\v')))
    WHERE exit IS NULL;
