-- ═══════════════════════════════════════════════════════════════════════════
-- Forma de cobro del articulo dentro del cierre, COPIADA al confirmar.
--
-- El resumen de productos ya agrupa por familia + marca + modelo. Lo que falta
-- es separar esos grupos por como entro el dinero —efectivo, tarjeta,
-- transferencia/deposito— con la MISMA semantica que el cierre de caja de
-- siempre, que desglosa por `sale.payment_method`.
--
-- `cash_closure_item` no podia responderlo: guarda producto, cantidad, importe
-- y dimensiones, pero nada sobre el cobro. Y no se puede deducir al descargar
-- el PDF, por la misma razon que V37 copio la familia: `product_id` y la venta
-- de origen apuntan a datos VIVOS, y cobrar manana una cambiaria de hoy
-- reescribiria un documento ya firmado.
--
-- ── Por que una columna en la fila y no una tabla de reparto ───────────────
--
-- Porque en este dominio una venta tiene UNA forma de pago: `sale.payment_method`
-- es una columna escalar, no una coleccion, y no existe ninguna tabla de
-- pagos parciales (ver V20, que la creo, y el esquema entero, que no tiene
-- `sale_payment`). Una linea vendida pertenece ENTERA a una sola via de cobro,
-- asi que no hay nada que repartir entre metodos y una tabla hija solo anadiria
-- un sitio mas desde el que el mismo dinero podria contradecirse.
--
-- Si algun dia existieran los pagos mixtos, ESTA columna dejaria de bastar y
-- haria falta el reparto. Hoy seria una abstraccion para un caso que el modelo
-- no puede producir.
--
-- ── Lo que cambia: el GRANO de la fila ────────────────────────────────────
--
--   antes   una fila por (cierre, producto)
--   ahora   una fila por (cierre, producto, forma de cobro)
--
-- El mismo perno vendido por la manana en efectivo y por la tarde con tarjeta
-- pasa de una fila a dos. SE PARTE LA FILA, NO EL DINERO: la suma de
-- `billed_amount` y de `quantity` de un cierre es exactamente la misma antes y
-- despues, y por eso `cash_closure.items_billed_amount` no se toca aqui.
--
-- Quien necesita el articulo entero lo vuelve a sumar por `product_id`:
-- `CashClosureMapper.toResponse` para el cierre del dia —cuyo PDF sigue
-- enseñando una linea por producto, sin cambio alguno— y `findMonthlyItems`
-- para el mes, que ya agrupaba asi.
-- ═══════════════════════════════════════════════════════════════════════════


-- ── La columna ─────────────────────────────────────────────────────────────
--
-- Entra NULLABLE, se rellena y solo despues se exige. Al reves fallaria: la
-- tabla ya tiene filas de cierres confirmados y no hay DEFAULT honesto que
-- ponerles.
--
-- Los cinco valores, y por que son cinco y no tres:
--
--   CASH / CARD / TRANSFER  la venta estaba COBRADA al cerrar, por esa via.
--                           Son los mismos tres de `sale.payment_method`.
--   PENDING                 emitida en el periodo y aun sin cobrar al cerrar.
--                           Es el caso de la factura cambiaria: aporta su
--                           mercaderia al resumen del dia porque se facturo,
--                           pero no aporta un quetzal a ninguna caja. Meterla
--                           en efectivo para dejar la tabla con tres secciones
--                           seria inventar un cobro que no ocurrio.
--   UNKNOWN                 no se sabe. Dos origenes reales: los cierres
--                           anteriores a esta migracion (abajo) y las ventas
--                           anteriores a V20, cobradas de verdad pero sin que
--                           el sistema registrara COMO —`sale.payment_method`
--                           en NULL, que V20 dejo explicitamente permitido—.
--
-- NO es una lista de formas de pago ampliada: PENDING y UNKNOWN son estados de
-- reconciliacion y el reporte los imprime aparte, nunca como una cuarta caja.

ALTER TABLE cash_closure_item
    ADD COLUMN payment_attribution VARCHAR(20);


