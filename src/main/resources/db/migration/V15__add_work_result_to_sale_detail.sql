ALTER TABLE sale_detail
    ADD COLUMN work_result_id UUID;

ALTER TABLE sale_detail
    ADD CONSTRAINT fk_sale_detail_work_result
        FOREIGN KEY (work_result_id)
            REFERENCES work_result(id);

CREATE UNIQUE INDEX uk_sale_detail_work_result
    ON sale_detail(work_result_id)
    WHERE work_result_id IS NOT NULL;
