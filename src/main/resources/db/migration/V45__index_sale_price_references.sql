-- Indices para la referencia de precio de la caja (SaleLineGuidanceService).
--
-- Solo indices: ninguna columna nueva, ningun dato tocado. La guia de precio
-- lee lo que ya existe —el ultimo precio cobrado de una pieza en una venta
-- completada y el precio de catalogo de piezas comparables del taller— y esas
-- dos lecturas no tenian por donde entrar:
--
--   * `sale_detail` crece con cada linea vendida y no tenia ningun indice por
--     `product_id`: "ultimo precio cobrado de ESTA pieza" recorria la tabla
--     entera. Con el indice, la consulta baja por las lineas de una sola pieza
--     y se queda con la mas reciente del taller.
--
--   * Las piezas comparables se buscan por familia y modelo. `product` solo
--     estaba indexado por su clave y su SKU; el indice compuesto acota la
--     busqueda a las piezas de esa familia y ese modelo antes de cruzarlas con
--     el inventario del taller (UNIQUE(product_id, workshop_id)).
--
-- Sin CONCURRENTLY: Flyway corre cada migracion dentro de una transaccion, y
-- las tablas de este sistema son pequenas para un CREATE INDEX normal.

CREATE INDEX idx_sale_detail_product
    ON sale_detail(product_id);

CREATE INDEX idx_product_family_model
    ON product(family_id, model_id);
