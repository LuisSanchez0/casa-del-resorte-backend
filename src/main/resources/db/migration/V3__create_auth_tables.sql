CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TABLE role (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(50) NOT NULL UNIQUE,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE TABLE user_account (
    id UUID PRIMARY KEY,
    role_id UUID NOT NULL,
    employee_id UUID NOT NULL UNIQUE,
    username VARCHAR(50) NOT NULL UNIQUE,
    password TEXT NOT NULL,
    is_active BOOLEAN NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_user_role FOREIGN KEY (role_id) REFERENCES role(id),
    CONSTRAINT fk_user_employee FOREIGN KEY (employee_id) REFERENCES employee(id)
);