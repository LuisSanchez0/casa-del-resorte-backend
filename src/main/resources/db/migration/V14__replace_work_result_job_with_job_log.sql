ALTER TABLE work_result
    DROP COLUMN job_id;

ALTER TABLE work_result
    ADD COLUMN job_log_id UUID;

ALTER TABLE work_result
    ADD CONSTRAINT fk_work_result_job_log
        FOREIGN KEY (job_log_id)
            REFERENCES job_log(id);

ALTER TABLE work_result
    ADD CONSTRAINT ck_work_result_source
        CHECK (
            (job_log_id IS NOT NULL AND item_id IS NULL)
            OR
            (job_log_id IS NULL AND item_id IS NOT NULL)
        );

DROP INDEX uk_work_result_pending_item;

DROP INDEX idx_work_result_item;

CREATE UNIQUE INDEX uk_work_result_job_log
    ON work_result(job_log_id)
    WHERE job_log_id IS NOT NULL;

CREATE UNIQUE INDEX uk_work_result_item
    ON work_result(item_id)
    WHERE item_id IS NOT NULL;
