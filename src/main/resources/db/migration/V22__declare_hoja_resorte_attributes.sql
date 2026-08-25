-- V21 declaraba `cantidadColochos` en las familias SPECIAL que YA tenian
-- `tieneOrificio`, y ese atributo no lo crea ninguna migracion: se da de alta por
-- la API. En una instalacion limpia V21 corre antes de que exista y no declara
-- nada, dejando la familia sin el atributo.
--
-- Aqui se declaran los tres atributos que la transformacion SPECIAL necesita,
-- apuntando a la familia por su identidad de dominio en vez de por lo que ya
-- tenga configurado. Se aceptan dos claves porque no coinciden entre entornos:
-- V6 siembra la familia con code 'HR', mientras que la base de trabajo tiene la
-- misma familia con code 'HOJA_RESORTE'; el nombre sí es igual en ambas.
--
-- Idempotente: `attribute` tiene UNIQUE(family_id, name) y cada insercion
-- comprueba antes que el atributo no exista con ninguna grafia, de modo que la
-- migracion no duplica lo que V21 o la API ya crearon.

INSERT INTO attribute (id, family_id, name, data_type, is_required)
SELECT
    gen_random_uuid(),
    target.id,
    declared.name,
    declared.data_type,
    declared.is_required
FROM (
    SELECT id
    FROM family
    WHERE category = 'SPECIAL'
      AND (code = 'HR' OR name = 'HOJA DE RESORTE')
) AS target
CROSS JOIN (
    VALUES
        ('largo', 'NUMBER', true),
        ('tieneOrificio', 'BOOLEAN', true),
        ('cantidadColochos', 'NUMBER', false)
) AS declared(name, data_type, is_required)
WHERE NOT EXISTS (
    SELECT 1
    FROM attribute existing
    WHERE existing.family_id = target.id
      AND LOWER(existing.name) = LOWER(declared.name));
