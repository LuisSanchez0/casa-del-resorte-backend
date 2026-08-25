-- La bitacora policial de vehiculo (vehicle_movement) no guardaba ningun dato
-- de contacto de la persona asociada a la ENTRADA. El negocio lo pide para toda
-- bitacora nueva, y lo pide como FOTO HISTORICA: el telefono que se capturo en
-- ESA visita, no "el telefono actual de la placa".
--
-- Por eso la columna vive en vehicle_movement y no en una tabla de vehiculo o
-- de propietario. Una relacion placa -> telefono_actual seria un valor mutable
-- compartido: cambiarlo en la visita de octubre reescribiria lo que muestra el
-- historial de la visita de agosto. Una placa puede cambiar de dueno, asi que
-- ese "unico telefono vigente" no solo es fragil, es falso. Cada fila conserva
-- el suyo y ninguna escritura posterior lo toca.
--
-- NO se toca daily_log. Esa es la bitacora de la SECRETARIA (un registro
-- historico levantado a partir de una venta ya facturada, con sale_id UNIQUE
-- desde V26); no tiene placa, ni entrada, ni salida, y no es la bitacora que
-- registra Policia. Compartir el nombre "bitacora" no las hace la misma cosa.
--
-- ── Tipo ────────────────────────────────────────────────────────
--
-- VARCHAR(8) y no un entero. Un telefono no es una cantidad: no se suma, no se
-- promedia, y guardarlo como numero perderia un cero inicial silenciosamente
-- (02345678 volveria como 2345678). El ancho exacto tambien deja el limite
-- superior en el esquema: un noveno digito no cabe.
--
-- ── Nullable, a proposito ───────────────────────────────────────
--
-- Misma decision, y por la misma razon, que V23 con employee_id y que V26 con
-- sale_id: las filas anteriores a esta migracion NO tienen telefono demostrable.
--
-- No se rellenan. Copiarles el telefono de una bitacora mas nueva de la misma
-- placa seria inventar un dato de contacto historico —y ademas atribuirle a una
-- visita antigua a una persona que quiza ni participo en ella—. Se quedan en
-- NULL, la interfaz las muestra como "No registrado", y el contrato de la
-- aplicacion exige el telefono unicamente para las bitacoras NUEVAS
-- (CreateVehicleMovementRequestDTO lo declara @NotBlank + @Pattern).
--
-- Por eso la columna no puede ser NOT NULL: eso obligaria a inventar un valor
-- para el historico o a borrarlo, y las dos cosas destruyen bitacora real.
--
-- ── El CHECK, y por que aqui SI es VALID ────────────────────────
--
-- Mismo criterio que fijo V30 para la placa: la base garantiza POR SU CUENTA la
-- forma canonica del valor, para que una escritura que no pase por el servicio
-- —SQL manual, una carga de datos, un endpoint futuro— no pueda dejar
-- '5845-2136' ni '+50258452136' en una columna que el resto del sistema lee
-- como ocho digitos.
--
-- Es EXACTAMENTE el patron que valida CreateVehicleMovementRequestDTO
-- (^[0-9]{8}$): se copia el contrato de la aplicacion, no se inventa uno nuevo.
-- Anclado, asi que ni espacios ni signos ni un salto de linea final caben.
--
-- V30 tuvo que entrar NOT VALID porque existia una fila historica que la
-- violaba. Aqui no puede existir ninguna: la columna nace en esta misma
-- migracion y toda fila anterior la tiene en NULL, que el CHECK admite de forma
-- explicita. Se valida desde el primer momento, y el pasado tambien queda
-- cubierto sin reescribir una sola fila.
--
-- ── El indice, y la medicion que lo justifica ───────────────────
--
-- La sugerencia de telefono
-- (VehicleMovementRepository.findLatestPhoneByPlate) es un top-1 por
-- (taller, placa canonica) sobre TODO el historico, no solo sobre los
-- movimientos abiertos. Ninguno de los indices que ya existian la resuelve:
--
--   uq_vehicle_movement_open_plate_per_workshop  es parcial WHERE exit IS NULL
--                                                (solo visitas abiertas)
--   idx_vehicle_movement_workshop_entry          no lleva la placa
--
-- Medido con EXPLAIN (ANALYZE, BUFFERS) contra PostgreSQL 18, sobre 200.000
-- movimientos, 3 talleres y 5.000 placas distintas, ANTES de crear nada:
--
--   placa conocida     58.6 ms   3.546 buffers   66.666 filas descartadas
--   placa desconocida  35.3 ms   3.535 buffers   66.667 filas descartadas
--
-- El planificador recorre idx_vehicle_movement_workshop_entry hacia atras y
-- descarta por filtro TODO el historico del taller, porque la placa no esta en
-- el indice. El peor caso no es raro: es exactamente lo que pasa cuando la
-- placa es nueva, que es el caso mas frecuente del alta.
--
-- Con este indice, misma consulta y mismos datos:
--
--   placa conocida      0.26 ms       4 buffers
--   placa desconocida   0.07 ms       3 buffers
--
-- Tres ordenes de magnitud, y el coste deja de crecer con el historico del
-- taller. Es indice medido, no especulativo.
--
-- Las columnas siguen el orden en que la consulta las usa: igualdad por taller,
-- igualdad por placa canonica, y `entry DESC, id DESC` para que el LIMIT 1 salga
-- del propio recorrido sin ordenar nada. La expresion de la placa es la MISMA
-- que ya usa el indice unico desde V28 (UPPER(BTRIM(plate))); si no coincidiera
-- caracter por caracter, PostgreSQL no podria usarlo.
--
-- PARCIAL sobre `phone IS NOT NULL`, que es la condicion de la propia consulta:
-- una fila sin telefono no puede ser nunca una sugerencia. Eso deja fuera del
-- indice todo el historico anterior a esta migracion —que es NULL entero— en
-- lugar de indexar filas que jamas se leen por este camino. Ocupa 9,9 MB frente
-- a los 12 MB del indice completo ya existente sobre la misma tabla.

ALTER TABLE vehicle_movement
    ADD COLUMN phone VARCHAR(8);

ALTER TABLE vehicle_movement
    ADD CONSTRAINT ck_vehicle_movement_phone_local
        CHECK (phone IS NULL OR phone ~ '^[0-9]{8}$');

CREATE INDEX idx_vehicle_movement_phone_suggestion
    ON vehicle_movement(workshop_id, UPPER(BTRIM(plate)), entry DESC, id DESC)
    WHERE phone IS NOT NULL;
