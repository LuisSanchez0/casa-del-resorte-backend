CREATE TABLE sale (
    id UUID PRIMARY KEY,
    workshop_id UUID NOT NULL,
    employee_id UUID NOT NULL,
    performed_date TIMESTAMP NOT NULL,
    total NUMERIC(10, 2) NOT NULL CHECK total >= 0,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    FOREIGN KEY (workshop_id) REFERENCES workshop(id),
    FOREIGN KEY (employee_id) REFERENCES employee(id)
);

CREATE TABLE sale_detail (
    id UUID PRIMARY KEY,
    sale_id UUID NOT NULL,
    product_id UUID NOT NULL,
    quantity SMALLINT NOT NULL CHECK quantiy > 0,
    price NUMERIC(10,2) NOT NULL CHECK price >= 0,
    sub_total NUMERIC(10, 2) NOT NULL CHECK price >= 0,
    FOREIGN KEY (product_id) REFERENCES product(id),
    FOREIGN KEY (sale_id) REFERENCES sale(id)
);