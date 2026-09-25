-- ═══════════════════════════════════════════════════════════════════════════
-- Deshace V38: `cash_closure_item` vuelve a tener UNA fila por producto y deja
-- de guardar como se cobro cada articulo.
--
-- ── Por que se revierte ────────────────────────────────────────────────────
--
-- V38 anadio `payment_attribution` para poder dividir el resumen de productos
-- por forma de cobro. Esa columna creaba una SEGUNDA verdad sobre el mismo
-- dinero, y las dos se contradijeron en cuanto se miraron juntas:
--
--   cierre 0ea01f94-1458-4243-8d77-8c30efc81297, confirmado 14:43
--     cash_closure_payment_method  ->  Efectivo 1  Q510.00
--     cash_closure_item            ->  UNKNOWN     Q510.00
--
-- El cierre se habia confirmado ANTES de que V38 existiera (instalada a las
-- 15:05), asi que su relleno legacy marco la fila como "no se sabe" mientras el
-- desglose de cobros del mismo snapshot decia, correctamente, que se cobro
-- integro en efectivo. Los dos PDF del mismo dia se contradecian.
--
-- El diseno correcto es el que ya existia: el cierre guarda COMO entro el
-- dinero UNA sola vez, en `cash_closure_payment_method`, y los dos reportes lo
-- leen de ahi. El resumen de productos responde otra pregunta —QUE mercaderia
-- se vendio, agrupada por familia, marca y modelo— y no necesita repetir la
-- primera.
--
-- ── Por que V39 y no borrar V38 ────────────────────────────────────────────
--
-- Porque V38 YA ESTA APLICADA en al menos una base real. Borrar su script
-- dejaria una fila en `flyway_schema_history` sin migracion que la respalde, y
-- Flyway se negaria a arrancar. Editarla cambiaria su checksum, con el mismo
-- resultado. Una migracion aplicada no se reescribe: se compensa.
--
-- Sobre una base limpia, V38 y V39 se cancelan y el esquema final es el que
-- V37 dejo. Es un ida y vuelta visible en el historial, que es exactamente lo
-- que paso.
-- ═══════════════════════════════════════════════════════════════════════════


-- ── 1. Volver a UNA fila por producto ──────────────────────────────────────
--
-- Solo puede haber filas partidas en cierres confirmados ENTRE V38 y V39. Se
-- suman en la fila superviviente en vez de borrar las sobrantes: lo que se
-- deshace es el reparto, no el dinero.
--
-- La superviviente es la de `id` menor, que es la misma que sobrevive al DELETE
-- de abajo; sus dimensiones y su nombre son los mismos en todas las filas del
-- grupo, porque describen al producto y no al cobro.
--
-- Se identifica con `NOT EXISTS ... id <` y no con `MIN(id)`: PostgreSQL sabe
-- comparar uuid pero no tiene un agregado `min(uuid)`.

WITH merged AS (
    SELECT cash_closure_id,
           product_id,
           SUM(quantity) AS total_quantity,
           SUM(billed_amount) AS total_billed_amount
    FROM cash_closure_item
    GROUP BY cash_closure_id, product_id
    HAVING COUNT(*) > 1
)
UPDATE cash_closure_item item
SET quantity = merged.total_quantity,
    billed_amount = merged.total_billed_amount
FROM merged
WHERE item.cash_closure_id = merged.cash_closure_id
  AND item.product_id = merged.product_id
  AND NOT EXISTS (
      SELECT 1
      FROM cash_closure_item other
      WHERE other.cash_closure_id = item.cash_closure_id
        AND other.product_id = item.product_id
        AND other.id < item.id
  );

DELETE FROM cash_closure_item item
WHERE EXISTS (
    SELECT 1
    FROM cash_closure_item other
    WHERE other.cash_closure_id = item.cash_closure_id
      AND other.product_id = item.product_id
      AND other.id < item.id
);


-- ── 2. El indice de agrupacion, sin la forma de cobro ──────────────────────
--
-- Vuelve a ser el que creo V37: el reporte agrupa por familia, marca y modelo
-- dentro de un cierre, y ese es de nuevo el orden de sus columnas.

DROP INDEX idx_cash_closure_item_dimensions;

CREATE INDEX idx_cash_closure_item_dimensions
    ON cash_closure_item (cash_closure_id, family_id, brand_id, model_id);


-- ── 3. La unicidad original ────────────────────────────────────────────────
--
-- Un producto aparece UNA vez por cierre: la fila ya viene agregada. Se puede
-- restaurar porque el paso 1 acaba de garantizarlo.

ALTER TABLE cash_closure_item
    DROP CONSTRAINT uq_cash_closure_item,
    ADD CONSTRAINT uq_cash_closure_item UNIQUE (cash_closure_id, product_id);


-- ── 4. La columna ──────────────────────────────────────────────────────────
--
-- Se va entera, con su CHECK. No se conserva "por si acaso": una columna que
-- nadie lee en una tabla contable es una invitacion a que alguien la lea mal
-- dentro de un ano, y es justo lo que acaba de pasar.

ALTER TABLE cash_closure_item
    DROP COLUMN payment_attribution;
