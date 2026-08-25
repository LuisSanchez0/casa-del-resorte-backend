-- Rol SECRETARY. Sigue exactamente la convencion de V4__seed_default_roles.sql:
-- el nombre es el identificador de negocio (VARCHAR UNIQUE en ingles y en
-- mayusculas) y el id lo genera la propia columna con uuid_generate_v4(), como
-- los cinco roles ya sembrados. La etiqueta "Secretaria" es cosa del frontend:
-- en base de datos no vive ningun texto de interfaz.
--
-- Se inserta SOLO este rol: no se tocan ADMIN, SELLER, CONTROL, OPERATOR ni
-- POLICE, cuyos UUID ya estan referenciados por user_account.role_id.
--
-- ON CONFLICT DO NOTHING hace la migracion idempotente frente a una base donde
-- alguien haya creado el rol a mano antes de aplicarla; sin el, Flyway quedaria
-- marcada como fallida por la violacion de UNIQUE(name).

INSERT INTO role (name, created_at, updated_at)
VALUES ('SECRETARY', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (name) DO NOTHING;
