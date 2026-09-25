-- Identidad fiscal de la venta: con NIT, Consumidor Final (CF) o todavia sin
-- definir (UNRESOLVED), mas el nombre escrito en la propia venta.
--
-- Es una dimension distinta de `status`: una venta PENDING puede tener ya su
-- NIT elegido, o estar guardada para despues con solo un nombre de referencia.
--
-- `customer_name` NO duplica `customer.name`: solo existe cuando la venta no
-- factura a un Customer registrado. En UNRESOLVED es el nombre de referencia de
-- la venta pendiente; en CF, el nombre opcional del documento. Un CF nunca crea
-- un Customer, asi que `customer.nit` sigue siendo NOT NULL UNIQUE y nunca
-- guarda un "CF" literal.

ALTER TABLE sale
    ADD COLUMN tax_mode VARCHAR(20),
    ADD COLUMN customer_name VARCHAR(75);

-- Backfill: hasta ahora la API solo creaba ventas con un Customer, que siempre
-- tiene NIT. Una venta sin cliente solo pudo llegar por fuera de la API y el
-- cierre la imprime como "Mostrador": una venta sin identidad fiscal, que es
-- justo CF. Ninguna fila historica queda UNRESOLVED: ese estado exige un
-- nombre de referencia que ninguna venta anterior tiene, y nunca puede
-- aplicarse a una venta ya completada.
UPDATE sale
SET tax_mode = CASE
        WHEN customer_id IS NOT NULL THEN 'NIT'
        ELSE 'CF'
    END;

ALTER TABLE sale
    ALTER COLUMN tax_mode SET NOT NULL,
    ADD CONSTRAINT sale_tax_mode_check
        CHECK (tax_mode IN ('NIT', 'CF', 'UNRESOLVED')),
    ADD CONSTRAINT ck_sale_customer_name_not_blank
        CHECK (customer_name IS NULL OR btrim(customer_name) <> ''),
    -- Cada modo con su forma exacta, para que ninguna fila sea ambigua:
    --   NIT        -> un Customer registrado y ningun nombre suelto
    --   CF         -> sin Customer (el nombre es opcional)
    --   UNRESOLVED -> sin Customer, con nombre de referencia, y NUNCA
    --                 completada ni revertida: solo puede estar abierta o
    --                 anulada antes de facturarse.
    ADD CONSTRAINT ck_sale_tax_identity
        CHECK (
            (tax_mode = 'NIT'
                AND customer_id IS NOT NULL
                AND customer_name IS NULL)
            OR
            (tax_mode = 'CF'
                AND customer_id IS NULL)
            OR
            (tax_mode = 'UNRESOLVED'
                AND customer_id IS NULL
                AND customer_name IS NOT NULL
                AND status IN ('PENDING', 'CANCELLED'))
        );