-- ── Los cierres que ya existian ────────────────────────────────────────────
--
-- ═══ NO SE RECONSTRUYE. SE DECLARA DESCONOCIDO ════════════════════════════
--
-- Se podria intentar deducir la forma de cobro releyendo `sale_detail` y `sale`
-- por la fecha operativa del cierre. Seria un error, y conviene decir por que
-- en vez de dejarlo a la intuicion:
--
--   1. La fila historica YA VIENE AGREGADA por producto. Si ese producto se
--      vendio ese dia en dos ventas con formas distintas, repartir su importe
--      exigiria volver al detalle... que es dato VIVO.
--   2. Ese dato vivo YA CAMBIO. Una cambiaria que estaba pendiente cuando se
--      firmo el cierre puede llevar meses cobrada; leer `sale` hoy diria
--      "TARJETA" donde el documento de aquel dia decia, con razon, "sin
--      cobrar". Reescribiria la historia con la respuesta de otro dia.
--   3. Una venta revertida despues, o un detalle editado, desplazaria importes
--      entre secciones de un papel ya archivado.
--
-- Entre una etiqueta inventada y un "no se sabe" honesto, el cierre de caja
-- elige siempre lo segundo. Es la misma decision que V37 tomo con los productos
-- borrados del catalogo: se pierde la ETIQUETA, nunca el dinero.
--
-- Consecuencia, dicha entera:
--
--   cierre confirmado DESPUES de V38
--     -> atribucion EXACTA, congelada en el instante de confirmar. Sus
--        secciones de efectivo, tarjeta y transferencia son ciertas.
--
--   cierre confirmado ANTES de V38
--     -> TODAS sus filas en UNKNOWN. El resumen de ese dia sigue agrupando por
--        familia, marca y modelo, con sus cantidades e importes intactos, bajo
--        un unico encabezado que dice que la forma de cobro no se registro.
--
-- UNA sola sentencia. Ni una consulta por fila.

UPDATE cash_closure_item
SET payment_attribution = 'UNKNOWN'
WHERE payment_attribution IS NULL;

ALTER TABLE cash_closure_item
    ALTER COLUMN payment_attribution SET NOT NULL,
    ADD CONSTRAINT ck_cash_closure_item_payment_attribution CHECK (
        payment_attribution IN ('CASH', 'CARD', 'TRANSFER', 'PENDING', 'UNKNOWN')
    );


-- ── La unicidad, con el grano nuevo ────────────────────────────────────────
--
-- `uq_cash_closure_item (cash_closure_id, product_id)` era correcta mientras la
-- fila era "un producto por cierre". Con el grano nuevo rechazaria justo lo que
-- ahora es legitimo —el mismo perno en efectivo y con tarjeta— asi que se
-- sustituye en vez de eliminarse: sin ella, un fallo de agregacion podria colar
-- el mismo producto y la misma via dos veces y DUPLICAR dinero dentro de un
-- cierre.
--
-- Las tres columnas son NOT NULL, de modo que la restriccion se aplica de
-- verdad a todas las filas. Con una columna nullable PostgreSQL considera
-- distintos dos NULL y la garantia se evaporaria en silencio.
ALTER TABLE cash_closure_item
    DROP CONSTRAINT uq_cash_closure_item,
    ADD CONSTRAINT uq_cash_closure_item
        UNIQUE (cash_closure_id, product_id, payment_attribution);


-- ── El indice de cobertura de V37, actualizado ─────────────────────────────
--
-- V37 lo creo para la agrupacion del resumen, que entonces era
-- (familia, marca, modelo) dentro de un cierre. Ahora la agrupacion empieza por
-- la forma de cobro, asi que el indice se recrea con esa columna delante de las
-- dimensiones: dejarlo como estaba seria mantener un indice cuyo comentario
-- describe una agrupacion que ya no existe.
--
-- Sigue arrancando por `cash_closure_id`, que es el unico filtro con el que se
-- pide este reporte.
DROP INDEX idx_cash_closure_item_dimensions;

CREATE INDEX idx_cash_closure_item_dimensions
    ON cash_closure_item (
        cash_closure_id, payment_attribution, family_id, brand_id, model_id);
