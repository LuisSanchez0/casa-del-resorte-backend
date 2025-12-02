CREATE TABLE brand (
    id UUID PRIMARY KEY,
    name VARCHAR(50) UNIQUE NOT NULL,
    logo_url TEXT UNIQUE NOT NULL,
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

CREATE TABLE products (
    id UUID PRIMARY KEY,
    sku VARCHAR(25) UNIQUE NOT NULL,
    family_id UUID NOT NULL,
    name VARCHAR(100) NOT NULL,
    price NUMERIC(10, 2) NOT NULL CHECK (price >= 0),
    cost NUMERIC(10, 2) NOT NULL CHECK (cost >= 0),
    stock SMALLINT NOT NULL CHECK (stock >= 0),
    min_stock SMALLINT NOT NULL CHECK (min_stock >= 0),
    attributes jsonb NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);






