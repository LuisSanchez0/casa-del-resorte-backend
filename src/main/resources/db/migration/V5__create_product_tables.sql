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
    id UUID PRIMARY KEY,
    code VARCHAR(25) UNIQUE NOT NULL,
    name VARCHAR(50) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE TYPE attr_data_type AS ENUM ('NUMBER', 'STRING', 'BOOLEAN');

CREATE TABLE attribute (
    id UUID PRIMARY KEY,
    family_id UUID NOT NULL,
    key VARCHAR(50) NOT NULL,
    data_type attr_data_type NOT NULL,
    required boolean NOT NULL, 
    UNIQUE (family_id, key),
    FOREIGN KEY (family_id) REFERENCES family(id)
);

CREATE TABLE product (
    id UUID PRIMARY KEY,
    sku VARCHAR(25) UNIQUE NOT NULL,
    family_id UUID NOT NULL,
    name VARCHAR(100) NOT NULL,
    attributes jsonb NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    FOREIGN KEY (family_id) REFERENCES family(id)
);

CREATE TABLE stock_item (
    id UUID PRIMARY KEY,
    product_id UUID NOT NULL,
    workshop_id UUID NOT NULL,
    price NUMERIC(10, 2) NOT NULL CHECK (price >= 0),
    last_cost NUMERIC(10, 2) NOT NULL CHECK (last_cost >= 0),
    quantity SMALLINT NOT NULL CHECK (quantity >= 0),
    min_threshold SMALLINT NOT NULL CHECK (min_threshold >= 0),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    FOREIGN KEY (product_id) REFERENCES product(id),
    FOREIGN KEY (workshop_id) REFERENCES workshop(id)
);

CREATE TYPE leaf_pack_status AS ENUM ('OPENED', 'SEALED', 'DEPLETED', 'SOLD');

CREATE TABLE leaf_pack (
    id UUID PRIMARY KEY,
    product_id UUID NOT NULL,
    workshop_id UUID NOT NULL,
    original_leaf_count SMALLINT NOT NULL CHECK (original_leaf_count >= 2),
    available_leaf_count SMALLINT NOT NULL CHECK (available_leaf_count >= 0),
    status leaf_pack_status NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    FOREIGN KEY (product_id) REFERENCES product(id),
    FOREIGN KEY (workshop_id) REFERENCES workshop(id)
);

CREATE TYPE leaf_status AS ENUM ('AVAILABLE', 'SOLD', 'DISCARDED', 'IN_PACKAGE');

CREATE TABLE leaf (
    id UUID PRIMARY KEY,
    leaf_pack_id UUID NOT NULL,
    status leaf_status NOT NULL,
    leaf_number VARCHAR(10) NOT NULL,
    width NUMERIC(10, 2) NOT NULL CHECK (width > 0),
    first_length NUMERIC(10, 2) NOT NULL CHECK (first_length > 0),
    second_length NUMERIC(10, 2) NOT NULL CHECK (second_length > 0),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    FOREIGN KEY (leaf_pack_id) REFERENCES leaf_pack(id)
);