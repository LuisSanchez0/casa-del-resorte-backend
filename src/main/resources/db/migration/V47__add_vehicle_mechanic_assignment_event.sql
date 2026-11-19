-- Bitacora de asignaciones de mecanico por VISITA (vehicle_movement), no por placa ni por mecanico.
-- El mecanico vigente sigue siendo vehicle_movement.mechanic_user_id; aqui solo se agrega una fila
-- por cada asignacion real: de quien a quien, quien la hizo y cuando (reloj del servidor).
-- Las visitas anteriores a V47 no reciben eventos: no hay actor ni instante demostrable.
CREATE TABLE vehicle_mechanic_assignment_event (
    id UUID PRIMARY KEY,
    vehicle_movement_id UUID NOT NULL REFERENCES vehicle_movement(id),
    event_type VARCHAR(20) NOT NULL,
    previous_mechanic_user_id UUID REFERENCES user_account(id),
    new_mechanic_user_id UUID NOT NULL REFERENCES user_account(id),
    changed_by_user_id UUID NOT NULL REFERENCES user_account(id),
    changed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_vmae_event_type CHECK (event_type IN ('INITIAL_ASSIGNMENT', 'REASSIGNMENT')),
    CONSTRAINT ck_vmae_initial_without_previous
        CHECK (event_type <> 'INITIAL_ASSIGNMENT' OR previous_mechanic_user_id IS NULL),
    CONSTRAINT ck_vmae_real_change CHECK (previous_mechanic_user_id IS DISTINCT FROM new_mechanic_user_id)
);

-- Una sola asignacion inicial por visita. Los ciclos Juan -> Maria -> Juan siguen permitidos.
CREATE UNIQUE INDEX uq_vmae_initial_per_visit
    ON vehicle_mechanic_assignment_event(vehicle_movement_id) WHERE event_type = 'INITIAL_ASSIGNMENT';
-- Linea de tiempo paginada de una visita: mas recientes primero, desempate estable por id.
CREATE INDEX idx_vmae_visit_timeline
    ON vehicle_mechanic_assignment_event(vehicle_movement_id, changed_at DESC, id DESC);

-- Solo insercion: ninguna ruta de la aplicacion modifica ni borra eventos, y la base tampoco lo permite.
CREATE FUNCTION reject_vehicle_mechanic_assignment_event_change() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'vehicle_mechanic_assignment_event es de solo insercion' USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER trg_vmae_append_only
    BEFORE UPDATE OR DELETE ON vehicle_mechanic_assignment_event
    FOR EACH ROW EXECUTE FUNCTION reject_vehicle_mechanic_assignment_event_change();
