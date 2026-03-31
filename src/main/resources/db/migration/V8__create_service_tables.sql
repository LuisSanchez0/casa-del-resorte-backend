CREATE TABLE service_details (
    id UUID PRIMARY KEY,
    product_id UUID NOT NULL,
    workshop_id UUID NOT NULL,
    price NUMERIC(10, 2) NOT NULL CHECK (price >= 0),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    UNIQUE(product_id, workshop_id),
    FOREIGN KEY (product_id) REFERENCES product(id),
    FOREIGN KEY (workshop_id) REFERENCES workshop(id)
);