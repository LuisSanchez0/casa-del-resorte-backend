-- El movimiento de vehiculo no guardaba QUIEN registro la entrada: `Auditable`
-- solo aporta created_at/updated_at y no existe @CreatedBy ni AuditorAware en el
-- proyecto. Sin este dato el dashboard policial no puede ofrecer "Mis
-- estadisticas" sin inventarlas desde el cliente.
--
-- Se sigue exactamente la convencion ya usada por daily_log, transformation,
-- sale y job_log: una FK `employee_id` hacia employee(id) que el servicio fija
-- desde la identidad autenticada (CurrentUserService), nunca desde la peticion.
--
-- NULLABLE a proposito, y esta es la parte importante: las filas anteriores a
-- esta migracion no tienen actor demostrable. Asignarlas a cualquier empleado
-- seria inventar autoria. Quedan en NULL y el ambito personal las excluye por
-- construccion (employee_id = :employeeId nunca casa con NULL).

ALTER TABLE vehicle_movement
    ADD COLUMN employee_id UUID;

ALTER TABLE vehicle_movement
    ADD CONSTRAINT fk_vehicle_movement_employee
        FOREIGN KEY (employee_id) REFERENCES employee(id);

-- El dashboard agrega SIEMPRE por taller y casi siempre tambien por fecha de
-- entrada (KPI de hoy, tendencia de 30 dias, top de placas). Este indice cubre
-- ese acceso y evita recorrer la tabla completa por taller.
CREATE INDEX idx_vehicle_movement_workshop_entry
    ON vehicle_movement(workshop_id, entry);

-- Variante personal de las mismas agregaciones. Solo indexa filas con actor:
-- las historicas en NULL no se consultan nunca por este camino.
CREATE INDEX idx_vehicle_movement_workshop_employee_entry
    ON vehicle_movement(workshop_id, employee_id, entry)
    WHERE employee_id IS NOT NULL;
