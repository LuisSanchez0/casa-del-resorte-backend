-- Cierre de caja diario: el SNAPSHOT inmutable de lo que el taller facturo,
-- cobro y conto en efectivo una fecha operativa concreta.
--
-- No existe una sesion de caja persistida (OPEN/CLOSED). Mientras no hay fila
-- aqui, `sale` es la unica fuente de verdad y el cierre se calcula al vuelo;
-- en cuanto la fila existe, ES el historico y nada vuelve a recalcularse contra
-- `sale`. Por eso se guardan tambien totales matematicamente derivables
-- (`total_invoiced`, `total_collected`, `expected_cash`, `difference`): un
-- documento historico tiene que reproducir exactamente las cifras que la
-- persona confirmo, aunque manana cambie una venta, se revierta un documento o
-- se cobre una cambiaria antigua.
--
-- `created_at` de Auditable ES el momento del cierre: no se anade una columna
-- `closed_at` que guardaria el mismo instante con otro nombre. El DTO expone
-- la semantica `closedAt` leyendo `created_at`.

CREATE TABLE cash_closure (
    id UUID PRIMARY KEY,
    workshop_id UUID NOT NULL,
    closed_by UUID NOT NULL,
    business_date DATE NOT NULL,

    -- Arqueo: lo unico que aporta la persona, mas la nota opcional.
    opening_cash NUMERIC(10, 2) NOT NULL CHECK (opening_cash >= 0),

    -- Facturacion del dia, por `completed_at` (fecha de EMISION).
    invoice_count BIGINT NOT NULL CHECK (invoice_count >= 0),
    invoice_total NUMERIC(10, 2) NOT NULL CHECK (invoice_total >= 0),
    exchange_invoice_count BIGINT NOT NULL CHECK (exchange_invoice_count >= 0),
    exchange_invoice_total NUMERIC(10, 2) NOT NULL CHECK (exchange_invoice_total >= 0),
    total_invoiced NUMERIC(10, 2) NOT NULL CHECK (total_invoiced >= 0),

    -- Cobros del dia, por `paid_at` (fecha del COBRO). Una cambiaria emitida
    -- hoy y no cobrada suma en facturacion y en cartera, nunca aqui.
    cash_payment_count BIGINT NOT NULL CHECK (cash_payment_count >= 0),
    cash_payments NUMERIC(10, 2) NOT NULL CHECK (cash_payments >= 0),
    card_payment_count BIGINT NOT NULL CHECK (card_payment_count >= 0),
    card_payments NUMERIC(10, 2) NOT NULL CHECK (card_payments >= 0),
    transfer_payment_count BIGINT NOT NULL CHECK (transfer_payment_count >= 0),
    transfer_payments NUMERIC(10, 2) NOT NULL CHECK (transfer_payments >= 0),
    total_collected NUMERIC(10, 2) NOT NULL CHECK (total_collected >= 0),

    -- Cartera cambiaria. `exchange_invoice_total` de arriba ya es lo GENERADO
    -- hoy como credito, asi que no se repite con otro nombre.
    exchange_collected_count BIGINT NOT NULL CHECK (exchange_collected_count >= 0),
    exchange_collected_amount NUMERIC(10, 2) NOT NULL CHECK (exchange_collected_amount >= 0),
    -- De lo cobrado hoy en cambiarias, la parte emitida en dias anteriores. No
    -- se deduce de las columnas de arriba (haria falta cruzar emision y cobro
    -- documento a documento), y el reporte historico la muestra igual que la
    -- previsualizacion, asi que se persiste.
    previous_exchange_collected_amount NUMERIC(10, 2) NOT NULL
        CHECK (previous_exchange_collected_amount >= 0),
    -- Saldo pendiente TOTAL del taller en el momento del cierre, no solo el de
    -- los documentos emitidos hoy.
    pending_exchange_amount NUMERIC(10, 2) NOT NULL CHECK (pending_exchange_amount >= 0),

    -- Arqueo calculado. `difference` puede ser negativa (faltante), de ahi que
    -- sea la unica columna monetaria sin CHECK de no negatividad.
    expected_cash NUMERIC(10, 2) NOT NULL,
    counted_cash NUMERIC(10, 2) NOT NULL CHECK (counted_cash >= 0),
    difference NUMERIC(10, 2) NOT NULL,

    notes VARCHAR(500),

    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,

    -- Un unico cierre por taller y fecha operativa. La comprobacion previa del
    -- servicio existe para dar un mensaje entendible, pero no sustituye a esta
    -- restriccion: dos peticiones simultaneas de cierre pasarian las dos por el
    -- `exists(...)` y solo esta linea impide que se persistan dos snapshots.
    CONSTRAINT uq_cash_closure_workshop_business_date
        UNIQUE (workshop_id, business_date),

    FOREIGN KEY (workshop_id) REFERENCES workshop(id),
    FOREIGN KEY (closed_by) REFERENCES employee(id)
);

-- Las lecturas historicas —por fecha concreta, por rango y el resumen— filtran
-- siempre por taller y ordenan por fecha operativa. El UNIQUE de arriba ya crea
-- el indice (workshop_id, business_date) que las sirve, asi que NO se anade otro
-- indice para lo mismo.
--
-- `closed_by` si necesita el suyo: sin el, borrar o reasignar un empleado obliga
-- a PostgreSQL a recorrer la tabla entera para validar la FK, que es la misma
-- razon por la que V24 indexo `sale_action.performed_by`.
CREATE INDEX idx_cash_closure_closed_by ON cash_closure(closed_by);
