package com.sbss.lexia.facturacion.service.impl;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sbss.lexia.facturacion.dto.FacturacionDtos.ActualizarHonorarioRequest;
import com.sbss.lexia.facturacion.dto.FacturacionDtos.CatalogosFacturacion;
import com.sbss.lexia.facturacion.dto.FacturacionDtos.DetalleHonorario;
import com.sbss.lexia.facturacion.dto.FacturacionDtos.GuardarHonorarioRequest;
import com.sbss.lexia.facturacion.dto.FacturacionDtos.HonorarioLista;
import com.sbss.lexia.facturacion.dto.FacturacionDtos.PagoHonorarioDetalle;
import com.sbss.lexia.facturacion.dto.FacturacionDtos.RegistrarMovimientoRequest;
import com.sbss.lexia.facturacion.dto.FacturacionDtos.RegistrarPagoRequest;
import com.sbss.lexia.facturacion.dto.FacturacionDtos.ResumenFacturacion;
import com.sbss.lexia.facturacion.repository.FacturacionRepositoryJdbc;
import com.sbss.lexia.facturacion.service.FacturacionService;
import com.sbss.lexia.shared.security.SesionActual;
import com.sbss.lexia.shared.util.CalculadoraPlazos;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class FacturacionServiceImpl implements FacturacionService {

    private final FacturacionRepositoryJdbc repository;

    // =========================================================
    // DATOS DE LA SESIÓN ACTUAL
    // =========================================================

    private Integer getEstudioIdActual() {
        return SesionActual.estudioId();
    }

    private Long getUsuarioIdActual() {
        // Devolvia 1L fijo, asi que todo honorario, movimiento y pago quedaba
        // registrado a nombre del usuario 1 hiciera quien lo hiciera. En un
        // modulo de dinero la trazabilidad es el punto.
        return SesionActual.usuarioId();
    }


    // =========================================================
    // LISTAR HONORARIOS
    // =========================================================

    @Override
    public List<HonorarioLista> listar(
            String cliente,
            String expediente,
            Long modalidadId,
            String estado,
            int limite,
            int desplazamiento) {

        return repository.listar(
                getEstudioIdActual(),
                cliente,
                expediente,
                modalidadId,
                estado,
                limite,
                desplazamiento
        );
    }


    // =========================================================
    // RESUMEN DE FACTURACIÓN
    // =========================================================
    //
    // El resumen incluye:
    //
    // - total facturado
    // - total cobrado
    // - total pendiente
    // - cantidad de facturas pendientes
    // - total vencido
    // - cantidad de cuentas vencidas
    //
    // IMPORTANTE:
    //
    // VENCIDO no es un estado de pago.
    //
    // Los estados continúan siendo:
    //
    // - PENDIENTE
    // - PARCIAL
    // - PAGADO
    //
    // =========================================================

    @Override
    public ResumenFacturacion resumen() {

        return repository.resumen(getEstudioIdActual());
    }


    // =========================================================
    // CATÁLOGOS
    // =========================================================

    @Override
    public CatalogosFacturacion catalogos() {

        return repository.catalogos(getEstudioIdActual());
    }


    // =========================================================
    // CREAR HONORARIO
    // =========================================================

    @Override
    @Transactional
    public String crearHonorario(
            GuardarHonorarioRequest request) {

        return repository.crearHonorario(
                getEstudioIdActual(),
                getUsuarioIdActual(),
                request
        );
    }


    // =========================================================
    // ACTUALIZAR HONORARIO
    // =========================================================
    //
    // Este método será utilizado por el botón "Editar"
    // del frontend.
    //
    // El Repository se encargará de:
    //
    // - validar cliente y expediente
    // - comprobar que el honorario exista
    // - proteger datos si ya existen pagos
    // - actualizar honorarios
    // - actualizar fecha de vencimiento
    // - actualizar el movimiento financiero original
    //
    // =========================================================

    @Override
    @Transactional
    public void actualizarHonorario(
            String honorarioUuid,
            ActualizarHonorarioRequest request) {

        repository.actualizarHonorario(
                getEstudioIdActual(),
                getUsuarioIdActual(),
                honorarioUuid,
                request
        );
    }


    // =========================================================
    // OBTENER DETALLE DEL HONORARIO
    // =========================================================
    //
    // Utilizado posteriormente por:
    //
    // GET /api/v1/facturacion/honorarios/{uuid}/detalle
    //
    // El Repository aplica el filtro por estudioId.
    //
    // =========================================================

    @Override
    @Transactional(readOnly = true)
    public DetalleHonorario obtenerDetalleHonorario(
            String honorarioUuid) {

        validarUuidHonorario(honorarioUuid);

        return repository.obtenerDetalleHonorario(
                getEstudioIdActual(),
                honorarioUuid
        );
    }


    // =========================================================
    // GENERAR COMPROBANTE PDF
    // =========================================================
    //
    // REGLA:
    //
    // El comprobante final solamente se puede generar cuando:
    //
    // - estadoCodigo = PAGADO
    // - saldoPendiente <= 0
    //
    // La validación se realiza nuevamente en el backend.
    // Por tanto, no depende de que Angular oculte el botón.
    //
    // Este documento es una constancia interna de pago
    // generada por LEXIA.
    //
    // No representa por sí mismo un comprobante tributario
    // electrónico emitido por SUNAT.
    //
    // =========================================================

    @Override
    @Transactional(readOnly = true)
    public byte[] generarComprobante(
            String honorarioUuid) {

        validarUuidHonorario(honorarioUuid);

        DetalleHonorario detalle =
                repository.obtenerDetalleHonorario(
                        getEstudioIdActual(),
                        honorarioUuid
                );

        validarEmisionComprobante(detalle);

        try {

            return ComprobantePdf.generar(
                    detalle,
                    repository.obtenerDatosEmisor(getEstudioIdActual()),
                    LocalDate.now(CalculadoraPlazos.ZONA)
            );

        } catch (IOException e) {

            log.error(
                    "Error al generar el comprobante PDF del honorario {}: {}",
                    honorarioUuid,
                    e.getMessage(),
                    e
            );

            throw new IllegalStateException(
                    "No se pudo generar el comprobante de pago.",
                    e
            );
        }
    }


    // =========================================================
    // VALIDAR UUID DEL HONORARIO
    // =========================================================

    private void validarUuidHonorario(
            String honorarioUuid) {

        if (honorarioUuid == null
                || honorarioUuid.isBlank()) {

            throw new IllegalArgumentException(
                    "Debe indicar el honorario."
            );
        }
    }


    // =========================================================
    // VALIDAR EMISIÓN DEL COMPROBANTE
    // =========================================================

    private void validarEmisionComprobante(
            DetalleHonorario detalle) {

        if (detalle == null) {

            throw new IllegalArgumentException(
                    "El honorario seleccionado no existe."
            );
        }

        boolean estadoPagado =
                "PAGADO".equalsIgnoreCase(
                        detalle.estadoCodigo()
                );

        BigDecimal saldo =
                detalle.saldoPendiente() != null
                        ? detalle.saldoPendiente()
                        : BigDecimal.ZERO;

        boolean saldoCancelado =
                saldo.compareTo(BigDecimal.ZERO) <= 0;

        if (!estadoPagado || !saldoCancelado) {

            throw new IllegalStateException(
                    "El comprobante solo puede emitirse cuando el honorario se encuentra completamente pagado."
            );
        }
    }


    // =========================================================
    // REGISTRAR MOVIMIENTO FINANCIERO
    // =========================================================
    //
    // Cuando el movimiento sea "Honorario Pactado",
    // el Repository también almacenará la fecha de vencimiento.
    //
    // =========================================================

    @Override
    @Transactional
    public String registrarMovimiento(
            RegistrarMovimientoRequest request) {

        return repository.registrarMovimiento(
                getEstudioIdActual(),
                getUsuarioIdActual(),
                request
        );
    }


    // =========================================================
    // REGISTRAR PAGO
    // =========================================================

    @Override
    @Transactional
    public String registrarPago(
            String honorarioUuid,
            RegistrarPagoRequest request) {

        return repository.registrarPago(
                getEstudioIdActual(),
                getUsuarioIdActual(),
                honorarioUuid,
                request
        );
    }
}