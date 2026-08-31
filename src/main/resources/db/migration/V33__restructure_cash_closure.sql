-- ═══════════════════════════════════════════════════════════════════════════
-- Reestructuracion del cierre de caja.
--
-- El cierre dejaba de responder la pregunta que el taller de verdad hace. Tenia
-- un ARQUEO manual (fondo inicial, efectivo contado, diferencia) y una cartera
-- GLOBAL, pero no decia que se vendio, ni cuantas unidades, ni que documentos
-- cambiarios quedaron pendientes de cobro.
--
-- Lo que se elimina y lo que se anade responde a eso:
--
--   FUERA  arqueo manual        nadie cuenta el cajon contra un fondo declarado
--   FUERA  cartera global       mezclaba el saldo historico del taller con el
--                               periodo que el cierre describe
--   DENTRO articulos vendidos   que se vendio y cuanto genero cada cosa
--   DENTRO documentos cambiarios cuales quedaron pendientes y cuales se cobraron
--   DENTRO formas de pago       una fila por metodo, sin columnas fijas
--
-- La distincion que ya existia y NO se toca es la importante:
--
--   FACTURADO -> status COMPLETED, por `completed_at`
--   COBRADO   -> payment_status PAID, por `paid_at`
--
-- ═══════════════════════════════════════════════════════════════════════════


