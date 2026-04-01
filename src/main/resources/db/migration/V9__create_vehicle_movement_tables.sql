CREATE TABLE vehicle_movement (
    id UUID PRIMARY KEY,
    workshop_id UUID NOT NULL,
    plate VARCHAR(10) NOT NULL,
    entry TIMESTAMP NOT NULL,
    exit TIMESTAMP NULL,
    notes TEXT,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    FOREIGN KEY (workshop_id) REFERENCES workshop(id)
);