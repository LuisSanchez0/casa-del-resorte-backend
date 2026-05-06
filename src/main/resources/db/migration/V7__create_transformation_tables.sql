CREATE TABLE transformation (
    id UUID PRIMARY KEY,
    workshop_id UUID NOT NULL,
    employee_id UUID NOT NULL,
    performed_date DATETIME NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    FOREIGN KEY (workshop_id) REFERENCES workshop(id),
    FOREIGN KEY (employee_id) REFERENCES employee(id)
);

CREATE TABLE transformation_item (
    id UUID PRIMARY KEY,
    transformation_id UUID NOT NULL,
    item_id UUID NOT NULL,
    role VARCHAR(20) NOT NULL CHECK (role IN ('INPUT', 'OUTPUT', 'REMAINDER')),
    FOREIGN KEY (transformation_id) REFERENCES transformation(id) ON DELETE CASCADE,
    FOREIGN KEY (item_id) REFERENCES inventory_item(id)
);
