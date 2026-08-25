-- Auditoria de por que una venta termino cancelada o revertida: motivo, quien
-- lo hizo y cuando, en una entidad separada de `sale` para no reescribir su
-- historial ni el de `sale_detail`.
--
-- Es 1 a 1 con `sale` (sale_id UNIQUE): en este modelo una venta solo puede
-- vivir uno de los dos eventos terminales auditables una unica vez.

CREATE TABLE sale_action (
    id UUID PRIMARY KEY,
    sale_id UUID NOT NULL UNIQUE,
    action_type VARCHAR(25) NOT NULL
        CHECK (action_type IN ('CANCEL_PENDING', 'REVERSE_COMPLETED')),
    reason VARCHAR(500) NOT NULL CHECK (LENGTH(TRIM(reason)) > 0),
    performed_by UUID NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    FOREIGN KEY (sale_id) REFERENCES sale(id),
    FOREIGN KEY (performed_by) REFERENCES employee(id)
);

CREATE INDEX idx_sale_action_performed_by ON sale_action(performed_by);

-- `REVERSED` es un evento distinto de `CANCELLED`: una venta cancelada nunca
-- llego a completarse, una revertida se completo y luego se deshizo. Sin este
-- valor, corregir una venta ya emitida solo podia reutilizar CANCELLED, que
-- mentiria sobre lo que en realidad paso.
ALTER TABLE sale
    DROP CONSTRAINT sale_status_check;

ALTER TABLE sale
    ADD CONSTRAINT sale_status_check
        CHECK (status IN ('COMPLETED', 'PENDING', 'CANCELLED', 'REVERSED'));
