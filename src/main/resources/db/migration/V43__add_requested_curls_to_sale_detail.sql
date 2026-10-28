-- Lo que el vendedor decidio de los colochos de la pieza pedida, en la propia
-- linea de la venta.
--
--   requested_curls        TRUE  -> la pieza lleva colochos
--                          FALSE -> la pieza NO lleva colochos
--                          NULL  -> no se pregunto: linea anterior a esta regla,
--                                   o una pieza cuya familia no maneja colochos
--   requested_curl_count   cuantos, solo si lleva y se sabe (>= 1); NULL si
--                          lleva pero la cantidad quedo abierta
--
-- No vive en el producto: un producto del catalogo que no fija sus colochos
-- (y que puede tener unidades en stock) no cambia de identidad porque un
-- cliente pida "si, pero no se cuantos" o "sin colochos". Es lo pedido en ESA
-- linea. La cantidad que registre despues el Operador NO se escribe aqui: va
-- en la pieza fabricada.
--
-- Columnas nulas, sin valor por defecto: las lineas existentes quedan como
-- "no se pregunto" y nada se reinterpreta.

ALTER TABLE sale_detail
    ADD COLUMN requested_curls BOOLEAN,
    ADD COLUMN requested_curl_count SMALLINT,
    ADD CONSTRAINT ck_sale_detail_requested_curl_count
        CHECK (requested_curl_count IS NULL
            OR (requested_curls IS TRUE AND requested_curl_count >= 1));
