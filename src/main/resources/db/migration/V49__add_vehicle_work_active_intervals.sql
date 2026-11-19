-- Tramos ACTIVOS del trabajo de UNA visita (vehicle_movement), no de la placa ni del mecanico.
--
-- El tiempo de trabajo efectivo es la SUMA de estos tramos. Una pausa no se guarda como tiempo:
-- es el hueco entre el fin de un tramo y el inicio del siguiente. work_started_at sigue siendo el
-- PRIMER inicio y work_finished_at el fin definitivo; pausar y reanudar no los tocan.
--
--   START             primer tramo, abierto al iniciar el trabajo (started_at = work_started_at).
--   RESUME            cada tramo abierto al reanudar despues de una pausa.
--   LEGACY_CARRYOVER  visita que ya estaba EN TRABAJO antes de V49 (sin tramos): su primera pausa
--                     o su fin escribe UN tramo cerrado desde su inicio real. Antes de V49 no se
--                     podia pausar, asi que ese tramo es el tiempo transcurrido sin registro de pausas.
--
-- Ninguna fila existente se modifica ni se rellena aqui. Las visitas finalizadas antes de V49 no
-- reciben tramos: su duracion sigue siendo work_finished_at - work_started_at y se informa como
-- "tiempo transcurrido historico (sin registro de pausas)", nunca como tiempo activo medido.
CREATE TABLE vehicle_work_interval (
    id UUID PRIMARY KEY,
    vehicle_movement_id UUID NOT NULL REFERENCES vehicle_movement(id),
    origin VARCHAR(20) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    ended_at TIMESTAMPTZ,
    started_by_user_id UUID REFERENCES user_account(id),
    ended_by_user_id UUID REFERENCES user_account(id),
    CONSTRAINT ck_vwi_origin CHECK (origin IN ('START', 'RESUME', 'LEGACY_CARRYOVER')),
    -- Un tramo nunca es negativo; uno de cero segundos es legitimo.
    CONSTRAINT ck_vwi_order CHECK (ended_at IS NULL OR ended_at >= started_at),
    -- Quien cerro el tramo existe exactamente cuando el tramo esta cerrado.
    CONSTRAINT ck_vwi_end_actor CHECK ((ended_at IS NULL) = (ended_by_user_id IS NULL)),
    -- START y RESUME siempre tienen actor; el traspaso historico hereda started_by de la visita (puede faltar).
    CONSTRAINT ck_vwi_start_actor CHECK (origin = 'LEGACY_CARRYOVER' OR started_by_user_id IS NOT NULL),
    -- El traspaso historico se escribe ya cerrado.
    CONSTRAINT ck_vwi_legacy_closed CHECK (origin <> 'LEGACY_CARRYOVER' OR ended_at IS NOT NULL)
);

-- A lo sumo UN tramo abierto por visita: dos sesiones nunca acumulan tiempo en paralelo.
CREATE UNIQUE INDEX uq_vwi_open_per_visit
    ON vehicle_work_interval(vehicle_movement_id) WHERE ended_at IS NULL;
-- A lo sumo UN primer tramo (START o traspaso historico) por visita.
CREATE UNIQUE INDEX uq_vwi_first_per_visit
    ON vehicle_work_interval(vehicle_movement_id) WHERE origin IN ('START', 'LEGACY_CARRYOVER');
-- Agregacion por visita (reporte y tarjetas) sin recorrer la tabla.
CREATE INDEX idx_vwi_visit_timeline
    ON vehicle_work_interval(vehicle_movement_id, started_at, id);

