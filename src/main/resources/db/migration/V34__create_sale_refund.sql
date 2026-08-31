-- ═══════════════════════════════════════════════════════════════════════════
-- Devoluciones de dinero.
--
-- Revertir una venta y devolver el dinero eran, hasta aqui, la misma cosa sin
-- serlo. `SaleServiceImpl.reverse` deshace lo que la venta reservo —existencias,
-- lotes FIFO, WorkResult— y deja constancia en `sale_action`, pero NO tocaba
-- `payment_status` ni registraba salida de dinero alguna. Consecuencia: si el
-- taller devolvia Q1,000 en efectivo por una venta ya cerrada, ningun informe
-- lo sabia. El lunes seguia diciendo que entraron Q1,000 —y es cierto— pero
-- nada decia que salieron.
--
-- Esta tabla es ese hecho que faltaba: DINERO QUE SALIO, con su fecha propia.
--
-- La distincion que sostiene todo el diseno:
--
--   sale.status = REVERSED   el documento se deshizo
--   sale_refund              el dinero volvio al cliente
--
-- Son independientes. Se puede revertir sin devolver (el cliente se lleva
-- otra cosa, o se le abona despues), y por eso la devolucion NO se infiere del
-- estado: se pregunta y se registra, o no existe.
-- ═══════════════════════════════════════════════════════════════════════════


-- ── El evento de salida de dinero ──────────────────────────────────────────
--
-- `effective_at` es la columna que decide TODO el reporte: la devolucion
-- pertenece al periodo en que el dinero salio, no al periodo de la venta que
-- la origino. Una venta de agosto devuelta en septiembre es dinero que salio
-- en SEPTIEMBRE, y agosto no se toca.

CREATE TABLE sale_refund (
    id UUID PRIMARY KEY,

    workshop_id UUID NOT NULL,
    sale_id UUID NOT NULL,

    -- Hoy solo existe REFUND. Se guarda igualmente porque esta tabla es el
    -- unico sitio del dominio donde vive una salida de dinero: una nota de
    -- credito o un ajuste de caja futuros son otra clase del mismo hecho, y
    -- discriminarlos por columna es mas barato que migrar la tabla entera.
    type VARCHAR(20) NOT NULL CHECK (type IN ('REFUND')),

    -- Positivo SIEMPRE. La direccion la da el tipo, no el signo: un importe
    -- negativo aqui permitiria "devolver" dinero hacia dentro.
    amount NUMERIC(10, 2) NOT NULL CHECK (amount > 0),

    -- El instante REAL de la devolucion, puesto por el reloj del servidor.
    -- Nunca por el cliente: quien elige la fecha elige en que cierre cae.
    effective_at TIMESTAMP NOT NULL,

    -- COMO volvio el dinero, que no tiene por que ser como entro: una venta
    -- cobrada con tarjeta puede devolverse por transferencia.
    payment_method VARCHAR(20) NOT NULL
        CHECK (payment_method IN ('CASH', 'CARD', 'TRANSFER')),

    reason VARCHAR(500) NOT NULL,

    -- Quien la hizo, resuelto del token. El cliente no lo envia.
    performed_by UUID NOT NULL,

    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,

    -- UNA devolucion por venta.
    --
    -- No es una limitacion arbitraria: una venta se revierte UNA sola vez
    -- —`reverse` rechaza la segunda— y la devolucion se captura DENTRO de esa
    -- reversion. Mientras esa siga siendo la unica via, dos devoluciones para
    -- la misma venta solo pueden ser un doble envio o una carrera, y las dos
    -- deben fallar. Este UNIQUE hace que fallen en la base y no solo en el
    -- servicio, que es lo que lo vuelve seguro bajo concurrencia real.
    --
    -- Si algun dia se admiten devoluciones parciales sucesivas, esta
    -- restriccion se retira y el limite acumulado pasa a exigir bloqueo de
    -- fila; hasta entonces, el invariante "lo devuelto <= lo cobrado" lo
    -- garantiza esta unicidad mas la validacion de importe.
    CONSTRAINT uq_sale_refund_sale UNIQUE (sale_id),

    FOREIGN KEY (workshop_id) REFERENCES workshop(id),
    FOREIGN KEY (sale_id) REFERENCES sale(id),
    FOREIGN KEY (performed_by) REFERENCES employee(id)
);

