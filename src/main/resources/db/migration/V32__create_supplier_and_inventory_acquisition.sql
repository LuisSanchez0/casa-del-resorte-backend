-- ═══════════════════════════════════════════════════════════════════════════
-- PARTE 2 de 2: ESQUEMA NUEVO
-- ═══════════════════════════════════════════════════════════════════════════

-- ── Proveedor ──────────────────────────────────────────────────────────────
--
-- Es del TALLER, no global: dos talleres pueden trabajar con proveedores
-- distintos que se llamen igual, y el nombre de uno no debe filtrarse al
-- autocompletado del otro. Mismo criterio que `inventory`, `job` y `sale`.
--
-- No lleva NIT, telefono, direccion ni datos bancarios: el requisito es
-- identificar de quien vino un lote, y ningun flujo del sistema necesita hoy
-- facturar ni pagar a un proveedor. Añadir columnas que nadie escribe solo
-- crea formularios con campos vacios.

CREATE TABLE supplier (
    id UUID PRIMARY KEY,
    workshop_id UUID NOT NULL,
    name VARCHAR(100) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    FOREIGN KEY (workshop_id) REFERENCES workshop(id)
);

-- Unicidad por FORMA CANONICA del nombre dentro del taller.
--
-- La comprobacion previa del servicio existe para dar un mensaje entendible,
-- pero NO sustituye a este indice: dos peticiones simultaneas pasan las dos por
-- el `exists(...)` y solo esta linea impide que se persistan dos proveedores
-- que son el mismo.
--
-- La canonizacion no es solo UPPER. Estos tres nombres son el MISMO proveedor y
-- los tres colisionan aqui:
--
--   'Resortes Guatemala'
--   ' resortes guatemala '     -> BTRIM
--   'RESORTES   GUATEMALA'     -> REGEXP_REPLACE de espacios repetidos
--
-- `SupplierServiceImpl.canonical` aplica exactamente esta misma expresion en
-- Java, de modo que lo que el servicio considera duplicado y lo que la base
-- rechaza no pueden separarse. El nombre del indice se compara en el servicio
-- para traducir la violacion concurrente en un 400 de negocio, asi que
-- renombrarlo convertiria esa carrera en un 500.
CREATE UNIQUE INDEX uq_supplier_canonical_name_per_workshop
    ON supplier(workshop_id, UPPER(REGEXP_REPLACE(BTRIM(name), '\s+', ' ', 'g')));


-- ── Lote de inventario ─────────────────────────────────────────────────────
--
-- Una fila por ENTRADA de stock. `quantity_remaining` es lo que queda sin
-- consumir de ese lote concreto, y es la columna sobre la que corre el FIFO.
--
-- `origin` distingue las dos formas en que una unidad entra al inventario, y
-- existe para que el invariante
--
--     inventory.stock = SUM(quantity_remaining)
--
-- se cumpla SIEMPRE y no solo para lo comprado. Sin esta columna habria stock
-- sin lote —el que produce una transformacion o el armado de una maleta— y el
-- FIFO se encontraria con lineas de venta que no puede costear a medias, que es
-- justo el estado inconsistente que este modelo debe hacer imposible.
--
--   PURCHASE    compra real registrada en Control. Lleva proveedor, tipo y
--               costo unitario, los tres obligatorios.
--   PRODUCTION  la unidad entro sin comprarse: salida de una transformacion,
--               maleta armada con componentes que ya eran nuestros, o
--               componente devuelto al desarmarla. NO tiene proveedor, ni tipo,
--               ni costo, y el CHECK impide inventarselos.
--
-- Un lote PRODUCTION no es un proveedor falso: es el registro explicito de que
-- esa unidad no se compro. Lo que se vende consumiendolo queda marcado como sin
-- cobertura de costo, no como costo cero.

CREATE TABLE inventory_acquisition (
    id UUID PRIMARY KEY,
    workshop_id UUID NOT NULL,
    inventory_id UUID NOT NULL,
    supplier_id UUID,
    employee_id UUID,

    origin VARCHAR(20) NOT NULL CHECK (origin IN ('PURCHASE', 'PRODUCTION')),
    acquisition_type VARCHAR(20) CHECK (acquisition_type IN ('NATIONAL', 'IMPORTATION')),

    -- SMALLINT, igual que `inventory.stock`: un lote no puede traer mas
    -- unidades de las que la existencia sabe contar.
    quantity_received SMALLINT NOT NULL CHECK (quantity_received > 0),
    quantity_remaining SMALLINT NOT NULL,

    -- Misma precision que `inventory.cost`: es la misma magnitud.
    unit_cost NUMERIC(10, 2) CHECK (unit_cost >= 0),

    -- Fecha de negocio de la entrada, la que fecha las graficas de compras. La
    -- pone el servidor; no viaja en la peticion.
    acquired_at TIMESTAMP NOT NULL,

    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,

    -- El invariante que impide que el FIFO consuma de mas o resucite unidades.
    -- Va en la BASE y no solo en Java porque es la unica barrera que sigue en
    -- pie cuando dos ventas simultaneas tocan el mismo lote.
    CONSTRAINT ck_inventory_acquisition_remaining
        CHECK (quantity_remaining >= 0 AND quantity_remaining <= quantity_received),

    -- Una compra SIEMPRE tiene proveedor, tipo y costo. Una entrada de
    -- produccion NUNCA los tiene. Sin este CHECK, un lote a medio rellenar
    -- pasaria por compra en las graficas de gasto.
    CONSTRAINT ck_inventory_acquisition_origin_shape CHECK (
        (origin = 'PURCHASE'
            AND supplier_id IS NOT NULL
            AND acquisition_type IS NOT NULL
            AND unit_cost IS NOT NULL)
        OR
        (origin = 'PRODUCTION'
            AND supplier_id IS NULL
            AND acquisition_type IS NULL
            AND unit_cost IS NULL)
    ),

    FOREIGN KEY (workshop_id) REFERENCES workshop(id),
    FOREIGN KEY (inventory_id) REFERENCES inventory(id),
    FOREIGN KEY (supplier_id) REFERENCES supplier(id),
    FOREIGN KEY (employee_id) REFERENCES employee(id)
);

