-- El desarmado de un CONTAINER ahora registra cada componente liberado como un
-- TransformationItem con role = 'RELEASED', de modo que la trazabilidad viva en
-- la transformación y no en inventory_item.container_id.
-- La columna es VARCHAR(20), suficiente para 'RELEASED': solo hay que ampliar el
-- CHECK creado en V7, que restringía los roles a INPUT/OUTPUT/REMAINDER.

ALTER TABLE transformation_item
    DROP CONSTRAINT transformation_item_role_check;

ALTER TABLE transformation_item
    ADD CONSTRAINT transformation_item_role_check
    CHECK (role IN ('INPUT', 'OUTPUT', 'REMAINDER', 'RELEASED'));
