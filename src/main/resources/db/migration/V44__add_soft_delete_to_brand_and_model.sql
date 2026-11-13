-- Eliminacion LOGICA de marcas y modelos.
--
-- Una marca o un modelo ya puede estar referenciado por productos, y a traves
-- de ellos por inventario, ventas, transformaciones y cierres de caja. Borrar
-- la fila romperia ese historial (o lo impediria la FK), asi que "Eliminar"
-- solo estampa `deleted_at`: la fila, su id, su nombre y todas sus relaciones
-- se conservan.
--
-- Es de UNA sola via. No hay columna editable `is_active` como en taller o
-- cuenta de usuario —que se activan y desactivan desde su formulario—: nada en
-- la API vuelve a poner `deleted_at` en NULL.
--
--   deleted_at NULL      -> vigente: se lista y se puede elegir
--   deleted_at NOT NULL  -> eliminada: fuera de listados y selectores; solo
--                           se sigue leyendo como parte de lo que ya la usa
--
-- Un modelo es ELEGIBLE solo si el y su marca estan vigentes. Eliminar una
-- marca no toca a sus modelos: cada uno conserva su propio estado y dejan de
-- ofrecerse por la marca.
--
-- Las restricciones UNIQUE existentes no cambian y siguen cubriendo las filas
-- eliminadas: el nombre de una marca eliminada (y la combinacion
-- marca/nombre/años de un modelo eliminado) no queda libre, para que ningun
-- alta nueva se confunda con la historica ni la "reviva".
--
-- Sin backfill: toda fila existente queda vigente.

ALTER TABLE brand
    ADD COLUMN deleted_at TIMESTAMP;

ALTER TABLE model
    ADD COLUMN deleted_at TIMESTAMP;
