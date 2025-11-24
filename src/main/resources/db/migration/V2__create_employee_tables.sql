CREATE TABLE employee (
    id UUID PRIMARY KEY,
    workshop_id UUID NOT NULL,
    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100) NOT NULL,
    dpi VARCHAR(20) NOT NULL UNIQUE,
    birth_date DATE NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    FOREIGN KEY (workshop_id) REFERENCES workshop(id)
);

CREATE TABLE employee_phone_number (
    id UUID PRIMARY KEY,
    employee_id UUID NOT NULL,
    phone_number VARCHAR(20),
    UNIQUE(employee_id, phone_number),
    FOREIGN KEY (employee_id) REFERENCES employee(id)
);