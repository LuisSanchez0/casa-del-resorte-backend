-- La regla "una placa no puede tener dos movimientos ABIERTOS en el mismo taller"
-- solo se comprobaba en aplicacion (existsByPlateAndWorkshopIdAndExitIsNull), lo
-- que no la garantiza si dos peticiones concurrentes pasan la comprobacion antes
-- de que cualquiera de las dos inserte. El indice parcial la hace efectiva en la
-- base de datos y deja libres los movimientos ya cerrados (exit IS NOT NULL).
--
-- Se compara `plate` tal cual porque hoy no existe normalizacion de mayusculas en
-- el modulo: el indice replica exactamente el criterio de la consulta de negocio.
-- Si mas adelante se normaliza la placa, este indice debe pasar a UPPER(plate).

CREATE UNIQUE INDEX uq_vehicle_movement_open_plate_per_workshop
    ON vehicle_movement(workshop_id, plate)
    WHERE exit IS NULL;
