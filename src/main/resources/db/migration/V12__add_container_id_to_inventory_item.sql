ALTER TABLE inventory_item
    ADD COLUMN container_id UUID;

ALTER TABLE inventory_item
    ADD CONSTRAINT fk_inventory_item_container
        FOREIGN KEY (container_id)
            REFERENCES inventory_item(id);

CREATE INDEX idx_inventory_item_container_id
    ON inventory_item(container_id);