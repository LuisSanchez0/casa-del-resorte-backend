-- El año del modelo deja de ser un valor único y pasa a ser el intervalo de
-- años a los que aplica: "Corolla 2020" y "Corolla 2020-2025" son la misma
-- clase de dato, y con una sola columna el segundo no se podía representar.
--
-- El intervalo se guarda normalizado en dos enteros. Un modelo de un solo año
-- es el intervalo cerrado [2020, 2020], no un caso especial: así toda consulta
-- —incluida la de "qué modelos cubren 2023"— es la misma comparación para los
-- dos casos, y la cadena "2020-2025" se queda donde le corresponde, en la
-- presentación.
ALTER TABLE model ADD COLUMN year_from INTEGER;
ALTER TABLE model ADD COLUMN year_to INTEGER;

-- Cada fila existente vale exactamente para su año, así que el intervalo
-- equivalente tiene los dos extremos iguales. No se pierde ni una fila: es una
-- copia de la columna vieja sobre las dos nuevas.
UPDATE model SET year_from = year, year_to = year;

ALTER TABLE model ALTER COLUMN year_from SET NOT NULL;
ALTER TABLE model ALTER COLUMN year_to SET NOT NULL;

-- La unicidad del catálogo seguía siendo (marca, nombre, año); ahora la
-- identifica el intervalo completo. Se sustituye antes de soltar la columna
-- para no dejar la tabla ni un paso sin regla de duplicados.
ALTER TABLE model
    ADD CONSTRAINT uq_model_brand_name_years
    UNIQUE (brand_id, name, year_from, year_to);

ALTER TABLE model DROP CONSTRAINT model_brand_id_name_year_key;
ALTER TABLE model DROP COLUMN year;

-- Los dos extremos que ya acotaba el CHECK de V5 —1900 por abajo, el año
-- siguiente por arriba— más el invariante del intervalo. Sin la última
-- condición un "2025-2020" entraría en la base y ninguna consulta lo
-- devolvería nunca.
ALTER TABLE model
    ADD CONSTRAINT ck_model_year_range
    CHECK (year_from >= 1900
           AND year_to <= date_part('year', now()) + 1
           AND year_from <= year_to);
