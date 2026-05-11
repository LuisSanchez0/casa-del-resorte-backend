CREATE TABLE daily_log (
    id UUID PRIMARY KEY,
    workshop_id UUID NOT NULL,
    employee_id UUID NOT NULL,
    description TEXT NOT NULL,
    performed_date DATE UNIQUE NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    FOREIGN KEY (workshop_id) REFERENCES workshop(id),
    FOREIGN KEY (employee_id) REFERENCES employee(id)
);