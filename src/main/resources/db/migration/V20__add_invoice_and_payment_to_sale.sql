-- Extiende la venta con: placa opcional del vehiculo, tipo de documento emitido y
-- la dimension de cobro (estado, metodo y fecha), mas la descripcion fiscal
-- alterna del detalle.
--
-- Todas las columnas entran como NULL o con DEFAULT para no romper las ventas ya
-- registradas. `payment_status` es la unica NOT NULL y se rellena por backfill:
-- antes de esta migracion no existia el credito, asi que una venta COMPLETED solo
-- podia estar cobrada, y una venta abierta todavia no.

ALTER TABLE sale
    ADD COLUMN plate VARCHAR(10),
    ADD COLUMN invoice_type VARCHAR(25)
        CHECK (invoice_type IN ('INVOICE', 'EXCHANGE_INVOICE')),
    ADD COLUMN payment_status VARCHAR(25),
    ADD COLUMN payment_method VARCHAR(25)
        CHECK (payment_method IN ('CASH', 'CARD', 'TRANSFER')),
    ADD COLUMN paid_at TIMESTAMP;

UPDATE sale
SET payment_status = CASE
        WHEN status = 'COMPLETED' THEN 'PAID'
        ELSE 'PENDING'
    END,
    invoice_type = CASE
        WHEN status = 'COMPLETED' THEN 'INVOICE'
        ELSE NULL
    END,
    paid_at = CASE
        WHEN status = 'COMPLETED' THEN completed_at
        ELSE NULL
    END;

ALTER TABLE sale
    ALTER COLUMN payment_status SET NOT NULL,
    ALTER COLUMN payment_status SET DEFAULT 'PENDING',
    ADD CONSTRAINT sale_payment_status_check
        CHECK (payment_status IN ('PENDING', 'PAID'));

-- Una venta pendiente de cobro no puede tener metodo ni fecha de pago, y una
-- cobrada tiene que tener fecha. Evita estados a medias imposibles de leer.
--
-- No se exige `payment_method` en las cobradas a proposito: las ventas anteriores
-- a esta migracion se cobraron —no existia el credito— pero el sistema nunca
-- registro COMO, y rellenarlas con un metodo inventado falsearia el historial
-- contable. De aqui en adelante el servicio siempre lo exige al facturar, asi que
-- el NULL solo puede provenir de esas ventas historicas.
ALTER TABLE sale
    ADD CONSTRAINT ck_sale_payment_consistency
        CHECK (
            (payment_status = 'PAID'
                AND paid_at IS NOT NULL)
            OR
            (payment_status = 'PENDING'
                AND payment_method IS NULL
                AND paid_at IS NULL)
        );

ALTER TABLE sale_detail
    ADD COLUMN invoice_description VARCHAR(100);

CREATE INDEX idx_sale_workshop_payment_status
    ON sale(workshop_id, payment_status);
