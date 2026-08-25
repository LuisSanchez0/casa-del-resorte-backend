-- Declara `cantidadColochos` para las hojas de resorte, de modo que una
-- transformacion SPECIAL pueda cambiar solo el numero de colochos.
--
-- Se eligen las familias SPECIAL que ya declaran `tieneOrificio`: ese atributo es
-- lo que en este dominio distingue a una hoja transformable, y asi la migracion
-- no depende de que la familia se llame exactamente 'HOJA DE RESORTE' en cada
-- entorno. `attribute` tiene UNIQUE(family_id, name); el NOT EXISTS evita
-- duplicados y deja la migracion segura si el atributo ya se creo por la API.
--
-- is_required = false a proposito: los productos que ya existen no lo tienen y
-- exigirlo romperia su alta y su edicion. Los productos historicos NO se
-- modifican; la ausencia del atributo se sigue soportando.

INSERT INTO attribute (id, family_id, name, data_type, is_required)
SELECT
    gen_random_uuid(),
    family.id,
    'cantidadColochos',
    'NUMBER',
    false
FROM family
WHERE family.category = 'SPECIAL'
  AND EXISTS (
      SELECT 1
      FROM attribute existing
      WHERE existing.family_id = family.id
        AND LOWER(existing.name) = 'tieneorificio')
  AND NOT EXISTS (
      SELECT 1
      FROM attribute existing
      WHERE existing.family_id = family.id
        AND LOWER(existing.name) = 'cantidadcolochos');
