package com.sbss.lexia.facturacion.service.impl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sbss.lexia.facturacion.dto.DatosEmisor;
import com.sbss.lexia.facturacion.dto.FacturacionDtos.DetalleHonorario;
import com.sbss.lexia.facturacion.dto.FacturacionDtos.PagoHonorarioDetalle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * El comprobante se arma sin base de datos: estas pruebas le dan datos
 * sinteticos, incluidos los que rompian a PDFBox (emojis, saltos de linea,
 * campos nulos) y un historial lo bastante largo para saltar de pagina.
 *
 * Para VER el resultado, apunta LEXIA_PDF_SALIDA a una carpeta: cada prueba deja
 * ahi el PDF y un PNG por pagina.
 */
class ComprobantePdfTest {

    private static final LocalDate HOY = LocalDate.of(2026, 11, 21);

    private static final DatosEmisor EMISOR = new DatosEmisor(
            "Estudio Ramirez Abogados S.A.C.", "Estudio Ramirez Abogados", "20521478963",
            "Av. Larco 1234, Urb. Los Jardines, Trujillo", "044-201548", "contacto@lexia.com");

    @Test
    @DisplayName("un historial largo salta de pagina y cada pagina trae cabecera y pie")
    void historialLargoOcupaVariasPaginas() throws Exception {
        List<PagoHonorarioDetalle> pagos = new ArrayList<>();
        for (int i = 1; i <= 25; i++) {
            pagos.add(new PagoHonorarioDetalle((long) i, HOY.minusDays(200 - i * 5),
                    new BigDecimal("120.00"), "Cuota " + i + " de honorarios",
                    i % 3 == 0 ? "Transferencia BCP, operacion 00" + i : null));
        }
        DetalleHonorario detalle = detalle("Defensa en proceso de alimentos", "Mixta", "Etapa postulatoria",
                "Pagado en cuotas mensuales.", new BigDecimal("3000.00"), new BigDecimal("3000.00"),
                BigDecimal.ZERO, "PEN", pagos);

        byte[] pdf = ComprobantePdf.generar(detalle, EMISOR, HOY);
        guardar("largo", pdf);

        try (PDDocument documento = Loader.loadPDF(pdf)) {
            assertTrue(documento.getNumberOfPages() >= 2, "25 pagos deben ocupar mas de una pagina");
            String texto = new PDFTextStripper().getText(documento);
            assertTrue(texto.contains("20521478963"), "falta el RUC del estudio");
            assertTrue(texto.contains("Estudio Ramirez Abogados S.A.C."), "falta la razon social");
            assertTrue(texto.contains("COMPROBANTE DE PAGO"));
            assertTrue(texto.contains("CANCELADO"));
            assertTrue(texto.contains("S/ 3,000.00"), "falta el total pagado");
            assertTrue(texto.contains("Cuota 25 de honorarios"), "el ultimo pago debe aparecer");
            assertTrue(texto.contains("Página 1 de " + documento.getNumberOfPages()));
            assertTrue(texto.contains("PAGOS REGISTRADOS (continuación)"), "falta la cabecera de la 2.ª pagina");
            assertTrue(texto.contains("No constituye por sí misma"), "el aviso de que no es boleta de SUNAT debe verse");
        }
    }

    @Test
    @DisplayName("emojis, saltos de linea, campos nulos y dolares no rompen el PDF")
    void datosHostilesNoRompen() throws Exception {
        List<PagoHonorarioDetalle> pagos = List.of(
                new PagoHonorarioDetalle(1L, null, new BigDecimal("500"), null, "Pagó 😀 por Yape\r\nvía → BCP\tOK"),
                new PagoHonorarioDetalle(2L, HOY, null, "Anticipo " + "x".repeat(400), null));
        DetalleHonorario detalle = detalle(
                "Asesoría integral 🧾 del caso\nsegunda línea " + "palabralarga".repeat(30),
                null, null, "Observación con “comillas” y – guiones ✓",
                new BigDecimal("1500.5"), new BigDecimal("1500.5"), BigDecimal.ZERO, "USD", pagos);
        DatosEmisor sinContacto = new DatosEmisor("Estudio Sin Nombre Comercial S.A.C.", null, "20100070970",
                null, null, null);

        byte[] pdf = assertDoesNotThrow(() -> ComprobantePdf.generar(detalle, sinContacto, HOY));
        guardar("hostil", pdf);

        try (PDDocument documento = Loader.loadPDF(pdf)) {
            String texto = new PDFTextStripper().getText(documento);
            assertTrue(texto.contains("USD 1,500.50"), "el monto en dolares debe llevar su moneda");
            assertTrue(texto.contains("Estudio Sin Nombre Comercial S.A.C."),
                    "sin nombre comercial se usa la razon social");
            assertTrue(texto.contains("20100070970"));
            assertFalse(texto.contains("😀"), "un emoji no se puede dibujar con Helvetica");
        }
    }

    @Test
    @DisplayName("sin pagos registrados lo dice y no falla")
    void sinPagos() throws Exception {
        DetalleHonorario detalle = detalle("Consulta", "Consulta", null, null, new BigDecimal("150"),
                new BigDecimal("150"), BigDecimal.ZERO, "PEN", null);

        byte[] pdf = ComprobantePdf.generar(detalle, EMISOR, HOY);
        guardar("sin-pagos", pdf);

        try (PDDocument documento = Loader.loadPDF(pdf)) {
            assertEquals(1, documento.getNumberOfPages());
            assertTrue(new PDFTextStripper().getText(documento).contains("No existen pagos registrados."));
        }
    }

    // ---- apoyo ----

    private static DetalleHonorario detalle(String concepto, String modalidad, String etapa, String observaciones,
            BigDecimal pactado, BigDecimal pagado, BigDecimal saldo, String moneda,
            List<PagoHonorarioDetalle> pagos) {
        return new DetalleHonorario("3f9a21c4-7b1e-4d52-9c80-a1b2c3d4e5f6", 1L, 2L,
                "María Elena Quispe Rodríguez", "42871563", "01452-2026-0-1601-JR-FC-03",
                concepto, modalidad, etapa, observaciones, pactado, pagado, saldo, moneda,
                "PAGADO", "Pagado", HOY.minusDays(210), HOY.minusDays(30), false, true, pagos);
    }

    private static void guardar(String nombre, byte[] pdf) throws IOException {
        String carpeta = System.getenv("LEXIA_PDF_SALIDA");
        if (carpeta == null || carpeta.isBlank()) {
            return;
        }
        Path destino = Path.of(carpeta);
        Files.createDirectories(destino);
        Files.write(destino.resolve("comprobante-" + nombre + ".pdf"), pdf);
        try (PDDocument documento = Loader.loadPDF(pdf)) {
            PDFRenderer renderizador = new PDFRenderer(documento);
            for (int i = 0; i < documento.getNumberOfPages(); i++) {
                BufferedImage imagen = renderizador.renderImageWithDPI(i, 110);
                ImageIO.write(imagen, "png", destino.resolve("comprobante-" + nombre + "-p" + (i + 1) + ".png").toFile());
            }
        }
    }
}
