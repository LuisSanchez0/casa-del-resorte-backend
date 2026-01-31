CREATE TABLE brand (
    id UUID PRIMARY KEY,
    name VARCHAR(50) UNIQUE NOT NULL,
    logo_key TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE TABLE model (
    id UUID PRIMARY KEY,
    brand_id UUID NOT NULL,
    name VARCHAR(50) NOT NULL,
    year SMALLINT NOT NULL CHECK (year >= 1900 AND year <= date_part('year', now()) + 1),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    UNIQUE(brand_id, name, year),
    FOREIGN KEY (brand_id) REFERENCES brand(id)
);

CREATE TABLE family (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    code VARCHAR(25) UNIQUE NOT NULL,
    name VARCHAR(50) UNIQUE NOT NULL,
    requires_model BOOLEAN NOT NULL,
    category VARCHAR(20) NOT NULL CHECK (category in ('SPECIAL', 'NORMAL', 'SERVICE')),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE TABLE attribute (
    id UUID PRIMARY KEY,
    family_id UUID NOT NULL,
    name VARCHAR(50) NOT NULL,
    data_type VARCHAR(10) NOT NULL CHECK (data_type IN ('NUMBER','STRING','BOOLEAN')),
    is_required boolean NOT NULL, 
    UNIQUE (family_id, name),
    FOREIGN KEY (family_id) REFERENCES family(id)
);

CREATE TABLE product (
    id UUID PRIMARY KEY,
    sku VARCHAR(25) UNIQUE NOT NULL,
    family_id UUID NOT NULL,
    name VARCHAR(100) NOT NULL,
    attributes jsonb NOT NULL,
    model_id UUID,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    FOREIGN KEY (family_id) REFERENCES family(id),
    FOREIGN KEY (model_id) REFERENCES model(id)
);

CREATE TABLE inventory (
    id UUID PRIMARY KEY,
    product_id UUID NOT NULL,
    workshop_id UUID NOT NULL,
    price NUMERIC(10, 2) NOT NULL CHECK (price >= 0),
    cost NUMERIC(10, 2) NOT NULL CHECK (cost >= 0),
    min_threshold SMALLINT NOT NULL CHECK (min_threshold >= 0),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    UNIQUE(product_id, workshop_id),
    FOREIGN KEY (product_id) REFERENCES product(id),
    FOREIGN KEY (workshop_id) REFERENCES workshop(id)
);

CREATE TABLE quantity_inventory (
    id UUID PRIMARY KEY,
    inventory_id UUID UNIQUE NOT NULL,
    quantity SMALLINT NOT NULL CHECK (quantity >= 0),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    FOREIGN KEY (inventory_id) REFERENCES inventory(id)
);

CREATE TABLE unit_inventory (
    id UUID PRIMARY KEY,
    inventory_id UUID NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('AVAILABLE', 'CONSUMED', 'SOLD', 'UNAVAILABLE')),
    parent_id UUID,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    FOREIGN KEY (inventory_id) REFERENCES inventory(id),
    FOREIGN KEY (parent_id) REFERENCES inventory_unit(id)
);