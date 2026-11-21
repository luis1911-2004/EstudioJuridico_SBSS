package com.sbss.lexia.facturacion.dto;

/**
 * Datos del estudio que aparecen como emisor en el comprobante de pago.
 * Salen de la tabla `estudios` y solo viajan al PDF: no es un contrato de la API.
 */
public record DatosEmisor(
        String razonSocial,
        String nombreComercial,
        String ruc,
        String direccion,
        String telefono,
        String email) {
}