-- El cierre diario pregunta siempre lo mismo: las devoluciones de ESTE taller
-- entre dos instantes. El indice sirve al filtro y al orden de una vez.
CREATE INDEX idx_sale_refund_workshop_effective_at
    ON sale_refund (workshop_id, effective_at);


-- ── El snapshot: devoluciones dentro del cierre confirmado ─────────────────
--
-- Mismo criterio que el resto del cierre: los ESCALARES permiten que el mes
-- agregue con una sola consulta, y la tabla hija conserva el detalle legible
-- de cada dia. Sin los escalares el mes tendria que agrupar la tabla hija para
-- responder lo que ya estaba sumado.

ALTER TABLE cash_closure
    ADD COLUMN refund_count BIGINT NOT NULL DEFAULT 0
        CHECK (refund_count >= 0),
    ADD COLUMN total_refunded NUMERIC(12, 2) NOT NULL DEFAULT 0
        CHECK (total_refunded >= 0),
    ADD COLUMN refund_cash_count BIGINT NOT NULL DEFAULT 0
        CHECK (refund_cash_count >= 0),
    ADD COLUMN refund_cash NUMERIC(12, 2) NOT NULL DEFAULT 0
        CHECK (refund_cash >= 0),
    ADD COLUMN refund_card_count BIGINT NOT NULL DEFAULT 0
        CHECK (refund_card_count >= 0),
    ADD COLUMN refund_card NUMERIC(12, 2) NOT NULL DEFAULT 0
        CHECK (refund_card >= 0),
    ADD COLUMN refund_transfer_count BIGINT NOT NULL DEFAULT 0
        CHECK (refund_transfer_count >= 0),
    ADD COLUMN refund_transfer NUMERIC(12, 2) NOT NULL DEFAULT 0
        CHECK (refund_transfer >= 0);

-- A diferencia de V33, los DEFAULT se CONSERVAN. Un cierre anterior a esta
-- migracion no tuvo devoluciones —no existian—, asi que cero no es una cifra
-- que falte: es la cifra correcta. Y conservarlos deja que el INSERT del
-- servicio siga siendo valido si algun dia se anade otra columna.

-- El detalle historico. Se COPIAN el codigo de documento y el nombre de quien
-- devolvio, no solo sus ids: renombrar despues a un empleado o revertir otra
-- vez la venta no puede cambiar lo que el cierre de ese dia dijo.
CREATE TABLE cash_closure_refund (
    id UUID PRIMARY KEY,
    cash_closure_id UUID NOT NULL,

    -- Referencia al evento vivo, para poder rastrearlo. El cierre NO lo relee:
    -- lo de abajo es su copia congelada.
    refund_id UUID NOT NULL,

    sale_id UUID NOT NULL,
    document_code VARCHAR(12) NOT NULL,

    amount NUMERIC(10, 2) NOT NULL CHECK (amount > 0),
    effective_at TIMESTAMP NOT NULL,
    payment_method VARCHAR(20) NOT NULL
        CHECK (payment_method IN ('CASH', 'CARD', 'TRANSFER')),
    performed_by_name VARCHAR(120) NOT NULL,
    reason VARCHAR(500) NOT NULL,

    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,

    -- Una devolucion aparece una sola vez en un cierre.
    CONSTRAINT uq_cash_closure_refund UNIQUE (cash_closure_id, refund_id),
    FOREIGN KEY (cash_closure_id) REFERENCES cash_closure(id) ON DELETE CASCADE
);

-- Como las otras tablas hijas, se lee siempre por su cierre y el UNIQUE ya
-- crea el indice que empieza por `cash_closure_id`. No se anade ninguno mas.
