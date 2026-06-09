CREATE TABLE customer (
    id UUID PRIMARY KEY,
    nit VARCHAR(13) UNIQUE NOT NULL CHECK (LENGTH(nit) BETWEEN 8 AND 13),
    name VARCHAR(75) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE TABLE sale (
    id UUID PRIMARY KEY,
    workshop_id UUID NOT NULL,
    employee_id UUID NOT NULL,
    customer_id UUID,
    status VARCHAR(25) NOT NULL CHECK (status in ('COMPLETED', 'PENDING', 'CANCELLED')),
    completed_at TIMESTAMP,
    total NUMERIC(10, 2) NOT NULL CHECK (total >= 0),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    FOREIGN KEY (workshop_id) REFERENCES workshop(id),
    FOREIGN KEY (employee_id) REFERENCES employee(id),
    FOREIGN KEY (customer_id) REFERENCES customer(id)
);

CREATE TABLE sale_detail (
    id UUID PRIMARY KEY,
    sale_id UUID NOT NULL,
    product_id UUID NOT NULL,
    item_id UUID,
    quantity SMALLINT NOT NULL CHECK (quantity > 0),
    price NUMERIC(10,2) NOT NULL CHECK (price >= 0),
    sub_total NUMERIC(10, 2) NOT NULL CHECK (sub_total >= 0),
    FOREIGN KEY (product_id) REFERENCES product(id),
    FOREIGN KEY (sale_id) REFERENCES sale(id),
    FOREIGN KEY (item_id) REFERENCES inventory_item(id)
);