-- Each row is one visit. Historic rows deliberately remain unassigned/untimed.
ALTER TABLE vehicle_movement
 ADD COLUMN mechanic_user_id UUID REFERENCES user_account(id),
 ADD COLUMN work_started_at TIMESTAMPTZ,
 ADD COLUMN work_finished_at TIMESTAMPTZ,
 ADD COLUMN started_by UUID REFERENCES user_account(id),
 ADD COLUMN finished_by UUID REFERENCES user_account(id),
 ADD CONSTRAINT ck_vehicle_work_order CHECK (work_finished_at IS NULL OR
    (work_started_at IS NOT NULL AND work_finished_at >= work_started_at)),
 ADD CONSTRAINT ck_vehicle_work_mechanic CHECK (work_started_at IS NULL OR mechanic_user_id IS NOT NULL),
 ADD CONSTRAINT ck_vehicle_work_exit CHECK (exit IS NULL OR work_started_at IS NULL OR work_finished_at IS NOT NULL);
-- The report filters admission dates in the authenticated workshop, with stable pagination.
CREATE INDEX idx_vehicle_work_report ON vehicle_movement(workshop_id, entry DESC, id DESC);
