-- Comanda: cada linea que el vendedor PIDE (pieza especial o servicio) se
-- cumple en el taller antes de poder facturarse.
--
-- El cumplimiento vive en la propia linea y no en una tabla de tareas aparte:
-- una solicitud y su cumplimiento nacen, se mueven y mueren juntos (1:1), la
-- venta ya carga sus lineas en una sola consulta, y una tabla nueva obligaria a
-- otra union por linea en cada lectura de la venta. La "tarea" que ven Control
-- y el Operador ES la linea; su id es el de `sale_detail`.
--
--   fulfillment_type    quien la cumple y como:
--                         STOCK_RELEASE   -> Control entrega una unidad que ya existe
--                         TRANSFORMATION  -> el Operador fabrica la pieza
--                         SERVICE         -> el Operador ejecuta el servicio
--                       NULL mientras una pieza especial no se ha enviado: la ruta
--                       se decide al enviar la comanda, con el stock de ESE momento.
--   fulfillment_status  PENDING (sin enviar), QUEUED, IN_PROGRESS, BLOCKED,
--                       FULFILLED, CANCELLED.
--   fulfillment_block_reason  por que esta BLOCKED, y por tanto quien la
--                       destraba:
--                         MATERIAL      -> no hay pieza de origen; el Operador
--                         PRICE_REVIEW  -> la pieza de origen cuesta mas que el
--                                          precio autorizado; el Vendedor
--   fulfillment_required_price  en PRICE_REVIEW, el minimo que debe cubrir el
--                       precio de la linea para poder fabricarla.
--
-- Una linea con fulfillment_status NULL es una linea DIRECTA (lo de siempre:
-- stock que sale al agregarla, o un trabajo ya hecho). Ninguna fila existente
-- se convierte en solicitud: el backfill es no hacer nada, y las ventas
-- abiertas antes de esta migracion se completan exactamente como antes.

ALTER TABLE sale
    ADD COLUMN fulfillment_status VARCHAR(20) NOT NULL DEFAULT 'NONE',
    ADD CONSTRAINT sale_fulfillment_status_check
        CHECK (fulfillment_status IN ('NONE', 'IN_PROGRESS', 'READY_TO_COMPLETE'));

-- Orden estable de las lineas. `sale_detail` no tenia fecha de alta, asi que
-- la venta devolvia sus lineas en el orden fisico de la tabla, que cambia al
-- actualizar una fila: con la comanda, el vendedor sigue el pedido mientras el
-- taller lo cumple y las lineas no pueden saltar de sitio en cada lectura.
-- `clock_timestamp()` y no `now()`: varias lineas de la misma transaccion
-- (una solicitud de tres unidades) conservan su orden de alta.
ALTER TABLE sale_detail
    ADD COLUMN created_at TIMESTAMP NOT NULL DEFAULT clock_timestamp();

ALTER TABLE sale_detail
    ADD COLUMN instructions VARCHAR(500),
    ADD COLUMN fulfillment_type VARCHAR(20),
    ADD COLUMN fulfillment_status VARCHAR(20),
    ADD COLUMN fulfillment_transformation_id UUID,
    ADD COLUMN fulfillment_job_log_id UUID,
    ADD COLUMN fulfillment_employee_id UUID,
    ADD COLUMN fulfillment_note VARCHAR(255),
    ADD COLUMN fulfillment_block_reason VARCHAR(20),
    ADD COLUMN fulfillment_required_price NUMERIC(10, 2),
    ADD COLUMN fulfillment_dispatched_at TIMESTAMP,
    ADD COLUMN fulfillment_started_at TIMESTAMP,
    ADD COLUMN fulfillment_completed_at TIMESTAMP,
    ADD CONSTRAINT fk_sale_detail_fulfillment_transformation
        FOREIGN KEY (fulfillment_transformation_id) REFERENCES transformation(id),
    ADD CONSTRAINT fk_sale_detail_fulfillment_job_log
        FOREIGN KEY (fulfillment_job_log_id) REFERENCES job_log(id),
    ADD CONSTRAINT fk_sale_detail_fulfillment_employee
        FOREIGN KEY (fulfillment_employee_id) REFERENCES employee(id),
    ADD CONSTRAINT sale_detail_fulfillment_type_check
        CHECK (fulfillment_type IS NULL
            OR fulfillment_type IN ('STOCK_RELEASE', 'TRANSFORMATION', 'SERVICE')),
    ADD CONSTRAINT sale_detail_fulfillment_status_check
        CHECK (fulfillment_status IS NULL
            OR fulfillment_status IN (
                'PENDING', 'QUEUED', 'IN_PROGRESS', 'BLOCKED', 'FULFILLED', 'CANCELLED')),
    -- Una linea directa no tiene ruta; una solicitud ya enviada siempre la tiene.
    -- CASE y no OR: un CHECK que evalua a NULL se da por bueno, y
    -- `NULL IN (...)` es NULL, asi que con OR una ruta sin estado pasaba.
    ADD CONSTRAINT ck_sale_detail_fulfillment_shape
        CHECK (
            CASE
                WHEN fulfillment_status IS NULL THEN fulfillment_type IS NULL
                WHEN fulfillment_status IN ('PENDING', 'CANCELLED') THEN TRUE
                ELSE fulfillment_type IS NOT NULL
            END
        ),
    ADD CONSTRAINT sale_detail_fulfillment_block_reason_check
        CHECK (fulfillment_block_reason IS NULL
            OR fulfillment_block_reason IN ('MATERIAL', 'PRICE_REVIEW')),
    -- Un bloqueo siempre dice por que, y solo un bloqueo lo dice. En revision
    -- de precio se guarda ademas el minimo que hay que cubrir.
    ADD CONSTRAINT ck_sale_detail_fulfillment_block_shape
        CHECK (
            CASE
                WHEN fulfillment_status IS DISTINCT FROM 'BLOCKED'
                    THEN fulfillment_block_reason IS NULL
                         AND fulfillment_required_price IS NULL
                WHEN fulfillment_block_reason = 'PRICE_REVIEW'
                    THEN fulfillment_required_price IS NOT NULL
                         AND fulfillment_required_price >= 0
                ELSE fulfillment_block_reason IS NOT NULL
                     AND fulfillment_required_price IS NULL
            END
        ),
    ADD CONSTRAINT ck_sale_detail_instructions_not_blank
        CHECK (instructions IS NULL OR btrim(instructions) <> '');

-- Una transformacion o un servicio ejecutado cumple UNA sola solicitud: es la
-- misma garantia que uk_sale_detail_work_result da al trabajo que se factura.
CREATE UNIQUE INDEX uk_sale_detail_fulfillment_transformation
    ON sale_detail(fulfillment_transformation_id)
    WHERE fulfillment_transformation_id IS NOT NULL;

CREATE UNIQUE INDEX uk_sale_detail_fulfillment_job_log
    ON sale_detail(fulfillment_job_log_id)
    WHERE fulfillment_job_log_id IS NOT NULL;

-- Colas de Control y del Operador: solo las solicitudes vivas.
CREATE INDEX idx_sale_detail_fulfillment_queue
    ON sale_detail(fulfillment_type, fulfillment_status)
    WHERE fulfillment_status IN ('QUEUED', 'IN_PROGRESS', 'BLOCKED');

-- Ventas listas para completar del taller.
CREATE INDEX idx_sale_ready_to_complete
    ON sale(workshop_id)
    WHERE status = 'PENDING' AND fulfillment_status = 'READY_TO_COMPLETE';
