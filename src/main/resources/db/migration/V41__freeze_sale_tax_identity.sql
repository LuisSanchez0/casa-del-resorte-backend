-- Endurece la identidad fiscal de la venta introducida en V40.
--
-- 1. CF es una identidad FINAL. Solo existe en una venta emitida (o en el
--    historial que ya lo era): una venta abierta sin NIT es UNRESOLVED hasta
--    que se emite, y es en esa misma transaccion donde pasa a CF.
--
-- 2. Una venta abierta no es necesariamente una venta guardada para despues:
--    el carrito se persiste linea a linea desde que se abre. `saved_for_later`
--    distingue el borrador en curso (o abandonado) de la venta que el vendedor
--    guardo a proposito. Guardar una venta sin NIT exige su nombre de
--    referencia.
--
-- 3. Una venta con NIT congela el nombre y el NIT del cliente cuando deja de
--    estar abierta. Editar despues el maestro de clientes no reescribe el
--    historial. `customer_name` pasa a ser, en una venta con NIT ya cerrada, el
--    nombre congelado; `customer_nit`, el NIT congelado. Nunca se guarda un
--    "CF" literal como NIT.
--
-- V40 no se modifica: nunca se versiono, pero pudo aplicarse en alguna base, y
-- editarla provocaria un error de checksum.

ALTER TABLE sale
    ADD COLUMN customer_nit VARCHAR(13),
    ADD COLUMN saved_for_later BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE sale
    DROP CONSTRAINT ck_sale_tax_identity;

-- Antes de este cambio toda venta abierta aparecia como retomable. Las que
-- tienen identidad (cliente con NIT, o nombre de referencia) se conservan
-- asi; es una reconstruccion: el sistema nunca registro si se guardaron a
-- proposito o se abandonaron.
UPDATE sale
SET saved_for_later = TRUE
WHERE status = 'PENDING'
  AND tax_mode IN ('NIT', 'UNRESOLVED');

-- Una venta CF todavia abierta solo puede ser un carrito abandonado antes de
-- emitirse: vuelve a estar sin definir, como borrador sin guardar. Las CF ya
-- emitidas, revertidas o anuladas siguen siendo CF.
UPDATE sale
SET tax_mode = 'UNRESOLVED'
WHERE tax_mode = 'CF'
  AND status = 'PENDING';

-- Congelado de las ventas con NIT ya cerradas. Para el historial anterior a
-- esta migracion es el MEJOR ESFUERZO posible: se copia el cliente tal como
-- esta hoy, porque el sistema nunca guardo como estaba al emitir. De aqui en
-- adelante el servicio lo copia en el momento de emitir o anular.
UPDATE sale
SET customer_name = customer.name,
    customer_nit = customer.nit
FROM customer
WHERE customer.id = sale.customer_id
  AND sale.tax_mode = 'NIT'
  AND sale.status <> 'PENDING';

ALTER TABLE sale
    ADD CONSTRAINT ck_sale_customer_nit_length
        CHECK (customer_nit IS NULL OR LENGTH(customer_nit) BETWEEN 8 AND 13),
    -- Cada identidad con su forma exacta:
    --   NIT abierta     -> cliente registrado, todavia sin congelar
    --   NIT cerrada     -> cliente registrado y su nombre y NIT congelados
    --   CF              -> sin cliente ni NIT, y nunca abierta
    --   UNRESOLVED      -> sin cliente ni NIT, abierta o anulada; si se guardo
    --                      para despues, con nombre de referencia
    ADD CONSTRAINT ck_sale_tax_identity
        CHECK (
            (tax_mode = 'NIT'
                AND customer_id IS NOT NULL
                AND (
                    (status = 'PENDING'
                        AND customer_name IS NULL
                        AND customer_nit IS NULL)
                    OR
                    (status <> 'PENDING'
                        AND customer_name IS NOT NULL
                        AND customer_nit IS NOT NULL)
                ))
            OR
            (tax_mode = 'CF'
                AND customer_id IS NULL
                AND customer_nit IS NULL
                AND status <> 'PENDING')
            OR
            (tax_mode = 'UNRESOLVED'
                AND customer_id IS NULL
                AND customer_nit IS NULL
                AND status IN ('PENDING', 'CANCELLED')
                AND (NOT saved_for_later OR customer_name IS NOT NULL))
        );

-- "Mis ventas pendientes" lee las abiertas de un empleado separando las
-- guardadas de los borradores.
CREATE INDEX idx_sale_employee_open
    ON sale(employee_id, saved_for_later)
    WHERE status = 'PENDING';