-- La consulta del FIFO: lotes de UN inventario que todavia tienen existencias,
-- en orden de antiguedad. El indice es PARCIAL —solo filas con remanente— para
-- que los lotes agotados, que son la mayoria en un inventario con historia, no
-- ocupen sitio ni haya que descartarlos al recorrerlo. Las columnas de orden
-- van en el propio indice, de modo que el ORDER BY del FIFO no ordena nada en
-- tiempo de consulta.
CREATE INDEX idx_inventory_acquisition_fifo
    ON inventory_acquisition(inventory_id, acquired_at, id)
    WHERE quantity_remaining > 0;

-- Las series de gasto del panel (dia/mes/año, nacional/importacion) agrupan por
-- `acquired_at` dentro de un taller. Este es su indice.
CREATE INDEX idx_inventory_acquisition_workshop_acquired_at
    ON inventory_acquisition(workshop_id, acquired_at);

-- El Top 5 de proveedores agrupa por proveedor. Sirve ademas para validar la FK
-- al reasignar o dar de baja un proveedor sin recorrer la tabla entera, que es
-- la misma razon por la que V24 indexo `sale_action.performed_by`.
CREATE INDEX idx_inventory_acquisition_supplier
    ON inventory_acquisition(supplier_id)
    WHERE supplier_id IS NOT NULL;


-- ── Costo historico de la linea de venta ───────────────────────────────────
--
-- NULL no es cero: significa "esta linea no tiene costo de adquisicion
-- conocido" —un servicio, o mercaderia que entro por produccion—. Rellenarlo
-- con 0 convertiria el precio entero en ganancia y es exactamente la mentira
-- que este modelo existe para evitar, asi que la columna es NULLABLE a
-- proposito y el panel distingue los dos casos.
--
-- NUMERIC(12,2) y no (10,2) como `sub_total`: es una SUMA de
-- cantidad x costo_unitario sobre varios lotes, asi que necesita mas holgura
-- que un importe unitario. Los dos decimales son los mismos.
ALTER TABLE sale_detail
    ADD COLUMN cost_total NUMERIC(12, 2) CHECK (cost_total >= 0);


-- ── Trazabilidad del consumo FIFO ──────────────────────────────────────────
--
-- Una linea de venta puede consumir VARIOS lotes (4 unidades = 2 del lote de
-- Q90 + 2 del de Q110), asi que `cost_total` por si solo no basta: dice cuanto
-- costo, pero no DE DONDE salio. Y hace falta saberlo, porque en este dominio
-- el stock se devuelve de verdad —quitar una linea, anular un borrador y
-- revertir una venta emitida pasan los tres por el mismo `restore` de los
-- handlers—. Sin esta tabla, devolver stock obligaria a adivinar a que lotes
-- regresa, y adivinar significa reponer en el lote equivocado y corromper el
-- costo de la siguiente venta.
--
-- `unit_cost_snapshot` se COPIA aqui y no se lee del lote por join: es historia
-- contable. Aunque el lote se agote, aunque el proveedor cambie de nombre,
-- aunque manana entre mercaderia mas cara, lo que esta linea costo no se mueve.

CREATE TABLE sale_detail_cost_allocation (
    id UUID PRIMARY KEY,
    sale_detail_id UUID NOT NULL,
    acquisition_id UUID NOT NULL,

    quantity SMALLINT NOT NULL CHECK (quantity > 0),
    unit_cost_snapshot NUMERIC(10, 2) NOT NULL CHECK (unit_cost_snapshot >= 0),
    subtotal_cost NUMERIC(12, 2) NOT NULL CHECK (subtotal_cost >= 0),

    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,

    FOREIGN KEY (sale_detail_id) REFERENCES sale_detail(id),
    FOREIGN KEY (acquisition_id) REFERENCES inventory_acquisition(id)
);

-- Devolver stock lee TODAS las asignaciones de una linea. Es la consulta
-- caliente de quitar/anular/revertir.
CREATE INDEX idx_sale_detail_cost_allocation_detail
    ON sale_detail_cost_allocation(sale_detail_id);

-- Y esta valida la FK al lote sin recorrer la tabla.
CREATE INDEX idx_sale_detail_cost_allocation_acquisition
    ON sale_detail_cost_allocation(acquisition_id);
