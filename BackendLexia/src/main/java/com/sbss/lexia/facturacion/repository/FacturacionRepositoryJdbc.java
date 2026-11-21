package com.sbss.lexia.facturacion.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.sbss.lexia.facturacion.dto.FacturacionDtos.ActualizarHonorarioRequest;
import com.sbss.lexia.facturacion.dto.FacturacionDtos.CatalogosFacturacion;
import com.sbss.lexia.facturacion.dto.DatosEmisor;
import com.sbss.lexia.facturacion.dto.FacturacionDtos.DetalleHonorario;
import com.sbss.lexia.facturacion.dto.FacturacionDtos.ExpedienteOpcion;
import com.sbss.lexia.facturacion.dto.FacturacionDtos.GuardarHonorarioRequest;
import com.sbss.lexia.facturacion.dto.FacturacionDtos.HonorarioLista;
import com.sbss.lexia.shared.dto.Limites;
import com.sbss.lexia.facturacion.dto.FacturacionDtos.Opcion;
import com.sbss.lexia.facturacion.dto.FacturacionDtos.PagoHonorarioDetalle;
import com.sbss.lexia.facturacion.dto.FacturacionDtos.RegistrarMovimientoRequest;
import com.sbss.lexia.facturacion.dto.FacturacionDtos.RegistrarPagoRequest;
import com.sbss.lexia.facturacion.dto.FacturacionDtos.ResumenFacturacion;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class FacturacionRepositoryJdbc {

    private final NamedParameterJdbcTemplate jdbc;


    // =========================================================
    // LISTAR HONORARIOS
    // =========================================================

    public List<HonorarioLista> listar(
            Integer estudioId,
            String cliente,
            String expediente,
            Long modalidadId,
            String estado,
            int limite,
            int desplazamiento) {

        StringBuilder sql = new StringBuilder("""
            SELECT
                h.uuid AS uuid_publico,

                h.cliente_id AS cliente_id,
                h.expediente_id AS expediente_id,

                COALESCE(
                    c.nombre_busqueda,
                    'Cliente sin nombre'
                ) AS cliente_nombre,

                CONCAT(
                    COALESCE(c.tipo_documento, 'DOC'),
                    ' ',
                    COALESCE(c.numero_documento, '')
                ) AS cliente_documento,

                COALESCE(
                    e.codigo_interno,
                    CONCAT('EXP-', h.expediente_id)
                ) AS expediente_numero,

                COALESCE(
                    (
                        SELECT mf.concepto
                        FROM movimientos_financieros mf
                        WHERE mf.honorario_uuid = h.uuid
                          AND mf.tipo_operacion = 'Honorario Pactado'
                        ORDER BY mf.id ASC
                        LIMIT 1
                    ),
                    'Honorarios profesionales'
                ) AS concepto,

                h.modalidad_cobro AS modalidad_cobro,

                h.monto_pactado AS monto_total,

                h.monto_pagado AS monto_pagado,

                h.saldo_pendiente AS monto_pendiente,

                'PEN' AS moneda,

                h.estado_pago AS estado_codigo,

                CASE
                    WHEN h.estado_pago = 'PAGADO'
                        THEN 'Pagado'

                    WHEN h.estado_pago = 'PARCIAL'
                        THEN 'Pago Parcial'

                    ELSE 'Pendiente'
                END AS estado_nombre,

                CASE
                    WHEN h.estado_pago = 'PAGADO'
                        THEN '#28a745'

                    WHEN h.estado_pago = 'PARCIAL'
                        THEN '#ffc107'

                    ELSE '#dc3545'
                END AS estado_color,

                COALESCE(
                    (
                        SELECT mf.fecha
                        FROM movimientos_financieros mf
                        WHERE mf.honorario_uuid = h.uuid
                          AND mf.tipo_operacion = 'Honorario Pactado'
                        ORDER BY mf.id ASC
                        LIMIT 1
                    ),
                    DATE(h.fecha_creacion)
                ) AS fecha_emision,

                h.fecha_vencimiento AS fecha_vencimiento

            FROM honorarios h

            INNER JOIN clientes c
                ON c.id = h.cliente_id

            LEFT JOIN expedientes e
                ON e.id = h.expediente_id

            WHERE c.estudio_id = :estudioId
              AND c.activo = 1
        """);

        MapSqlParameterSource p =
                new MapSqlParameterSource()
                        .addValue("estudioId", estudioId);


        // -----------------------------------------------------
        // FILTRO POR CLIENTE
        // -----------------------------------------------------

        if (cliente != null && !cliente.isBlank()) {

            sql.append("""
                AND LOWER(c.nombre_busqueda) LIKE :cliente
            """);

            p.addValue(
                    "cliente",
                    "%" + cliente.toLowerCase() + "%"
            );
        }


        // -----------------------------------------------------
        // FILTRO POR EXPEDIENTE
        // -----------------------------------------------------

        if (expediente != null && !expediente.isBlank()) {

            sql.append("""
                AND (
                    LOWER(e.codigo_interno) LIKE :expediente
                    OR LOWER(COALESCE(e.numero_expediente, ''))
                        LIKE :expediente
                )
            """);

            p.addValue(
                    "expediente",
                    "%" + expediente.toLowerCase() + "%"
            );
        }


        // -----------------------------------------------------
        // FILTRO POR MODALIDAD
        // -----------------------------------------------------

        if (modalidadId != null) {

            String modalidadTexto =
                    obtenerModalidadTexto(modalidadId);

            sql.append("""
                AND h.modalidad_cobro = :modalidad
            """);

            p.addValue(
                    "modalidad",
                    modalidadTexto
            );
        }


        // -----------------------------------------------------
        // FILTRO POR ESTADO
        // -----------------------------------------------------

        if (estado != null && !estado.isBlank()) {

            sql.append("""
                AND UPPER(h.estado_pago) = :estado
            """);

            p.addValue(
                    "estado",
                    estado.toUpperCase()
            );
        }


        // -----------------------------------------------------
        // ORDEN
        // -----------------------------------------------------

        sql.append("""
            ORDER BY
                h.fecha_creacion DESC,
                h.id DESC
        """);

        // Los honorarios crecen con la facturacion del estudio: sin cota, este
        // listado devolveria todos los del historial en una sola respuesta.
        sql.append(" LIMIT :limite OFFSET :desplazamiento");
        p.addValue("limite", Limites.limite(limite));
        p.addValue("desplazamiento", Limites.desplazamiento(desplazamiento));


        return jdbc.query(
                sql.toString(),
                p,
                (rs, rowNum) -> new HonorarioLista(

                        rs.getString("uuid_publico"),

                        rs.getLong("cliente_id"),

                        rs.getLong("expediente_id"),

                        rs.getString("cliente_nombre"),

                        rs.getString("cliente_documento"),

                        rs.getString("expediente_numero"),

                        rs.getString("concepto"),

                        rs.getString("modalidad_cobro"),

                        rs.getBigDecimal("monto_total"),

                        rs.getBigDecimal("monto_pagado"),

                        rs.getBigDecimal("monto_pendiente"),

                        rs.getString("moneda"),

                        rs.getString("estado_codigo"),

                        rs.getString("estado_nombre"),

                        rs.getString("estado_color"),

                        rs.getDate("fecha_emision") != null
                                ? rs.getDate("fecha_emision").toLocalDate()
                                : null,

                        rs.getDate("fecha_vencimiento") != null
                                ? rs.getDate("fecha_vencimiento").toLocalDate()
                                : null
                )
        );
    }


    // =========================================================
    // DATOS DEL ESTUDIO PARA EL COMPROBANTE
    // =========================================================

    public DatosEmisor obtenerDatosEmisor(Integer estudioId) {

        List<Map<String, Object>> filas = jdbc.queryForList(
                """
                SELECT razon_social, nombre_comercial, ruc,
                       direccion, telefono, email
                  FROM estudios
                 WHERE id = :estudioId
                """,
                new MapSqlParameterSource()
                        .addValue("estudioId", estudioId)
        );

        if (filas.isEmpty()) {
            throw new IllegalStateException(
                    "No se encontraron los datos del estudio."
            );
        }

        Map<String, Object> fila = filas.get(0);

        return new DatosEmisor(
                (String) fila.get("razon_social"),
                (String) fila.get("nombre_comercial"),
                (String) fila.get("ruc"),
                (String) fila.get("direccion"),
                (String) fila.get("telefono"),
                (String) fila.get("email")
        );
    }


    // =========================================================
    // OBTENER DETALLE DE UN HONORARIO
    // =========================================================

    public DetalleHonorario obtenerDetalleHonorario(
            Integer estudioId,
            String honorarioUuid) {

        if (honorarioUuid == null || honorarioUuid.isBlank()) {
            throw new IllegalArgumentException(
                    "Debe indicar el honorario que desea consultar."
            );
        }

        List<Map<String, Object>> resultados = jdbc.queryForList(
                """
                SELECT
                    h.uuid AS uuid_publico,
                    h.cliente_id AS cliente_id,
                    h.expediente_id AS expediente_id,
                    COALESCE(c.nombre_busqueda, 'Cliente sin nombre') AS cliente_nombre,
                    CONCAT(
                        COALESCE(c.tipo_documento, 'DOC'),
                        ' ',
                        COALESCE(c.numero_documento, '')
                    ) AS cliente_documento,
                    COALESCE(
                        e.codigo_interno,
                        CONCAT('EXP-', h.expediente_id)
                    ) AS expediente_numero,
                    COALESCE(
                        (
                            SELECT mf.concepto
                            FROM movimientos_financieros mf
                            WHERE mf.honorario_uuid = h.uuid
                              AND mf.tipo_operacion = 'Honorario Pactado'
                            ORDER BY mf.id ASC
                            LIMIT 1
                        ),
                        'Honorarios profesionales'
                    ) AS concepto,
                    h.modalidad_cobro AS modalidad_cobro,
                    (
                        SELECT mf.etapa_procesal
                        FROM movimientos_financieros mf
                        WHERE mf.honorario_uuid = h.uuid
                          AND mf.tipo_operacion = 'Honorario Pactado'
                        ORDER BY mf.id ASC
                        LIMIT 1
                    ) AS etapa_procesal,
                    (
                        SELECT mf.observaciones
                        FROM movimientos_financieros mf
                        WHERE mf.honorario_uuid = h.uuid
                          AND mf.tipo_operacion = 'Honorario Pactado'
                        ORDER BY mf.id ASC
                        LIMIT 1
                    ) AS observaciones,
                    h.monto_pactado AS monto_pactado,
                    h.monto_pagado AS monto_pagado,
                    h.saldo_pendiente AS saldo_pendiente,
                    'PEN' AS moneda,
                    h.estado_pago AS estado_codigo,
                    CASE
                        WHEN h.estado_pago = 'PAGADO' THEN 'Pagado'
                        WHEN h.estado_pago = 'PARCIAL' THEN 'Pago Parcial'
                        ELSE 'Pendiente'
                    END AS estado_nombre,
                    COALESCE(
                        (
                            SELECT mf.fecha
                            FROM movimientos_financieros mf
                            WHERE mf.honorario_uuid = h.uuid
                              AND mf.tipo_operacion = 'Honorario Pactado'
                            ORDER BY mf.id ASC
                            LIMIT 1
                        ),
                        DATE(h.fecha_creacion)
                    ) AS fecha_emision,
                    h.fecha_vencimiento AS fecha_vencimiento
                FROM honorarios h
                INNER JOIN clientes c
                    ON c.id = h.cliente_id
                LEFT JOIN expedientes e
                    ON e.id = h.expediente_id
                WHERE h.uuid = :uuid
                  AND c.estudio_id = :estudioId
                  AND c.activo = 1
                """,
                new MapSqlParameterSource()
                        .addValue("uuid", honorarioUuid)
                        .addValue("estudioId", estudioId)
        );

        if (resultados.isEmpty()) {
            throw new IllegalArgumentException(
                    "El honorario seleccionado no existe."
            );
        }

        Map<String, Object> fila = resultados.get(0);

        List<PagoHonorarioDetalle> pagos = jdbc.query(
                """
                SELECT
                    mf.id,
                    mf.fecha,
                    mf.monto,
                    mf.concepto,
                    mf.observaciones
                FROM movimientos_financieros mf
                INNER JOIN honorarios h
                    ON h.uuid = mf.honorario_uuid
                INNER JOIN clientes c
                    ON c.id = h.cliente_id
                WHERE mf.honorario_uuid = :uuid
                  AND mf.tipo_operacion = 'Abono / Pago de Cuota'
                  AND c.estudio_id = :estudioId
                  AND c.activo = 1
                ORDER BY mf.fecha ASC, mf.id ASC
                """,
                new MapSqlParameterSource()
                        .addValue("uuid", honorarioUuid)
                        .addValue("estudioId", estudioId),
                (rs, rowNum) -> new PagoHonorarioDetalle(
                        rs.getLong("id"),
                        rs.getDate("fecha") != null
                                ? rs.getDate("fecha").toLocalDate()
                                : null,
                        rs.getBigDecimal("monto"),
                        rs.getString("concepto"),
                        rs.getString("observaciones")
                )
        );

        BigDecimal saldoPendiente =
                (BigDecimal) fila.get("saldo_pendiente");

        String estadoCodigo =
                String.valueOf(fila.get("estado_codigo"));

        LocalDate fechaVencimiento = fila.get("fecha_vencimiento") != null
                ? ((java.sql.Date) fila.get("fecha_vencimiento")).toLocalDate()
                : null;

        boolean vencido =
                saldoPendiente != null
                && saldoPendiente.compareTo(BigDecimal.ZERO) > 0
                && fechaVencimiento != null
                && fechaVencimiento.isBefore(LocalDate.now());

        boolean puedeEmitirComprobante =
                "PAGADO".equalsIgnoreCase(estadoCodigo)
                && (saldoPendiente == null
                    || saldoPendiente.compareTo(BigDecimal.ZERO) <= 0);

        LocalDate fechaEmision = fila.get("fecha_emision") != null
                ? ((java.sql.Date) fila.get("fecha_emision")).toLocalDate()
                : null;

        return new DetalleHonorario(
                String.valueOf(fila.get("uuid_publico")),
                ((Number) fila.get("cliente_id")).longValue(),
                ((Number) fila.get("expediente_id")).longValue(),
                String.valueOf(fila.get("cliente_nombre")),
                String.valueOf(fila.get("cliente_documento")),
                String.valueOf(fila.get("expediente_numero")),
                fila.get("concepto") != null
                        ? String.valueOf(fila.get("concepto"))
                        : null,
                fila.get("modalidad_cobro") != null
                        ? String.valueOf(fila.get("modalidad_cobro"))
                        : null,
                fila.get("etapa_procesal") != null
                        ? String.valueOf(fila.get("etapa_procesal"))
                        : null,
                fila.get("observaciones") != null
                        ? String.valueOf(fila.get("observaciones"))
                        : null,
                (BigDecimal) fila.get("monto_pactado"),
                (BigDecimal) fila.get("monto_pagado"),
                saldoPendiente,
                String.valueOf(fila.get("moneda")),
                estadoCodigo,
                String.valueOf(fila.get("estado_nombre")),
                fechaEmision,
                fechaVencimiento,
                vencido,
                puedeEmitirComprobante,
                pagos
        );
    }


    // =========================================================
    // RESUMEN DE FACTURACIÓN
    // =========================================================

    public ResumenFacturacion resumen(Integer estudioId) {

        String sql = """
            SELECT

                COALESCE(
                    SUM(h.monto_pactado),
                    0
                ) AS total_facturado,

                COALESCE(
                    SUM(h.monto_pagado),
                    0
                ) AS total_cobrado,

                COALESCE(
                    SUM(h.saldo_pendiente),
                    0
                ) AS total_pendiente,

                COUNT(
                    CASE
                        WHEN h.saldo_pendiente > 0
                        THEN 1
                    END
                ) AS facturas_pendientes,

                COALESCE(
                    SUM(
                        CASE
                            WHEN h.saldo_pendiente > 0
                             AND h.fecha_vencimiento IS NOT NULL
                             AND h.fecha_vencimiento < CURDATE()
                                THEN h.saldo_pendiente
                            ELSE 0
                        END
                    ),
                    0
                ) AS total_vencido,

                COUNT(
                    CASE
                        WHEN h.saldo_pendiente > 0
                         AND h.fecha_vencimiento IS NOT NULL
                         AND h.fecha_vencimiento < CURDATE()
                            THEN 1
                    END
                ) AS cuentas_vencidas

            FROM honorarios h

            INNER JOIN clientes c
                ON c.id = h.cliente_id

            WHERE c.estudio_id = :estudioId
              AND c.activo = 1
        """;

        return jdbc.queryForObject(
                sql,
                Map.of("estudioId", estudioId),
                (rs, rowNum) -> new ResumenFacturacion(

                        rs.getBigDecimal("total_facturado"),

                        rs.getBigDecimal("total_cobrado"),

                        rs.getBigDecimal("total_pendiente"),

                        rs.getLong("facturas_pendientes"),

                        rs.getBigDecimal("total_vencido"),

                        rs.getLong("cuentas_vencidas")
                )
        );
    }


    // =========================================================
    // CATÁLOGOS DE FACTURACIÓN
    // =========================================================

    public CatalogosFacturacion catalogos(
            Integer estudioId) {


        // -----------------------------------------------------
        // CLIENTES
        // -----------------------------------------------------
        //
        // Se conservan los clientes cargados desde MySQL.
        //
        // detalle queda vacío porque en Angular solamente
        // queremos mostrar el nombre del cliente.
        //
        // -----------------------------------------------------

        List<Opcion> clientes = jdbc.query(
                """
                SELECT
                    id,
                    nombre_busqueda AS nombre,
                    '' AS detalle

                FROM clientes

                WHERE estudio_id = :estudioId
                  AND activo = 1

                ORDER BY nombre_busqueda
                """,

                Map.of("estudioId", estudioId),

                (rs, n) -> new Opcion(
                        rs.getLong("id"),
                        rs.getString("nombre"),
                        rs.getString("detalle")
                )
        );


        // -----------------------------------------------------
        // EXPEDIENTES
        // -----------------------------------------------------
        //
        // Ahora cada expediente también devuelve cliente_id.
        //
        // Esto permitirá que Angular filtre los expedientes
        // según el cliente seleccionado.
        //
        // -----------------------------------------------------

        List<ExpedienteOpcion> expedientes = jdbc.query(
                """
                SELECT
                    id,
                    codigo_interno AS nombre,
                    numero_expediente AS detalle,
                    cliente_id

                FROM expedientes

                WHERE estudio_id = :estudioId
                  AND activo = 1

                ORDER BY id DESC
                """,

                Map.of("estudioId", estudioId),

                (rs, n) -> new ExpedienteOpcion(
                        rs.getLong("id"),
                        rs.getString("nombre"),
                        rs.getString("detalle"),
                        rs.getLong("cliente_id")
                )
        );


        // -----------------------------------------------------
        // MODALIDADES DE COBRO
        // -----------------------------------------------------
        //
        // Modalidades requeridas por el módulo.
        //
        // IMPORTANTE:
        //
        // "Abono / Pago de Cuota" NO es una modalidad.
        // Es un tipo de operación financiera.
        //
        // -----------------------------------------------------

        List<Opcion> modalidades = List.of(

                new Opcion(
                        1L,
                        "Por Etapas",
                        "ETAPAS"
                ),

                new Opcion(
                        2L,
                        "Monto Fijo / Global",
                        "FIJO"
                ),

                new Opcion(
                        3L,
                        "Cuota Litis / Éxito",
                        "EXITO"
                ),

                new Opcion(
                        4L,
                        "Mensual",
                        "MENSUAL"
                ),

                new Opcion(
                        5L,
                        "Por Hora",
                        "HORA"
                ),

                new Opcion(
                        6L,
                        "Mixta",
                        "MIXTA"
                ),

                new Opcion(
                        7L,
                        "Consulta",
                        "CONSULTA"
                ),

                new Opcion(
                        8L,
                        "Suscripción Empresarial",
                        "SUSCRIPCION"
                )
        );


        // -----------------------------------------------------
        // ESTADOS
        // -----------------------------------------------------

        List<Opcion> estados = List.of(

                new Opcion(
                        1L,
                        "PENDIENTE",
                        "PENDIENTE"
                ),

                new Opcion(
                        2L,
                        "PARCIAL",
                        "PARCIAL"
                ),

                new Opcion(
                        3L,
                        "PAGADO",
                        "PAGADO"
                )
        );


        return new CatalogosFacturacion(
                clientes,
                expedientes,
                modalidades,
                estados
        );
    }


    // =========================================================
    // CREAR HONORARIO
    // =========================================================

    @Transactional
    public String crearHonorario(
            Integer estudioId,
            Long usuarioId,
            GuardarHonorarioRequest r) {

        validarClienteYExpediente(
                estudioId,
                r.clienteId(),
                r.expedienteId()
        );

        String uuid = UUID.randomUUID().toString();

        String sql = """
            INSERT INTO honorarios (
                uuid,
                cliente_id,
                expediente_id,
                modalidad_cobro,
                estado_pago,
                monto_pactado,
                monto_pagado,
                gastos,
                fecha_vencimiento
            )

            VALUES (
                :uuid,
                :clienteId,
                :expedienteId,
                :modalidad,
                'PENDIENTE',
                :montoTotal,
                0.00,
                0.00,
                :fechaVencimiento
            )
        """;

        MapSqlParameterSource p =
                new MapSqlParameterSource()

                        .addValue(
                                "uuid",
                                uuid
                        )

                        .addValue(
                                "clienteId",
                                r.clienteId()
                        )

                        .addValue(
                                "expedienteId",
                                r.expedienteId()
                        )

                        .addValue(
                                "modalidad",
                                obtenerModalidadTexto(
                                        r.modalidadCobroId()
                                )
                        )

                        .addValue(
                                "montoTotal",
                                r.montoTotal()
                        )

                        .addValue(
                                "fechaVencimiento",
                                r.fechaVencimiento()
                        );


        jdbc.update(
                sql,
                p
        );

        return uuid;
    }


    // =========================================================
    // ACTUALIZAR HONORARIO
    // =========================================================
    //
    // Este método será utilizado por el botón Editar.
    //
    // REGLA:
    //
    // Si el honorario todavía NO tiene pagos:
    // - puede cambiar cliente
    // - puede cambiar expediente
    // - puede cambiar monto pactado
    //
    // Si ya tiene uno o más pagos:
    // - cliente no puede cambiar
    // - expediente no puede cambiar
    // - monto pactado no puede cambiar
    //
    // Esto evita inconsistencias con el historial de pagos.
    //
    // La modalidad y los datos descriptivos sí pueden
    // actualizarse.
    //
    // =========================================================

    @Transactional
    public void actualizarHonorario(
            Integer estudioId,
            Long usuarioId,
            String honorarioUuid,
            ActualizarHonorarioRequest r) {

        if (honorarioUuid == null || honorarioUuid.isBlank()) {
            throw new IllegalArgumentException(
                    "Debe indicar el honorario que desea editar."
            );
        }

        validarClienteYExpediente(
                estudioId,
                r.clienteId(),
                r.expedienteId()
        );


        // -----------------------------------------------------
        // OBTENER HONORARIO ACTUAL
        // -----------------------------------------------------

        List<Map<String, Object>> resultados = jdbc.queryForList(
                """
                SELECT
                    h.cliente_id,
                    h.expediente_id,
                    h.monto_pactado,
                    h.monto_pagado

                FROM honorarios h

                INNER JOIN clientes c
                    ON c.id = h.cliente_id

                WHERE h.uuid = :uuid
                  AND c.estudio_id = :estudioId
                  AND c.activo = 1
                """,

                new MapSqlParameterSource()
                        .addValue("uuid", honorarioUuid)
                        .addValue("estudioId", estudioId)
        );


        if (resultados.isEmpty()) {
            throw new IllegalArgumentException(
                    "El honorario seleccionado no existe."
            );
        }


        Map<String, Object> actual = resultados.get(0);

        Long clienteActual =
                ((Number) actual.get("cliente_id"))
                        .longValue();

        Long expedienteActual =
                ((Number) actual.get("expediente_id"))
                        .longValue();

        BigDecimal montoActual =
                (BigDecimal) actual.get("monto_pactado");

        BigDecimal montoPagado =
                (BigDecimal) actual.get("monto_pagado");


        // -----------------------------------------------------
        // PROTEGER DATOS CUANDO YA EXISTEN PAGOS
        // -----------------------------------------------------

        if (montoPagado != null
                && montoPagado.compareTo(BigDecimal.ZERO) > 0) {

            if (!clienteActual.equals(r.clienteId())) {
                throw new IllegalArgumentException(
                        "No se puede cambiar el cliente porque el honorario ya tiene pagos registrados."
                );
            }

            if (!expedienteActual.equals(r.expedienteId())) {
                throw new IllegalArgumentException(
                        "No se puede cambiar el expediente porque el honorario ya tiene pagos registrados."
                );
            }

            if (montoActual.compareTo(r.montoPactado()) != 0) {
                throw new IllegalArgumentException(
                        "No se puede cambiar el monto pactado porque el honorario ya tiene pagos registrados."
                );
            }
        }


        String modalidad =
                normalizarModalidad(
                        r.modalidad()
                );


        // -----------------------------------------------------
        // ACTUALIZAR TABLA HONORARIOS
        // -----------------------------------------------------

        int actualizados = jdbc.update(
                """
                UPDATE honorarios

                SET
                    cliente_id = :clienteId,
                    expediente_id = :expedienteId,
                    modalidad_cobro = :modalidad,
                    monto_pactado = :montoPactado,
                    fecha_vencimiento = :fechaVencimiento,

                    estado_pago =
                        CASE
                            WHEN monto_pagado <= 0
                                THEN 'PENDIENTE'

                            WHEN monto_pagado >= :montoPactado
                                THEN 'PAGADO'

                            ELSE 'PARCIAL'
                        END

                WHERE uuid = :uuid
                  AND EXISTS (
                      SELECT 1
                      FROM clientes c
                      WHERE c.id = honorarios.cliente_id
                        AND c.estudio_id = :estudioId
                        AND c.activo = 1
                  )
                """,

                new MapSqlParameterSource()

                        .addValue(
                                "clienteId",
                                r.clienteId()
                        )

                        .addValue(
                                "expedienteId",
                                r.expedienteId()
                        )

                        .addValue(
                                "modalidad",
                                modalidad
                        )

                        .addValue(
                                "montoPactado",
                                r.montoPactado()
                        )

                        .addValue(
                                "fechaVencimiento",
                                r.fechaVencimiento()
                        )

                        .addValue(
                                "uuid",
                                honorarioUuid
                        )

                        .addValue(
                                "estudioId",
                                estudioId
                        )
        );


        if (actualizados != 1) {
            throw new IllegalArgumentException(
                    "No se pudo actualizar el honorario seleccionado."
            );
        }


        // -----------------------------------------------------
        // ACTUALIZAR MOVIMIENTO ORIGINAL
        // -----------------------------------------------------
        //
        // El honorario pactado también tiene un registro en
        // movimientos_financieros.
        //
        // Lo actualizamos para que el historial no quede con
        // información distinta a la tabla honorarios.
        //
        // -----------------------------------------------------

        jdbc.update(
                """
                UPDATE movimientos_financieros

                SET
                    cliente_id = :clienteId,
                    expediente_id = :expedienteId,
                    modalidad = :modalidad,
                    monto = :monto,
                    fecha = :fecha,
                    etapa_procesal = :etapaProcesal,
                    concepto = :concepto,
                    observaciones = :observaciones

                WHERE honorario_uuid = :uuid
                  AND tipo_operacion = 'Honorario Pactado'
                """,

                new MapSqlParameterSource()

                        .addValue(
                                "clienteId",
                                r.clienteId()
                        )

                        .addValue(
                                "expedienteId",
                                r.expedienteId()
                        )

                        .addValue(
                                "modalidad",
                                modalidad
                        )

                        .addValue(
                                "monto",
                                r.montoPactado()
                        )

                        .addValue(
                                "fecha",
                                r.fecha()
                        )

                        .addValue(
                                "etapaProcesal",
                                r.etapaProcesal()
                        )

                        .addValue(
                                "concepto",
                                r.concepto() != null
                                        && !r.concepto().isBlank()
                                                ? r.concepto()
                                                : "Honorarios profesionales"
                        )

                        .addValue(
                                "observaciones",
                                r.observaciones()
                        )

                        .addValue(
                                "uuid",
                                honorarioUuid
                        )
        );
    }


    // =========================================================
    // REGISTRAR MOVIMIENTO
    // =========================================================

    @Transactional
    public String registrarMovimiento(
            Integer estudioId,
            Long usuarioId,
            RegistrarMovimientoRequest r) {

        String tipoOperacion =
                normalizarTipoOperacion(
                        r.tipoOperacion()
                );

        String modalidad =
                normalizarModalidad(
                        r.modalidad()
                );


        // =====================================================
        // 1. HONORARIO PACTADO
        // =====================================================

        if ("Honorario Pactado".equalsIgnoreCase(tipoOperacion)) {

            if (r.fechaVencimiento() == null) {
                throw new IllegalArgumentException(
                        "Debe indicar la fecha de vencimiento del honorario."
                );
            }

            validarClienteYExpediente(
                    estudioId,
                    r.clienteId(),
                    r.expedienteId()
            );

            String honorarioUuid =
                    UUID.randomUUID().toString();


            // -------------------------------------------------
            // CREAR HONORARIO
            // -------------------------------------------------

            String sqlHonorario = """
                INSERT INTO honorarios (
                    uuid,
                    cliente_id,
                    expediente_id,
                    modalidad_cobro,
                    estado_pago,
                    monto_pactado,
                    monto_pagado,
                    gastos,
                    fecha_vencimiento
                )

                VALUES (
                    :uuid,
                    :clienteId,
                    :expedienteId,
                    :modalidad,
                    'PENDIENTE',
                    :monto,
                    0.00,
                    0.00,
                    :fechaVencimiento
                )
            """;

            MapSqlParameterSource pHonorario =
                    new MapSqlParameterSource()

                            .addValue(
                                    "uuid",
                                    honorarioUuid
                            )

                            .addValue(
                                    "clienteId",
                                    r.clienteId()
                            )

                            .addValue(
                                    "expedienteId",
                                    r.expedienteId()
                            )

                            .addValue(
                                    "modalidad",
                                    modalidad
                            )

                            .addValue(
                                    "monto",
                                    r.monto()
                            )

                            .addValue(
                                    "fechaVencimiento",
                                    r.fechaVencimiento()
                            );


            jdbc.update(
                    sqlHonorario,
                    pHonorario
            );


            // -------------------------------------------------
            // REGISTRAR MOVIMIENTO
            // -------------------------------------------------

            insertarMovimiento(
                    r,
                    tipoOperacion,
                    modalidad,
                    honorarioUuid
            );


            return honorarioUuid;
        }


        // =====================================================
        // 2. ABONO / PAGO DE CUOTA
        // =====================================================

        if ("Abono / Pago de Cuota".equalsIgnoreCase(tipoOperacion)) {

            validarHonorario(
                    estudioId,
                    r.honorarioUuid(),
                    r.clienteId(),
                    r.expedienteId()
            );


            validarMontoPago(
                    estudioId,
                    r.honorarioUuid(),
                    r.monto()
            );


            insertarMovimiento(
                    r,
                    tipoOperacion,
                    modalidad,
                    r.honorarioUuid()
            );


            actualizarMontoPagadoYEstado(
                    r.honorarioUuid(),
                    r.monto()
            );


            return r.honorarioUuid();
        }


        // =====================================================
        // 3. GASTO REEMBOLSABLE
        // =====================================================

        if ("Gasto Reembolsable".equalsIgnoreCase(tipoOperacion)) {

            validarHonorario(
                    estudioId,
                    r.honorarioUuid(),
                    r.clienteId(),
                    r.expedienteId()
            );


            insertarMovimiento(
                    r,
                    tipoOperacion,
                    modalidad,
                    r.honorarioUuid()
            );


            int actualizados = jdbc.update(
                    """
                    UPDATE honorarios

                    SET
                        gastos = gastos + :monto

                    WHERE uuid = :uuid
                      AND cliente_id = :clienteId
                      AND expediente_id = :expedienteId
                    """,

                    new MapSqlParameterSource()

                            .addValue(
                                    "monto",
                                    r.monto()
                            )

                            .addValue(
                                    "uuid",
                                    r.honorarioUuid()
                            )

                            .addValue(
                                    "clienteId",
                                    r.clienteId()
                            )

                            .addValue(
                                    "expedienteId",
                                    r.expedienteId()
                            )
            );


            if (actualizados != 1) {
                throw new IllegalArgumentException(
                        "No se pudo actualizar el gasto del honorario."
                );
            }


            return r.honorarioUuid();
        }


        throw new IllegalArgumentException(
                "Tipo de operación no válido: " + tipoOperacion
        );
    }


    // =========================================================
    // INSERTAR MOVIMIENTO FINANCIERO
    // =========================================================

    private void insertarMovimiento(
            RegistrarMovimientoRequest r,
            String tipoOperacion,
            String modalidad,
            String honorarioUuid) {

        String sql = """
            INSERT INTO movimientos_financieros (
                cliente_id,
                expediente_id,
                honorario_uuid,
                tipo_operacion,
                modalidad,
                monto,
                fecha,
                etapa_procesal,
                concepto,
                observaciones
            )

            VALUES (
                :clienteId,
                :expedienteId,
                :honorarioUuid,
                :tipoOperacion,
                :modalidad,
                :monto,
                :fecha,
                :etapaProcesal,
                :concepto,
                :observaciones
            )
        """;


        MapSqlParameterSource p =
                new MapSqlParameterSource()

                        .addValue(
                                "clienteId",
                                r.clienteId()
                        )

                        .addValue(
                                "expedienteId",
                                r.expedienteId()
                        )

                        .addValue(
                                "honorarioUuid",
                                honorarioUuid
                        )

                        .addValue(
                                "tipoOperacion",
                                tipoOperacion
                        )

                        .addValue(
                                "modalidad",
                                modalidad
                        )

                        .addValue(
                                "monto",
                                r.monto()
                        )

                        .addValue(
                                "fecha",
                                r.fecha()
                        )

                        .addValue(
                                "etapaProcesal",
                                r.etapaProcesal()
                        )

                        .addValue(
                                "concepto",
                                r.concepto() != null
                                        && !r.concepto().isBlank()
                                                ? r.concepto()
                                                : "Movimiento financiero"
                        )

                        .addValue(
                                "observaciones",
                                r.observaciones()
                        );


        jdbc.update(
                sql,
                p
        );
    }


    // =========================================================
    // REGISTRAR PAGO
    // =========================================================

    @Transactional
    public String registrarPago(
            Integer estudioId,
            Long usuarioId,
            String honorarioUuid,
            RegistrarPagoRequest r) {


        // -----------------------------------------------------
        // OBTENER DATOS DEL HONORARIO
        // -----------------------------------------------------

        List<Map<String, Object>> resultados = jdbc.queryForList(
                """
                SELECT
                    h.cliente_id,
                    h.expediente_id,
                    h.modalidad_cobro

                FROM honorarios h

                INNER JOIN clientes c
                    ON c.id = h.cliente_id

                WHERE h.uuid = :uuid
                  AND c.estudio_id = :estudioId
                  AND c.activo = 1
                """,

                Map.of(
                        "uuid",
                        honorarioUuid,
                        "estudioId",
                        estudioId
                )
        );


        if (resultados.isEmpty()) {
            throw new IllegalArgumentException(
                    "El honorario seleccionado no existe."
            );
        }


        Map<String, Object> honorario =
                resultados.get(0);


        Long clienteId =
                ((Number) honorario.get("cliente_id"))
                        .longValue();

        Long expedienteId =
                ((Number) honorario.get("expediente_id"))
                        .longValue();

        String modalidad =
                String.valueOf(
                        honorario.get("modalidad_cobro")
                );


        // -----------------------------------------------------
        // VALIDAR MONTO
        // -----------------------------------------------------

        validarMontoPago(
                estudioId,
                honorarioUuid,
                r.monto()
        );


        // -----------------------------------------------------
        // REGISTRAR MOVIMIENTO
        // -----------------------------------------------------

        String sqlMovimiento = """
            INSERT INTO movimientos_financieros (
                cliente_id,
                expediente_id,
                honorario_uuid,
                tipo_operacion,
                modalidad,
                monto,
                fecha,
                etapa_procesal,
                concepto,
                observaciones
            )

            VALUES (
                :clienteId,
                :expedienteId,
                :honorarioUuid,
                'Abono / Pago de Cuota',
                :modalidad,
                :monto,
                :fecha,
                NULL,
                :concepto,
                :observaciones
            )
        """;


        MapSqlParameterSource pMov =
                new MapSqlParameterSource()

                        .addValue(
                                "clienteId",
                                clienteId
                        )

                        .addValue(
                                "expedienteId",
                                expedienteId
                        )

                        .addValue(
                                "honorarioUuid",
                                honorarioUuid
                        )

                        .addValue(
                                "modalidad",
                                modalidad
                        )

                        .addValue(
                                "monto",
                                r.monto()
                        )

                        .addValue(
                                "fecha",
                                r.fechaPago().toLocalDate()
                        )

                        .addValue(
                                "concepto",
                                r.observacion() != null
                                        && !r.observacion().isBlank()
                                                ? r.observacion()
                                                : "Pago de Honorario"
                        )

                        .addValue(
                                "observaciones",
                                r.numeroOperacion()
                        );


        jdbc.update(
                sqlMovimiento,
                pMov
        );


        // -----------------------------------------------------
        // ACTUALIZAR MONTO PAGADO Y ESTADO
        // -----------------------------------------------------

        actualizarMontoPagadoYEstado(
                honorarioUuid,
                r.monto()
        );


        return honorarioUuid;
    }


    // =========================================================
    // ACTUALIZAR MONTO PAGADO Y ESTADO
    // =========================================================
    //
    // Se realizan DOS UPDATE separados.
    //
    // De esta manera primero se actualiza monto_pagado y
    // posteriormente se calcula el estado usando el valor
    // definitivo almacenado.
    //
    // =========================================================

    private void actualizarMontoPagadoYEstado(
            String honorarioUuid,
            BigDecimal monto) {

        int actualizados = jdbc.update(
                """
                UPDATE honorarios

                SET
                    monto_pagado = monto_pagado + :monto

                WHERE uuid = :uuid
                """,

                new MapSqlParameterSource()
                        .addValue("monto", monto)
                        .addValue("uuid", honorarioUuid)
        );


        if (actualizados != 1) {
            throw new IllegalArgumentException(
                    "No se pudo actualizar el monto pagado del honorario."
            );
        }


        actualizados = jdbc.update(
                """
                UPDATE honorarios

                SET
                    estado_pago =
                        CASE
                            WHEN monto_pagado >= monto_pactado
                                THEN 'PAGADO'

                            WHEN monto_pagado > 0
                                THEN 'PARCIAL'

                            ELSE 'PENDIENTE'
                        END

                WHERE uuid = :uuid
                """,

                Map.of(
                        "uuid",
                        honorarioUuid
                )
        );


        if (actualizados != 1) {
            throw new IllegalArgumentException(
                    "No se pudo actualizar el estado del honorario."
            );
        }
    }


    // =========================================================
    // VALIDAR MONTO DEL PAGO
    // =========================================================
    //
    // Impide:
    //
    // - pagos mayores al saldo pendiente
    // - nuevos pagos sobre un honorario ya pagado
    //
    // =========================================================

    private void validarMontoPago(
            Integer estudioId,
            String honorarioUuid,
            BigDecimal monto) {

        if (monto == null
                || monto.compareTo(BigDecimal.ZERO) <= 0) {

            throw new IllegalArgumentException(
                    "El monto del pago debe ser mayor a cero."
            );
        }


        List<Map<String, Object>> resultados = jdbc.queryForList(
                """
                SELECT
                    h.monto_pactado,
                    h.monto_pagado,
                    h.saldo_pendiente,
                    h.estado_pago

                FROM honorarios h

                INNER JOIN clientes c
                    ON c.id = h.cliente_id

                WHERE h.uuid = :uuid
                  AND c.estudio_id = :estudioId
                  AND c.activo = 1
                """,

                new MapSqlParameterSource()
                        .addValue("uuid", honorarioUuid)
                        .addValue("estudioId", estudioId)
        );


        if (resultados.isEmpty()) {
            throw new IllegalArgumentException(
                    "El honorario seleccionado no existe."
            );
        }


        Map<String, Object> honorario =
                resultados.get(0);


        BigDecimal saldoPendiente =
                (BigDecimal) honorario.get("saldo_pendiente");


        if (saldoPendiente == null
                || saldoPendiente.compareTo(BigDecimal.ZERO) <= 0) {

            throw new IllegalArgumentException(
                    "El honorario ya se encuentra completamente pagado."
            );
        }


        if (monto.compareTo(saldoPendiente) > 0) {

            throw new IllegalArgumentException(
                    "El pago no puede ser mayor al saldo pendiente."
            );
        }
    }


    // =========================================================
    // VALIDAR CLIENTE Y EXPEDIENTE
    // =========================================================

    private void validarClienteYExpediente(
            Integer estudioId,
            Long clienteId,
            Long expedienteId) {

        if (clienteId == null) {

            throw new IllegalArgumentException(
                    "Debe seleccionar un cliente."
            );
        }


        if (expedienteId == null) {

            throw new IllegalArgumentException(
                    "Debe seleccionar un expediente."
            );
        }


        Integer cantidad = jdbc.queryForObject(
                """
                SELECT COUNT(*)

                FROM expedientes e

                INNER JOIN clientes c
                    ON c.id = e.cliente_id

                WHERE e.id = :expedienteId
                  AND e.cliente_id = :clienteId
                  AND e.estudio_id = :estudioId
                  AND e.activo = 1
                  AND c.estudio_id = :estudioId
                  AND c.activo = 1
                """,

                new MapSqlParameterSource()

                        .addValue(
                                "expedienteId",
                                expedienteId
                        )

                        .addValue(
                                "clienteId",
                                clienteId
                        )

                        .addValue(
                                "estudioId",
                                estudioId
                        ),

                Integer.class
        );


        if (cantidad == null || cantidad == 0) {

            throw new IllegalArgumentException(
                    "El expediente seleccionado no pertenece al cliente indicado."
            );
        }
    }


    // =========================================================
    // VALIDAR HONORARIO
    // =========================================================

    private void validarHonorario(
            Integer estudioId,
            String honorarioUuid,
            Long clienteId,
            Long expedienteId) {

        if (honorarioUuid == null
                || honorarioUuid.isBlank()) {

            throw new IllegalArgumentException(
                    "Debe seleccionar un honorario para registrar esta operación."
            );
        }


        Integer cantidad = jdbc.queryForObject(
                """
                SELECT COUNT(*)

                FROM honorarios h

                INNER JOIN clientes c
                    ON c.id = h.cliente_id

                WHERE h.uuid = :uuid
                  AND h.cliente_id = :clienteId
                  AND h.expediente_id = :expedienteId
                  AND c.estudio_id = :estudioId
                  AND c.activo = 1
                """,

                new MapSqlParameterSource()

                        .addValue(
                                "uuid",
                                honorarioUuid
                        )

                        .addValue(
                                "clienteId",
                                clienteId
                        )

                        .addValue(
                                "expedienteId",
                                expedienteId
                        )

                        .addValue(
                                "estudioId",
                                estudioId
                        ),

                Integer.class
        );


        if (cantidad == null || cantidad == 0) {

            throw new IllegalArgumentException(
                    "El honorario seleccionado no corresponde al cliente y expediente indicados."
            );
        }
    }


    // =========================================================
    // NORMALIZAR TIPO DE OPERACIÓN
    // =========================================================

    private String normalizarTipoOperacion(
            String tipoOperacion) {

        if (tipoOperacion == null
                || tipoOperacion.isBlank()) {

            return "Honorario Pactado";
        }


        String valor =
                tipoOperacion.trim();


        if (valor.equalsIgnoreCase(
                "Honorario Pactado"
        )) {

            return "Honorario Pactado";
        }


        if (valor.equalsIgnoreCase(
                "Abono / Pago de Cuota"
        )) {

            return "Abono / Pago de Cuota";
        }


        if (valor.equalsIgnoreCase(
                "Gasto Reembolsable"
        )) {

            return "Gasto Reembolsable";
        }


        return valor;
    }


    // =========================================================
    // NORMALIZAR MODALIDAD
    // =========================================================

    private String normalizarModalidad(
            String modalidad) {

        if (modalidad == null
                || modalidad.isBlank()) {

            return "Por Etapas";
        }


        String valor =
                modalidad.trim();


        if (valor.equalsIgnoreCase("Por Etapas")
                || valor.equalsIgnoreCase("ETAPAS")) {

            return "Por Etapas";
        }


        if (valor.equalsIgnoreCase("Monto Fijo / Global")
                || valor.equalsIgnoreCase("Monto Fijo")
                || valor.equalsIgnoreCase("FIJO")) {

            return "Monto Fijo / Global";
        }


        if (valor.equalsIgnoreCase("Cuota Litis")
                || valor.equalsIgnoreCase("Cuota Litis / Éxito")
                || valor.equalsIgnoreCase("Éxito")
                || valor.equalsIgnoreCase("EXITO")
                || valor.equalsIgnoreCase("LITIS")) {

            return "Cuota Litis / Éxito";
        }


        if (valor.equalsIgnoreCase("Mensual")
                || valor.equalsIgnoreCase("MENSUAL")) {

            return "Mensual";
        }


        if (valor.equalsIgnoreCase("Por Hora")
                || valor.equalsIgnoreCase("HORA")) {

            return "Por Hora";
        }


        if (valor.equalsIgnoreCase("Mixta")
                || valor.equalsIgnoreCase("MIXTA")) {

            return "Mixta";
        }


        if (valor.equalsIgnoreCase("Consulta")
                || valor.equalsIgnoreCase("CONSULTA")) {

            return "Consulta";
        }


        if (valor.equalsIgnoreCase("Suscripción Empresarial")
                || valor.equalsIgnoreCase("Suscripcion Empresarial")
                || valor.equalsIgnoreCase("SUSCRIPCION")) {

            return "Suscripción Empresarial";
        }


        throw new IllegalArgumentException(
                "Modalidad de cobro no válida: " + modalidad
        );
    }


    // =========================================================
    // CONVERTIR ID DE MODALIDAD A TEXTO
    // =========================================================

    private String obtenerModalidadTexto(
            Long modalidadId) {

        if (modalidadId == null) {

            return "Por Etapas";
        }


        return switch (modalidadId.intValue()) {

            case 1 ->
                    "Por Etapas";

            case 2 ->
                    "Monto Fijo / Global";

            case 3 ->
                    "Cuota Litis / Éxito";

            case 4 ->
                    "Mensual";

            case 5 ->
                    "Por Hora";

            case 6 ->
                    "Mixta";

            case 7 ->
                    "Consulta";

            case 8 ->
                    "Suscripción Empresarial";

            default ->
                    throw new IllegalArgumentException(
                            "Modalidad de cobro no válida: "
                                    + modalidadId
                    );
        };
    }
}