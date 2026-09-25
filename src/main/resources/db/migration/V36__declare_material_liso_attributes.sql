-- Esquema de transformacion COMPLETO y COMPARTIDO por MATERIAL LISO y HOJA DE
-- RESORTE.
--
--   largo             NUMBER   obligatorio
--   tieneOrificio     BOOLEAN  obligatorio
--   cantidadColochos  NUMBER   opcional
--   largoIzquierdo    NUMBER   opcional (ver "obligatoriedad condicional")
--   largoDerecho      NUMBER   opcional (ver "obligatoriedad condicional")
--
-- Es la configuracion de dominio que hace posible la regla "material liso ->
-- hoja de resorte": las dos familias describen la pieza con el MISMO
-- vocabulario, asi que una pieza de material liso puede acabar siendo una hoja.
--
-- <h2>Por que hace falta</h2>
--
-- MATERIAL LISO no recibia ningun atributo de ninguna migracion —se daban de
-- alta a mano por la API—, de modo que en una instalacion limpia las reglas
-- dirigidas por atributos quedaban inactivas para ella:
--
--   * TransformationLengthValidator.applies() era false, asi que una pieza de
--     material liso no tenia techo de largo;
--   * validateDeclaredAttributes no tenia contra que comparar;
--   * la cantidad de colochos no existia para ella.
--
-- Y `largoIzquierdo` / `largoDerecho` no los declaraba NINGUNA de las dos
-- familias (V22 solo dio a la hoja largo, tieneOrificio y cantidadColochos), de
-- modo que HoleMeasurementValidator.applies() era false en las dos y la regla de
-- medidas con orificio estaba dormida en la configuracion real. Declararlos aqui
-- es lo que la activa, sin que el validador tenga que conocer ninguna familia
-- por su nombre: su unica condicion sigue siendo que la familia declare el
-- conjunto completo con el tipo correcto.
--
-- <h2>Obligatoriedad condicional de los dos lados</h2>
--
-- `largoIzquierdo` y `largoDerecho` se declaran con is_required = FALSE, y eso
-- NO los hace opcionales en el dominio: cuando hay orificio siguen siendo
-- obligatorios y mayores que cero, y su suma debe igualar exactamente el largo.
-- Lo decide HoleMeasurementValidator, no esta columna.
--
-- La arquitectura ya esta montada asi a proposito:
--
--   * ProductServiceImpl solo exige un atributo obligatorio ausente cuando
--     `!HoleMeasurementValidator.ownsRequiredness(family, key)`, y esa funcion
--     devuelve true justo para estos dos en cuanto la familia declara el
--     conjunto completo. La columna queda, por contrato, ignorada para ellos.
--   * El formulario dinamico del frontend hace lo mismo:
--     `isRequired && !isConditionalSide`, y ademas oculta los dos campos
--     mientras `tieneOrificio` sea false.
--
-- Poner TRUE aqui seria escribir en el esquema una regla que el dominio no
-- tiene ("siempre obligatorios") y que hoy nadie leeria; si alguna vez alguien
-- leyera `isRequired` sin pasar por `ownsRequiredness`, TRUE rechazaria toda
-- pieza sin orificio y FALSE degrada sin romper nada. Es el mismo criterio con
-- el que V21 declaro `cantidadColochos`: lo que los productos existentes no
-- tienen no puede volverse obligatorio de golpe.
--
-- <h2>Como se localizan las familias</h2>
--
-- Igual que en V22, y por el mismo motivo: el `code` NO coincide entre entornos
-- (V6 siembra 'ML' y 'HR'; la base de trabajo tiene la hoja como
-- 'HOJA_RESORTE') mientras que el nombre si. Se aceptan las dos claves estables
-- de cada familia.
--
-- <h2>Idempotencia</h2>
--
-- `attribute` tiene UNIQUE(family_id, name) y cada insercion comprueba antes que
-- el atributo no exista con ninguna grafia. Una instalacion que ya los tenga
-- dados de alta por la API no se toca: esta migracion solo ANADE lo que falta y
-- nunca reescribe el tipo ni la obligatoriedad de lo que ya hubiera.

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
      AND (code IN ('ML', 'MATERIAL_LISO') OR name = 'MATERIAL LISO'
        OR code IN ('HR', 'HOJA_RESORTE') OR name = 'HOJA DE RESORTE')
) AS target
CROSS JOIN (
    VALUES
        ('largo', 'NUMBER', true),
        ('tieneOrificio', 'BOOLEAN', true),
        ('cantidadColochos', 'NUMBER', false),
        -- Obligatorios solo con orificio; lo decide el validador, no la columna.
        ('largoIzquierdo', 'NUMBER', false),
        ('largoDerecho', 'NUMBER', false)
) AS declared(name, data_type, is_required)
WHERE NOT EXISTS (
    SELECT 1
    FROM attribute existing
    WHERE existing.family_id = target.id
      AND LOWER(existing.name) = LOWER(declared.name));