-- Integridad del registro de tramos, ademas del bloqueo FOR UPDATE de la visita que usa la aplicacion:
--   * no se borra nada y un tramo cerrado es inmutable; un UPDATE solo puede CERRAR un tramo abierto;
--   * solo hay tramos en visitas iniciadas, con mecanico, sin fin y sin salida;
--   * el primer tramo empieza exactamente en work_started_at y ninguno empieza antes;
--   * un tramo nuevo nunca se solapa con otro de la misma visita y RESUME exige un tramo anterior.
CREATE FUNCTION guard_vehicle_work_interval() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    visit RECORD;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'vehicle_work_interval no admite borrados' USING ERRCODE = 'restrict_violation';
    END IF;
    IF TG_OP = 'UPDATE' THEN
        IF OLD.ended_at IS NOT NULL OR NEW.ended_at IS NULL
           OR NEW.id <> OLD.id OR NEW.vehicle_movement_id <> OLD.vehicle_movement_id
           OR NEW.origin <> OLD.origin OR NEW.started_at <> OLD.started_at
           OR NEW.started_by_user_id IS DISTINCT FROM OLD.started_by_user_id THEN
            RAISE EXCEPTION 'Un tramo de trabajo solo puede cerrarse una vez y no cambia despues'
                USING ERRCODE = 'restrict_violation';
        END IF;
    END IF;

    -- Serializa con cualquier otra escritura de la misma visita (la aplicacion ya tiene este bloqueo).
    SELECT work_started_at, work_finished_at, exit, mechanic_user_id INTO visit
      FROM vehicle_movement WHERE id = NEW.vehicle_movement_id FOR UPDATE;
    IF visit.work_started_at IS NULL OR visit.mechanic_user_id IS NULL THEN
        RAISE EXCEPTION 'No hay tramos de trabajo sin inicio ni mecanico' USING ERRCODE = 'check_violation';
    END IF;
    IF visit.work_finished_at IS NOT NULL OR visit.exit IS NOT NULL THEN
        RAISE EXCEPTION 'El trabajo ya finalizo o el vehiculo ya salio' USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.started_at < visit.work_started_at
       OR (NEW.origin IN ('START', 'LEGACY_CARRYOVER') AND NEW.started_at <> visit.work_started_at) THEN
        RAISE EXCEPTION 'El tramo no puede empezar antes del primer inicio del trabajo' USING ERRCODE = 'check_violation';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF EXISTS (SELECT 1 FROM vehicle_work_interval o
                    WHERE o.vehicle_movement_id = NEW.vehicle_movement_id AND o.id <> NEW.id
                      AND (o.ended_at IS NULL OR o.ended_at > NEW.started_at)) THEN
            RAISE EXCEPTION 'El tramo se solapa con otro de la misma visita' USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.origin = 'RESUME' AND NOT EXISTS (SELECT 1 FROM vehicle_work_interval o
                    WHERE o.vehicle_movement_id = NEW.vehicle_movement_id) THEN
            RAISE EXCEPTION 'Solo se reanuda un trabajo que ya tuvo un tramo' USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_vwi_guard
    BEFORE INSERT OR UPDATE OR DELETE ON vehicle_work_interval
    FOR EACH ROW EXECUTE FUNCTION guard_vehicle_work_interval();

-- La linea de tiempo de la visita: primer inicio y fin son definitivos, el mecanico queda fijo
-- desde el inicio y un fin nunca deja un tramo abierto ni anterior a un tramo ya cerrado.
-- Solo mira transiciones futuras: ninguna fila existente se reescribe.
CREATE FUNCTION guard_vehicle_work_timeline() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.work_started_at IS NOT NULL AND NEW.work_started_at IS DISTINCT FROM OLD.work_started_at THEN
        RAISE EXCEPTION 'El primer inicio del trabajo no puede cambiar' USING ERRCODE = 'restrict_violation';
    END IF;
    IF OLD.work_finished_at IS NOT NULL AND NEW.work_finished_at IS DISTINCT FROM OLD.work_finished_at THEN
        RAISE EXCEPTION 'El fin del trabajo es definitivo' USING ERRCODE = 'restrict_violation';
    END IF;
    IF OLD.work_started_at IS NOT NULL AND NEW.mechanic_user_id IS DISTINCT FROM OLD.mechanic_user_id THEN
        RAISE EXCEPTION 'El mecanico quedo fijo al iniciar el trabajo' USING ERRCODE = 'restrict_violation';
    END IF;
    IF OLD.work_finished_at IS NULL AND NEW.work_finished_at IS NOT NULL
       AND EXISTS (SELECT 1 FROM vehicle_work_interval i
                    WHERE i.vehicle_movement_id = NEW.id
                      AND (i.ended_at IS NULL OR i.ended_at > NEW.work_finished_at)) THEN
        RAISE EXCEPTION 'El trabajo no puede finalizar con un tramo abierto o posterior al fin'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_vehicle_work_timeline
    BEFORE UPDATE OF work_started_at, work_finished_at, mechanic_user_id ON vehicle_movement
    FOR EACH ROW EXECUTE FUNCTION guard_vehicle_work_timeline();