-- ── PARTE 1: fuera el arqueo manual ────────────────────────────────────────
--
-- Se DROPEAN y no se dejan como columnas muertas: el arqueo se elimina del
-- modelo, y una columna NOT NULL que nadie vuelve a escribir solo obligaria a
-- inventar un valor en cada INSERT.
--
-- NO se asume que la tabla este vacia. Ninguna migracion borra `cash_closure`,
-- asi que una base que ya operaba conserva sus cierres, y este DROP destruye el
-- arqueo historico de esos dias. Es la eliminacion pedida, no un descuido: el
-- fondo declarado y el conteo manual dejan de formar parte del cierre. Lo que
-- SI se preserva de esos dias son las cifras contables —facturado, cobrado y
-- desglose por forma de pago—, que viven en columnas que sobreviven intactas.
--
-- `notes` se va con ellas: existia como observacion DEL ARQUEO ("faltaron Q20
-- del vuelto"), y sin arqueo no observa nada.

ALTER TABLE cash_closure DROP COLUMN opening_cash;
ALTER TABLE cash_closure DROP COLUMN expected_cash;
ALTER TABLE cash_closure DROP COLUMN counted_cash;
ALTER TABLE cash_closure DROP COLUMN difference;
ALTER TABLE cash_closure DROP COLUMN notes;

-- Cartera GLOBAL del taller. Se va del cierre por la misma razon: era el saldo
-- pendiente total en ese instante, viniera del dia que viniera, y mezclado con
-- las cifras del periodo invitaba a leerlo como deuda generada ese dia.
ALTER TABLE cash_closure DROP COLUMN pending_exchange_amount;


-- ── PARTE 2: cifras del periodo que faltaban ───────────────────────────────

-- De las cambiarias EMITIDAS en el periodo, lo que quedo sin cobrar AL CERRAR.
-- Es period-scoped, no el saldo historico del taller: responde "de lo que
-- factura hoy a credito, cuanto sigue debiendose".
ALTER TABLE cash_closure
    ADD COLUMN issued_exchange_pending_amount NUMERIC(10, 2) NOT NULL DEFAULT 0
        CHECK (issued_exchange_pending_amount >= 0);

-- Total facturado en articulos. Permite comprobar que el desglose por articulo
-- del snapshot suma lo que el snapshot dice que se facturo, sin recorrer las
-- ventas.
ALTER TABLE cash_closure
    ADD COLUMN items_billed_amount NUMERIC(12, 2) NOT NULL DEFAULT 0
        CHECK (items_billed_amount >= 0);

-- Los DEFAULT existen para que el ALTER pase sin pedir un valor fila a fila;
-- despues se retiran porque el servicio SIEMPRE los escribe explicitamente.
--
-- En un cierre anterior a esta migracion los dos quedan en 0, y en el caso de
-- `items_billed_amount` eso es una cifra que falta, no una venta que no hubo:
-- el desglose por articulo no se guardaba, y no existe de donde reconstruirlo
-- sin releer `sale_detail` —justo lo que un snapshot confirmado no puede hacer,
-- porque hoy esas ventas pueden estar revertidas o renombradas—. Lo mismo con
-- `issued_exchange_pending_amount`: cuanto de lo emitido ESE dia seguia sin
-- cobrarse AL CERRAR no se registro, y deducirlo del estado de hoy dataria mal
-- cobros posteriores. Se prefiere el 0 explicito a un numero inventado.
ALTER TABLE cash_closure ALTER COLUMN issued_exchange_pending_amount DROP DEFAULT;
ALTER TABLE cash_closure ALTER COLUMN items_billed_amount DROP DEFAULT;


-- ── PARTE 3: el snapshot deja de ser solo escalares ────────────────────────
--
-- Con totales sueltos no se puede reconstruir "que se vendio el 14 de marzo":
-- habria que volver a consultar `sale_detail`, y eso es justo lo que un cierre
-- confirmado no puede hacer —el producto pudo renombrarse, la venta pudo
-- revertirse, la cambiaria pudo cobrarse despues—. Por eso el detalle se COPIA
-- al cerrar y el historico se lee de aqui.

-- Articulos vendidos (facturados) en el periodo.
--
-- `product_id` es la identidad ESTABLE por la que se agrupo; `item_name` es el
-- nombre en el momento del cierre. Se guardan los dos: agrupar por nombre
-- fusionaria dos productos homonimos distintos, y guardar solo el id dejaria el
-- historico ilegible si el catalogo cambia.
CREATE TABLE cash_closure_item (
    id UUID PRIMARY KEY,
    cash_closure_id UUID NOT NULL,

    product_id UUID NOT NULL,
    item_name VARCHAR(100) NOT NULL,
    -- Categoria de la familia: NORMAL, SPECIAL, CONTAINER o JOB. Se copia como
    -- texto y sin FK a `family` porque es el valor que tenia AL CERRAR.
    item_type VARCHAR(20) NOT NULL
        CHECK (item_type IN ('NORMAL', 'SPECIAL', 'CONTAINER', 'JOB')),

    quantity BIGINT NOT NULL CHECK (quantity > 0),
    billed_amount NUMERIC(12, 2) NOT NULL CHECK (billed_amount >= 0),

    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,

    -- Un producto aparece UNA vez por cierre: la fila ya viene agregada.
    CONSTRAINT uq_cash_closure_item UNIQUE (cash_closure_id, product_id),
    FOREIGN KEY (cash_closure_id) REFERENCES cash_closure(id) ON DELETE CASCADE
);

-- Documentos cambiarios del periodo, en sus DOS papeles.
--
-- `scope` distingue los dos conjuntos, que son distintos y no se pueden fundir:
--
--   ISSUED     emitida en el periodo. Puede estar cobrada o no.
--   COLLECTED  cobrada en el periodo. Pudo emitirse hace un mes.
--
-- Una cambiaria emitida y cobrada el mismo dia aparece en las dos, con scope
-- distinto: es informacion legitima en ambos apartados. Su dinero entra en el
-- total cobrado UNA sola vez porque ese total no se suma de aqui, sino de
-- `cash_closure_payment_method`.
CREATE TABLE cash_closure_exchange_invoice (
    id UUID PRIMARY KEY,
    cash_closure_id UUID NOT NULL,

    scope VARCHAR(10) NOT NULL CHECK (scope IN ('ISSUED', 'COLLECTED')),

    -- La venta de origen, para poder abrir el documento desde el historico.
    sale_id UUID NOT NULL,
    -- Codigo corto legible impreso en la factura. No se ensena el UUID crudo.
    document_code VARCHAR(12) NOT NULL,
    customer_name VARCHAR(75),

    issued_at TIMESTAMP NOT NULL,
    amount NUMERIC(10, 2) NOT NULL CHECK (amount >= 0),

    -- ESTADO AL CERRAR, no el de hoy. Es la razon de existir de esta tabla:
    -- cobrar el miercoles una cambiaria del lunes no puede reescribir el cierre
    -- del lunes, que la registro como pendiente.
    collected BOOLEAN NOT NULL,
    paid_at TIMESTAMP,
    payment_method VARCHAR(20) CHECK (payment_method IN ('CASH', 'CARD', 'TRANSFER')),

    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,

    -- Cobrada implica fecha y forma de cobro; pendiente implica que no las hay.
    -- Sin esto podria persistirse una fila "cobrada" sin decir cuando ni como.
    CONSTRAINT ck_cash_closure_exchange_collected CHECK (
        (collected = true AND paid_at IS NOT NULL AND payment_method IS NOT NULL)
        OR
        (collected = false AND paid_at IS NULL AND payment_method IS NULL)
    ),

    -- El mismo documento no puede repetirse dentro del mismo papel.
    CONSTRAINT uq_cash_closure_exchange UNIQUE (cash_closure_id, scope, sale_id),
    FOREIGN KEY (cash_closure_id) REFERENCES cash_closure(id) ON DELETE CASCADE
);

-- Cobros por forma de pago, UNA FILA POR METODO.
--
-- Sustituye a las seis columnas fijas (`cash_payments`, `card_payments`,
-- `transfer_payments` y sus contadores), que obligaban a una migracion cada vez
-- que el negocio aceptara una forma de pago nueva. Las columnas viejas se
-- CONSERVAN de momento: ver la nota al final.
CREATE TABLE cash_closure_payment_method (
    id UUID PRIMARY KEY,
    cash_closure_id UUID NOT NULL,

    payment_method VARCHAR(20) NOT NULL
        CHECK (payment_method IN ('CASH', 'CARD', 'TRANSFER')),
    payment_count BIGINT NOT NULL CHECK (payment_count >= 0),
    amount NUMERIC(12, 2) NOT NULL CHECK (amount >= 0),

    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,

    CONSTRAINT uq_cash_closure_payment_method
        UNIQUE (cash_closure_id, payment_method),
    FOREIGN KEY (cash_closure_id) REFERENCES cash_closure(id) ON DELETE CASCADE
);

-- Las tres tablas se leen SIEMPRE por su cierre, tanto en el detalle diario
-- como en la agregacion mensual. El UNIQUE de cada una ya crea un indice que
-- empieza por `cash_closure_id`, asi que no se anade ninguno mas: seria el
-- mismo prefijo dos veces.

-- ── PARTE 4: los cierres que ya existian ──────────────────────────────
--
-- El desglose por forma de pago SI se puede reconstruir con exactitud, y hay
-- que hacerlo. El total cobrado del mes se agrega desde los escalares de
-- `cash_closure`, mientras que el desglose por metodo se lee de la tabla hija:
-- si un dia antiguo aportara su total pero ninguna fila de metodo, el desglose
-- mensual dejaria de sumar el total cobrado, que es precisamente la
-- correspondencia que este cierre promete.
--
-- La fuente NO es `sale`: son las columnas del propio snapshot, que ya eran
-- inmutables y siguen ahi. Reconstruir desde las ventas podria contradecir lo
-- que ese dia declaro; copiar sus escalares no puede.
--
-- Se escriben los tres metodos aunque valgan cero, igual que hace el servicio
-- para un cierre nuevo: una forma de pago sin cobros vale Q0.00, y decirlo es
-- informacion.

INSERT INTO cash_closure_payment_method
    (id, cash_closure_id, payment_method, payment_count, amount, created_at, updated_at)
SELECT gen_random_uuid(), c.id, m.payment_method, m.payment_count, m.amount,
       c.created_at, c.updated_at
FROM cash_closure c
CROSS JOIN LATERAL (
    VALUES ('CASH',     c.cash_payment_count,     c.cash_payments),
           ('CARD',     c.card_payment_count,     c.card_payments),
           ('TRANSFER', c.transfer_payment_count, c.transfer_payments)
) AS m(payment_method, payment_count, amount);


-- NOTA sobre las columnas escalares que SOBREVIVEN en `cash_closure`
-- (`invoice_count`, `invoice_total`, `exchange_invoice_count`,
-- `exchange_invoice_total`, `total_invoiced`, `cash_payments`, `card_payments`,
-- `transfer_payments`, sus contadores, `total_collected`,
-- `exchange_collected_count`, `exchange_collected_amount`,
-- `previous_exchange_collected_amount`):
--
-- Se conservan a proposito. Son los totales que el cierre mensual agrega con
-- UNA consulta sobre `cash_closure`; reconstruirlos mes a mes desde las tablas
-- de detalle obligaria a agrupar tres tablas hijas para responder lo que ya
-- esta sumado. El detalle sirve para RECONSTRUIR un dia; los escalares, para
-- AGREGAR muchos.
