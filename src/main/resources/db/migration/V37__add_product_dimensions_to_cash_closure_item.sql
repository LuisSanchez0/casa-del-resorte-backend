-- ═══════════════════════════════════════════════════════════════════════════
-- Dimensiones comerciales del articulo dentro del cierre: familia, marca y
-- modelo, COPIADAS al confirmar.
--
-- El cierre ya guarda que se vendio producto a producto, pero el taller no
-- pregunta por producto: pregunta "cuantas hojas de resorte Toyota Hilux
-- vendimos y cuanto generaron". Eso es una fila por FAMILIA + MARCA + MODELO,
-- y `cash_closure_item` no tenia con que responderlo.
--
-- Por que se COPIAN y no se deducen del catalogo al leer:
--
--   `cash_closure_item.product_id` no tiene FK y apunta al catalogo VIVO.
--   Resolver la familia en el momento de descargar el PDF haria que renombrar
--   una marca, mover un producto de familia o corregir un modelo reescribiera
--   un documento ya firmado. Es exactamente lo que V33 evito con `item_name` e
--   `item_type`, y por la misma razon se hace aqui con las tres dimensiones.
--
-- Se guardan id Y nombre de cada dimension:
--
--   el id     agrupa. Dos familias homonimas son dos grupos, no uno.
--   el nombre imprime. Sin el, un historico quedaria ilegible si el catalogo
--             cambia, y con el el PDF de hace un ano dice lo que dijo.
--
-- ═══════════════════════════════════════════════════════════════════════════


-- ── Las columnas ───────────────────────────────────────────────────────────
--
-- TODAS nullable, y cada NULL significa una cosa distinta:
--
--   family_id NULL   el cierre es ANTERIOR a esta migracion y su producto ya
--                    no existe en el catalogo. El dato no se registro y no hay
--                    de donde sacarlo.
--   brand_id  NULL   la familia NO trabaja con modelo (`family.requires_model`
--                    en false), asi que el producto no tiene marca. NO es un
--                    dato que falte: es que no aplica.
--   model_id  NULL   lo mismo.
--
-- `family_id` NO se declara NOT NULL aunque `product.family_id` lo sea: esta
-- tabla YA tiene filas de cierres confirmados, y forzar un valor obligaria a
-- inventar una familia para el producto que se borro. Un NULL honesto es mejor
-- que una familia falsa dentro de un documento historico.
--
-- Sin FK a `family`, `brand` ni `model`, igual que `product_id`: es historia
-- copiada, y una FK impediria borrar del catalogo una marca que un cierre de
-- 2024 menciona.

ALTER TABLE cash_closure_item
    ADD COLUMN family_id UUID,
    ADD COLUMN family_name VARCHAR(50),
    ADD COLUMN brand_id UUID,
    ADD COLUMN brand_name VARCHAR(50),
    ADD COLUMN model_id UUID,
    ADD COLUMN model_name VARCHAR(50);

-- Marca y modelo van SIEMPRE juntos y siempre con su nombre. En este dominio
-- la marca se alcanza a traves del modelo (`product.model_id` -> `model.brand_id`,
-- que es NOT NULL), asi que no existe un articulo con marca y sin modelo ni al
-- reves. Un id sin su nombre seria una fila que agrupa pero no se puede
-- imprimir.
ALTER TABLE cash_closure_item
    ADD CONSTRAINT ck_cash_closure_item_family_named CHECK (
        (family_id IS NULL AND family_name IS NULL)
        OR (family_id IS NOT NULL AND family_name IS NOT NULL)
    ),
    ADD CONSTRAINT ck_cash_closure_item_brand_named CHECK (
        (brand_id IS NULL AND brand_name IS NULL)
        OR (brand_id IS NOT NULL AND brand_name IS NOT NULL)
    ),
    ADD CONSTRAINT ck_cash_closure_item_model_named CHECK (
        (model_id IS NULL AND model_name IS NULL)
        OR (model_id IS NOT NULL AND model_name IS NOT NULL)
    ),
    ADD CONSTRAINT ck_cash_closure_item_brand_with_model CHECK (
        (brand_id IS NULL) = (model_id IS NULL)
    );


-- ── Los cierres que ya existian ────────────────────────────────────────────
--
-- Se rellenan desde el catalogo ACTUAL, que es la unica fuente que hay: cuando
-- esos cierres se confirmaron, estas columnas no existian y nadie copio nada.
--
-- ═══ ESTO ES MEJOR ESFUERZO, NO HISTORIA EXACTA ═══════════════════════════
--
-- Hay que decirlo sin rodeos, porque las dos mitades del historico NO tienen la
-- misma calidad:
--
--   cierre confirmado DESPUES de V37
--     -> familia, marca y modelo se copian EN EL INSTANTE DE CONFIRMAR.
--        Son exactos y son inmutables: editar el catalogo despues no los toca.
--
--   cierre confirmado ANTES de V37
--     -> familia, marca y modelo se reconstruyen AQUI, con lo que el catalogo
--        dice EN EL MOMENTO DE EJECUTAR ESTA MIGRACION.
--
-- La consecuencia del segundo caso, dicha entera: si un producto se renombro, o
-- cambio de familia, o su modelo se corrigio ANTES de que esta migracion
-- corriera, lo que queda guardado es la etiqueta de ese momento y NO la que
-- tenia el dia en que se vendio. Esa etiqueta original no se registro en
-- ninguna parte —las columnas no existian— y no hay forma de deducirla: ni de
-- `sale_detail`, ni del catalogo, ni del propio cierre.
--
-- No es un defecto de los snapshots posteriores a V37, que si son exactos. Es
-- el limite de lo que se puede reconstruir hacia atras, y se documenta en vez de
-- presentarse como historia fiel.
--
-- Se rellena igualmente porque la alternativa es peor: dejar todo el historico
-- anterior sin agrupar. Lo que NO se hace es inventar nombres.
--
-- Un producto borrado del catalogo deja las seis columnas en NULL. El reporte
-- agrupa esas filas aparte, las nombra SIN CLASIFICAR y mantiene su importe
-- dentro del total: el dato que falta es la etiqueta, no el dinero.
--
-- UNA sola sentencia con sus joins, no una consulta por fila.

UPDATE cash_closure_item item
SET family_id   = family.id,
    family_name = family.name,
    brand_id    = brand.id,
    brand_name  = brand.name,
    model_id    = model.id,
    model_name  = model.name
FROM product
JOIN family ON family.id = product.family_id
LEFT JOIN model ON model.id = product.model_id
LEFT JOIN brand ON brand.id = model.brand_id
WHERE product.id = item.product_id;


-- El reporte agrupado se pide SIEMPRE por cierre y agrupa por las tres
-- dimensiones. El indice arranca por `cash_closure_id` porque ese es el filtro;
-- las otras tres columnas lo convierten en un indice de cobertura para la
-- agrupacion, que evita releer la tabla fila a fila.
--
-- El UNIQUE existente (cash_closure_id, product_id) NO sirve para esto: su
-- segunda columna es justo la que el reporte NO agrupa.
CREATE INDEX idx_cash_closure_item_dimensions
    ON cash_closure_item (cash_closure_id, family_id, brand_id, model_id);
