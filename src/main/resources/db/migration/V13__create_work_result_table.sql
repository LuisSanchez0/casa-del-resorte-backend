CREATE TABLE work_result (
    id UUID PRIMARY KEY,
    workshop_id UUID NOT NULL,
    job_id UUID,
    item_id UUID,
    status VARCHAR(25) NOT NULL CHECK (status IN ('PENDING', 'SOLD')),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    FOREIGN KEY (workshop_id) REFERENCES workshop(id),
    FOREIGN KEY (job_id) REFERENCES job(id),
    FOREIGN KEY (item_id) REFERENCES inventory_item(id),
    CHECK (
        (job_id IS NOT NULL AND item_id IS NULL)
        OR
        (job_id IS NULL AND item_id IS NOT NULL)
    )
);

CREATE INDEX idx_work_result_workshop_status
    ON work_result(workshop_id, status);

CREATE INDEX idx_work_result_job
    ON work_result(job_id);

CREATE INDEX idx_work_result_item
    ON work_result(item_id);

CREATE UNIQUE INDEX uk_work_result_pending_job
    ON work_result(workshop_id, job_id)
    WHERE job_id IS NOT NULL AND status = 'PENDING';

CREATE UNIQUE INDEX uk_work_result_pending_item
    ON work_result(workshop_id, item_id)
    WHERE item_id IS NOT NULL AND status = 'PENDING';
