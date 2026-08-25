-- Daily Log deja de ser una bitacora libre "una por taller y dia" y pasa a ser
-- el registro historico que la secretaria levanta A PARTIR DE UNA VENTA ya
-- facturada: la venta como referencia, y las lineas de productos y servicios
-- copiadas COMO TEXTO.
--
-- Tres cambios de esquema, ninguno destructivo:
--
-- 1. `sale_id`, la unica relacion de negocio del registro. Entra NULLABLE a
--    proposito, igual que hizo V23 con vehicle_movement.employee_id: las filas
--    anteriores a esta migracion nacieron sin venta y no hay forma de deducir a
--    cual pertenecen. Inventarles una falsearia el historial comercial, y
--    borrarlas perderia bitacora real. Se quedan como estan, sin venta, y el
--    servicio exige `sale` para todo registro nuevo (CreateDailyLogRequestDTO
--    lo declara @NotNull). El UNIQUE de abajo acepta varios NULL en PostgreSQL,
--    asi que las historicas conviven sin chocar entre si.
--
-- 2. Se retira UNIQUE(workshop_id, performed_date). Esa restriccion codificaba
--    el modelo viejo —un unico apunte por taller y dia— y es incompatible con
--    el nuevo: un taller factura varias ventas el mismo dia y cada una tiene su
--    propio registro. La unicidad que si describe el modelo nuevo es por venta.
--
-- 3. `description` pasa a ser opcional: antes era el contenido entero del
--    apunte y ahora es una nota general sobre lineas que ya se detallan aparte.
--
-- Sobre la cardinalidad: 1 venta -> 1 registro (sale_id UNIQUE). No existe
-- ningun motivo de negocio para dos registros de la misma factura, y con la
-- restriccion en el esquema un doble POST no puede duplicar el historico. Es la
-- misma decision, y por la misma razon, que sale_action.sale_id en V24.

ALTER TABLE daily_log
    DROP CONSTRAINT IF EXISTS daily_log_workshop_id_performed_date_key;

ALTER TABLE daily_log
    ADD COLUMN sale_id UUID;

ALTER TABLE daily_log
    ADD CONSTRAINT fk_daily_log_sale
        FOREIGN KEY (sale_id) REFERENCES sale(id);

ALTER TABLE daily_log
    ADD CONSTRAINT uq_daily_log_sale UNIQUE (sale_id);

ALTER TABLE daily_log
    ALTER COLUMN description DROP NOT NULL;

-- Lineas del registro.
--
-- IMPORTANTE: `daily_log_product` NO tiene product_id y `daily_log_service` NO
-- tiene job_id, ni ninguna otra columna UUID que apunte al catalogo. Es
-- deliberado y es el punto central del modelo: el registro es un SNAPSHOT
-- TEXTUAL. El nombre se valida contra el catalogo al crear el registro —el
-- servicio rechaza nombres que no existan— pero esa comprobacion no sobrevive
-- al INSERT: despues, renombrar o retirar el producto del catalogo no cambia ni
-- arrastra lo que quedo escrito aqui.
--
-- Por eso tampoco hay FK: una FK obligaria al catalogo a conservar para siempre
-- la fila referenciada, que es justo lo contrario de lo que se quiere.
--
-- El dinero repite el tipo del resto del esquema, NUMERIC(10,2), y los CHECK
-- reproducen en la tabla las mismas reglas que valida el servicio, para que un
-- INSERT por otra via tampoco pueda dejar un importe imposible.

CREATE TABLE daily_log_product (
    id UUID PRIMARY KEY,
    daily_log_id UUID NOT NULL,
    name VARCHAR(100) NOT NULL CHECK (LENGTH(TRIM(name)) > 0),
    unit_price NUMERIC(10, 2) NOT NULL CHECK (unit_price >= 0),
    quantity SMALLINT NOT NULL CHECK (quantity >= 1),
    subtotal NUMERIC(10, 2) NOT NULL CHECK (subtotal >= 0),
    FOREIGN KEY (daily_log_id) REFERENCES daily_log(id)
);

CREATE TABLE daily_log_service (
    id UUID PRIMARY KEY,
    daily_log_id UUID NOT NULL,
    name VARCHAR(100) NOT NULL CHECK (LENGTH(TRIM(name)) > 0),
    unit_price NUMERIC(10, 2) NOT NULL CHECK (unit_price >= 0),
    quantity SMALLINT NOT NULL CHECK (quantity >= 1),
    subtotal NUMERIC(10, 2) NOT NULL CHECK (subtotal >= 0),
    FOREIGN KEY (daily_log_id) REFERENCES daily_log(id)
);

-- Las lineas SIEMPRE se leen por su registro; nunca por nombre ni por importe.
CREATE INDEX idx_daily_log_product_daily_log
    ON daily_log_product(daily_log_id);

CREATE INDEX idx_daily_log_service_daily_log
    ON daily_log_service(daily_log_id);

-- El listado filtra por taller y fecha, que es como consulta la pantalla.
CREATE INDEX idx_daily_log_workshop_performed_date
    ON daily_log(workshop_id, performed_date);
