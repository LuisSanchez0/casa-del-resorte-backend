-- Las fotografias del vehiculo se guardan en S3; en base de datos solo vive la
-- key devuelta por S3ServiceImpl.uploadImage, igual que brand.logo_key.

CREATE TABLE vehicle_movement_photo (
    id UUID PRIMARY KEY,
    vehicle_movement_id UUID NOT NULL,
    photo_key VARCHAR(255) NOT NULL,
    FOREIGN KEY (vehicle_movement_id) REFERENCES vehicle_movement(id)
);

CREATE INDEX idx_vehicle_movement_photo_movement_id
    ON vehicle_movement_photo(vehicle_movement_id);
