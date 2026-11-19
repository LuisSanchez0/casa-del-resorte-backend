-- Nombres tal como eran al momento del evento y motivo de cada reasignacion real.
-- Solo agrega columnas y restricciones: ninguna fila existente se actualiza (el trigger de solo
-- insercion de V47 sigue activo y ADD COLUMN / ADD CONSTRAINT no lo disparan).
-- Los eventos anteriores a V48 quedan con estas columnas en NULL: no hay nombre historico ni motivo
-- demostrable, y no se reconstruyen desde los nombres actuales.
ALTER TABLE vehicle_mechanic_assignment_event
    ADD COLUMN previous_mechanic_name_at_event VARCHAR(201),
    ADD COLUMN new_mechanic_name_at_event VARCHAR(201),
    ADD COLUMN actor_name_at_event VARCHAR(201),
    ADD COLUMN reassignment_reason VARCHAR(300);

ALTER TABLE vehicle_mechanic_assignment_event
    -- Ningun texto vacio o de solo espacios.
    ADD CONSTRAINT ck_vmae_names_not_blank CHECK (
        (previous_mechanic_name_at_event IS NULL OR btrim(previous_mechanic_name_at_event) <> '')
        AND (new_mechanic_name_at_event IS NULL OR btrim(new_mechanic_name_at_event) <> '')
        AND (actor_name_at_event IS NULL OR btrim(actor_name_at_event) <> '')),
    ADD CONSTRAINT ck_vmae_reason_not_blank CHECK (reassignment_reason IS NULL OR btrim(reassignment_reason) <> ''),
    -- Un nombre anterior solo existe si hubo mecanico anterior.
    ADD CONSTRAINT ck_vmae_previous_name_has_id
        CHECK (previous_mechanic_name_at_event IS NULL OR previous_mechanic_user_id IS NOT NULL),
    -- La asignacion inicial nunca lleva motivo.
    ADD CONSTRAINT ck_vmae_reason_only_reassignment
        CHECK (reassignment_reason IS NULL OR event_type = 'REASSIGNMENT'),
    -- Un evento con nombres registrados (todos los nuevos) tiene actor y, si es reasignacion, motivo.
    -- Los eventos anteriores a V48 (sin nombres) quedan exentos y sin cambios.
    ADD CONSTRAINT ck_vmae_snapshot_has_actor
        CHECK (new_mechanic_name_at_event IS NULL OR actor_name_at_event IS NOT NULL),
    ADD CONSTRAINT ck_vmae_snapshot_reassignment_has_reason
        CHECK (new_mechanic_name_at_event IS NULL OR event_type <> 'REASSIGNMENT' OR reassignment_reason IS NOT NULL);
