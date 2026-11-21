package com.sbss.lexia.facturacion.service.impl;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.util.Matrix;

import com.sbss.lexia.facturacion.dto.DatosEmisor;
import com.sbss.lexia.facturacion.dto.FacturacionDtos.DetalleHonorario;
import com.sbss.lexia.facturacion.dto.FacturacionDtos.PagoHonorarioDetalle;

/**
 * Dibuja el comprobante de pago de un honorario (A4, con la paleta de LEXIA).
 *
 * Es una CONSTANCIA INTERNA: tiene la disposicion de un recibo (emisor y RUC
 * arriba, cliente, detalle, pagos y totales), pero no usa serie ni correlativo
 * de SUNAT ni se presenta como boleta o factura electronica. El pie lo dice en
 * todas las paginas.
 *
 * Solo usa fuentes estandar (Helvetica, WinAnsi): todo texto que llega de la
 * base se limpia antes de dibujarlo, porque PDFBox lanza excepcion ante un
 * emoji, una flecha o un salto de linea. Sin base de datos: recibe los datos ya
 * cargados, asi que se prueba con datos sinteticos.
 */
final class ComprobantePdf {

    // ---- Paleta (la del menu y los acentos de la aplicacion) ----
    private static final Color AZUL_NOCHE = new Color(0x0F, 0x17, 0x2A);
    private static final Color INDIGO = new Color(0x63, 0x66, 0xF1);
    private static final Color INDIGO_OSCURO = new Color(0x4F, 0x46, 0xE5);
    private static final Color TEXTO = new Color(0x1E, 0x29, 0x3B);
    private static final Color APAGADO = new Color(0x64, 0x74, 0x8B);
    private static final Color CLARO = new Color(0xCB, 0xD5, 0xE1);
    private static final Color LINEA = new Color(0xE2, 0xE8, 0xF0);
    private static final Color FONDO_SUAVE = new Color(0xF8, 0xFA, 0xFC);
    private static final Color INDIGO_SUAVE = new Color(0xEE, 0xF2, 0xFF);
    private static final Color VERDE = new Color(0x15, 0x80, 0x3D);
    private static final Color BLANCO = Color.WHITE;

    // ---- Medidas (puntos; A4 = 595 x 842) ----
    private static final float ANCHO = PDRectangle.A4.getWidth();
    private static final float ALTO = PDRectangle.A4.getHeight();
    private static final float MARGEN = 40f;
    private static final float ANCHO_UTIL = ANCHO - 2 * MARGEN;
    private static final float ALTO_CABECERA = 112f;
    private static final float ALTO_CABECERA_CONTINUACION = 34f;
    /** Por debajo de esta altura empieza el pie: nada se dibuja ahi. */
    private static final float LIMITE_INFERIOR = 84f;
    private static final int MAXIMO_CARACTERES_CAMPO = 1200;

    private static final DateTimeFormatter FORMATO_FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final Locale LOCALE_PERU = Locale.forLanguageTag("es-PE");
    private static final String RECURSO_LOGO = "/facturacion/logo-lexia.png";

    private static final String AVISO_LEGAL =
            "Constancia interna de pago generada por LEXIA. No constituye por sí misma "
                    + "una factura o boleta electrónica emitida ante SUNAT.";

    private final DetalleHonorario detalle;
    private final DatosEmisor emisor;
    private final LocalDate fechaEmision;
    private final String referencia;

    private PDDocument documento;
    private PDFont normal;
    private PDFont negrita;
    private PDImageXObject logo;
    private PDPageContentStream lienzo;
    private float y;
    private int paginas;

    private ComprobantePdf(DetalleHonorario detalle, DatosEmisor emisor, LocalDate fechaEmision) {
        this.detalle = detalle;
        this.emisor = emisor;
        this.fechaEmision = fechaEmision;
        this.referencia = referenciaCorta(detalle.uuid());
    }

    /** Genera el PDF. `fechaEmision` es la fecha del comprobante (hoy, en hora de Lima). */
    static byte[] generar(DetalleHonorario detalle, DatosEmisor emisor, LocalDate fechaEmision)
            throws IOException {
        return new ComprobantePdf(detalle, emisor, fechaEmision).construir();
    }

    // =========================================================
    // CONSTRUCCION
    // =========================================================

    private byte[] construir() throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream salida = new ByteArrayOutputStream()) {
            documento = doc;
            normal = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            negrita = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            logo = cargarLogo(doc);

            try {
                paginaNueva(true);
                tarjetas();
                tablaHonorario();
                tablaPagos();
                totales();
            } finally {
                if (lienzo != null) {
                    lienzo.close();
                }
            }

            pieEnTodasLasPaginas();
            doc.save(salida);
            return salida.toByteArray();
        }
    }

    private PDImageXObject cargarLogo(PDDocument doc) {
        try (InputStream in = ComprobantePdf.class.getResourceAsStream(RECURSO_LOGO)) {
            return in == null ? null : PDImageXObject.createFromByteArray(doc, in.readAllBytes(), "logo-lexia");
        } catch (IOException | RuntimeException e) {
            // Un comprobante sin logo es mejor que ningun comprobante.
            return null;
        }
    }

    // =========================================================
    // PAGINAS Y CABECERAS
    // =========================================================

    private void paginaNueva(boolean primera) throws IOException {
        if (lienzo != null) {
            lienzo.close();
        }
        PDPage pagina = new PDPage(PDRectangle.A4);
        documento.addPage(pagina);
        paginas++;
        lienzo = new PDPageContentStream(documento, pagina);
        if (primera) {
            cabeceraPrincipal();
            y = ALTO - ALTO_CABECERA - 24;
        } else {
            cabeceraContinuacion();
            y = ALTO - ALTO_CABECERA_CONTINUACION - 26;
        }
    }

    /** Franja azul noche con el emisor a la izquierda y el recuadro del RUC a la derecha. */
    private void cabeceraPrincipal() throws IOException {
        rectangulo(0, ALTO - ALTO_CABECERA, ANCHO, ALTO_CABECERA, AZUL_NOCHE);
        rectangulo(0, ALTO - ALTO_CABECERA - 3, ANCHO, 3, INDIGO);

        float xTexto = MARGEN;
        if (logo != null) {
            float lado = 62f;
            lienzo.drawImage(logo, MARGEN, ALTO - ALTO_CABECERA / 2 - lado / 2, lado, lado * logo.getHeight() / logo.getWidth());
            xTexto = MARGEN + lado + 14;
        }

        String nombre = vacioSiNulo(emisor.nombreComercial()).isBlank()
                ? vacioSiNulo(emisor.razonSocial()) : emisor.nombreComercial();
        float anchoTexto = 360 - xTexto;
        float yTexto = ALTO - 38;
        // El nombre se achica hasta caber; si ni a 10 pt cabe, se recorta.
        float tamanoNombre = 15;
        while (tamanoNombre > 10 && anchoDe(negrita, tamanoNombre, limpiar(negrita, nombre)) > anchoTexto) {
            tamanoNombre -= 0.5f;
        }
        texto(negrita, tamanoNombre, xTexto, yTexto, envolver(negrita, tamanoNombre, anchoTexto, nombre, 1).get(0), BLANCO);
        yTexto -= 14;

        String razon = vacioSiNulo(emisor.razonSocial());
        if (!razon.isBlank() && !razon.equalsIgnoreCase(nombre)) {
            texto(normal, 8.5f, xTexto, yTexto, envolver(normal, 8.5f, anchoTexto, razon, 1).get(0), CLARO);
            yTexto -= 13;
        }
        if (!vacioSiNulo(emisor.direccion()).isBlank()) {
            for (String linea : envolver(normal, 8, anchoTexto, emisor.direccion(), 2)) {
                texto(normal, 8, xTexto, yTexto, linea, CLARO);
                yTexto -= 10.5f;
            }
        }
        String contacto = unir("  |  ", prefijo("Tel. ", emisor.telefono()), emisor.email());
        if (!contacto.isBlank()) {
            texto(normal, 8, xTexto, yTexto, envolver(normal, 8, anchoTexto, contacto, 1).get(0), CLARO);
        }

        // Recuadro del RUC, a la manera de un recibo.
        float anchoCaja = 186f;
        float xCaja = ANCHO - MARGEN - anchoCaja;
        float altoCaja = 88f;
        float yCaja = ALTO - 16 - altoCaja;
        rectangulo(xCaja, yCaja, anchoCaja, altoCaja, BLANCO);
        rectangulo(xCaja, yCaja + 32, anchoCaja, 26, INDIGO);
        textoCentrado(negrita, 11.5f, xCaja + anchoCaja / 2, yCaja + altoCaja - 22,
                "R.U.C. " + textoSeguro(emisor.ruc()), AZUL_NOCHE);
        textoCentrado(negrita, 10, xCaja + anchoCaja / 2, yCaja + 41, "COMPROBANTE DE PAGO", BLANCO);
        textoCentrado(negrita, 11.5f, xCaja + anchoCaja / 2, yCaja + 12, "Ref. " + referencia, AZUL_NOCHE);
    }

    /** Franja delgada para las paginas siguientes. */
    private void cabeceraContinuacion() throws IOException {
        rectangulo(0, ALTO - ALTO_CABECERA_CONTINUACION, ANCHO, ALTO_CABECERA_CONTINUACION, AZUL_NOCHE);
        rectangulo(0, ALTO - ALTO_CABECERA_CONTINUACION - 2, ANCHO, 2, INDIGO);
        String nombre = vacioSiNulo(emisor.nombreComercial()).isBlank()
                ? vacioSiNulo(emisor.razonSocial()) : emisor.nombreComercial();
        texto(negrita, 10, MARGEN, ALTO - 21, nombre, BLANCO);
        textoDerecha(normal, 9, ANCHO - MARGEN, ALTO - 21,
                "Comprobante de pago  |  Ref. " + referencia, CLARO);
    }

    // =========================================================
    // TARJETAS: CLIENTE Y DATOS DEL COMPROBANTE
    // =========================================================

    private void tarjetas() throws IOException {
        float separacion = 14f;
        float ancho = (ANCHO_UTIL - separacion) / 2;

        List<String[]> cliente = List.of(
                new String[] {"Nombre", detalle.clienteNombre()},
                new String[] {"Documento", detalle.clienteDocumento()},
                new String[] {"Expediente", detalle.expedienteNumero()});
        List<String[]> comprobante = List.of(
                new String[] {"Fecha de emisión", formatearFecha(fechaEmision)},
                new String[] {"Honorario emitido el", formatearFecha(detalle.fechaEmision())},
                new String[] {"Vencimiento", formatearFecha(detalle.fechaVencimiento())},
                new String[] {"Estado", detalle.estadoNombre()});

        float alto = Math.max(alturaTarjeta(cliente, ancho), alturaTarjeta(comprobante, ancho));
        dibujarTarjeta(MARGEN, y, ancho, alto, "DATOS DEL CLIENTE", cliente);
        dibujarTarjeta(MARGEN + ancho + separacion, y, ancho, alto, "DATOS DEL COMPROBANTE", comprobante);
        y -= alto + 22;
    }

    private float alturaTarjeta(List<String[]> campos, float ancho) throws IOException {
        float altura = 34f;
        for (String[] campo : campos) {
            altura += 11 + envolver(negrita, 9.5f, ancho - 26, campo[1], 3).size() * 12.5f + 6;
        }
        return altura + 2;
    }

    private void dibujarTarjeta(float x, float yArriba, float ancho, float alto, String titulo,
            List<String[]> campos) throws IOException {
        rectangulo(x, yArriba - alto, ancho, alto, FONDO_SUAVE);
        contorno(x, yArriba - alto, ancho, alto, LINEA);
        rectangulo(x, yArriba - alto, 4, alto, INDIGO);
        texto(negrita, 8, x + 16, yArriba - 17, titulo, INDIGO_OSCURO);
        linea(x + 16, yArriba - 24, x + ancho - 12, yArriba - 24, LINEA);

        float yCampo = yArriba - 38;
        for (String[] campo : campos) {
            texto(normal, 7.5f, x + 16, yCampo, campo[0].toUpperCase(LOCALE_PERU), APAGADO);
            yCampo -= 12;
            for (String lineaValor : envolver(negrita, 9.5f, ancho - 26, campo[1], 3)) {
                texto(negrita, 9.5f, x + 16, yCampo, lineaValor, TEXTO);
                yCampo -= 12.5f;
            }
            yCampo -= 5;
        }
    }

    // =========================================================
    // TABLA: DETALLE DEL HONORARIO
    // =========================================================

    private void tablaHonorario() throws IOException {
        float anchoImporte = 110f;
        float anchoDescripcion = ANCHO_UTIL - anchoImporte - 24;

        List<String> lineasConcepto = envolver(negrita, 9.5f, anchoDescripcion, detalle.concepto(), 4);
        String sub = unir("   |   ", prefijo("Modalidad: ", detalle.modalidadCobro()),
                prefijo("Etapa procesal: ", detalle.etapaProcesal()));
        List<String> lineasSub = sub.isBlank() ? List.of() : envolver(normal, 8, anchoDescripcion, sub, 2);
        List<String> lineasObs = vacioSiNulo(detalle.observaciones()).isBlank()
                ? List.of() : envolver(normal, 8, anchoDescripcion, "Observaciones: " + detalle.observaciones(), 4);

        float alturaFila = 14 + lineasConcepto.size() * 12.5f + lineasSub.size() * 10.5f
                + lineasObs.size() * 10.5f + 4;
        asegurarEspacio(26 + alturaFila + 20);

        encabezadoTabla("DETALLE DEL HONORARIO", "IMPORTE", ANCHO - MARGEN - 12);
        float yFila = y - 8;
        for (String l : lineasConcepto) {
            yFila -= 12.5f;
            texto(negrita, 9.5f, MARGEN + 12, yFila, l, TEXTO);
        }
        for (String l : lineasSub) {
            yFila -= 10.5f;
            texto(normal, 8, MARGEN + 12, yFila, l, APAGADO);
        }
        for (String l : lineasObs) {
            yFila -= 10.5f;
            texto(normal, 8, MARGEN + 12, yFila, l, APAGADO);
        }
        textoDerecha(negrita, 9.5f, ANCHO - MARGEN - 12, y - 8 - 12.5f,
                formatearMoneda(detalle.montoPactado(), detalle.moneda()), TEXTO);
        y = y - 8 - (alturaFila - 14) - 6;
        linea(MARGEN, y, ANCHO - MARGEN, y, LINEA);
        y -= 26;
    }

    // =========================================================
    // TABLA: PAGOS REGISTRADOS
    // =========================================================

    private void tablaPagos() throws IOException {
        float xFecha = MARGEN + 40;
        float xDetalle = MARGEN + 118;
        float xMonto = ANCHO - MARGEN - 12;
        float anchoDetalle = xMonto - 112 - xDetalle;

        asegurarEspacio(14 + 26 + 40);
        texto(negrita, 9, MARGEN, y, "PAGOS REGISTRADOS", INDIGO_OSCURO);
        y -= 10;
        encabezadoPagos(xFecha, xDetalle, xMonto);

        List<PagoHonorarioDetalle> pagos = detalle.pagos() != null ? detalle.pagos() : List.of();
        if (pagos.isEmpty()) {
            texto(normal, 9, MARGEN + 12, y - 16, "No existen pagos registrados.", APAGADO);
            y -= 30;
            return;
        }

        int numero = 1;
        for (PagoHonorarioDetalle pago : pagos) {
            String concepto = vacioSiNulo(pago.concepto()).isBlank() ? "Pago de honorarios" : pago.concepto();
            List<String> lineasConcepto = envolver(normal, 9, anchoDetalle, concepto, 4);
            List<String> lineasObs = vacioSiNulo(pago.observaciones()).isBlank()
                    ? List.of() : envolver(normal, 8, anchoDetalle, pago.observaciones(), 4);
            float alturaFila = 14 + 11.5f * (lineasConcepto.size() - 1)
                    + (lineasObs.isEmpty() ? 0 : 11 + 10 * (lineasObs.size() - 1)) + 8;

            if (y - alturaFila < LIMITE_INFERIOR) {
                paginaNueva(false);
                texto(negrita, 9, MARGEN, y, "PAGOS REGISTRADOS (continuación)", INDIGO_OSCURO);
                y -= 10;
                encabezadoPagos(xFecha, xDetalle, xMonto);
            }

            if (numero % 2 == 0) {
                rectangulo(MARGEN, y - alturaFila, ANCHO_UTIL, alturaFila, FONDO_SUAVE);
            }
            float yTexto = y - 14;
            texto(normal, 9, MARGEN + 12, yTexto, String.valueOf(numero), APAGADO);
            texto(normal, 9, xFecha, yTexto, formatearFecha(pago.fecha()), TEXTO);
            textoDerecha(negrita, 9, xMonto, yTexto, formatearMoneda(pago.monto(), detalle.moneda()), TEXTO);
            float yLinea = yTexto;
            for (String l : lineasConcepto) {
                texto(normal, 9, xDetalle, yLinea, l, TEXTO);
                yLinea -= 11.5f;
            }
            yLinea += 11.5f - 11;
            for (String l : lineasObs) {
                texto(normal, 8, xDetalle, yLinea, l, APAGADO);
                yLinea -= 10;
            }
            y -= alturaFila;
            linea(MARGEN, y, ANCHO - MARGEN, y, LINEA);
            numero++;
        }
        y -= 24;
    }

    private void encabezadoPagos(float xFecha, float xDetalle, float xMonto) throws IOException {
        rectangulo(MARGEN, y - 22, ANCHO_UTIL, 22, INDIGO);
        texto(negrita, 8, MARGEN + 12, y - 14.5f, "N.º", BLANCO);
        texto(negrita, 8, xFecha, y - 14.5f, "FECHA", BLANCO);
        texto(negrita, 8, xDetalle, y - 14.5f, "DETALLE DEL PAGO", BLANCO);
        textoDerecha(negrita, 8, xMonto, y - 14.5f, "MONTO", BLANCO);
        y -= 22;
    }

    private void encabezadoTabla(String izquierda, String derecha, float xDerecha) throws IOException {
        rectangulo(MARGEN, y - 22, ANCHO_UTIL, 22, INDIGO);
        texto(negrita, 8, MARGEN + 12, y - 14.5f, izquierda, BLANCO);
        textoDerecha(negrita, 8, xDerecha, y - 14.5f, derecha, BLANCO);
        y -= 22;
    }

    // =========================================================
    // TOTALES Y SELLO
    // =========================================================

    private void totales() throws IOException {
        float ancho = 232f;
        float x = ANCHO - MARGEN - ancho;
        asegurarEspacio(112);

        float yArriba = y;
        String[][] filas = {
            {"Monto pactado", formatearMoneda(detalle.montoPactado(), detalle.moneda())},
            {"Total pagado", formatearMoneda(detalle.montoPagado(), detalle.moneda())},
            {"Saldo pendiente", formatearMoneda(detalle.saldoPendiente(), detalle.moneda())},
        };
        float yFila = yArriba;
        for (String[] fila : filas) {
            texto(normal, 9.5f, x + 12, yFila - 15, fila[0], APAGADO);
            textoDerecha(negrita, 9.5f, x + ancho - 12, yFila - 15, fila[1], TEXTO);
            yFila -= 24;
            linea(x, yFila, x + ancho, yFila, LINEA);
        }
        rectangulo(x, yFila - 32, ancho, 32, AZUL_NOCHE);
        rectangulo(x, yFila - 32, 4, 32, INDIGO);
        texto(negrita, 9.5f, x + 14, yFila - 20, "TOTAL PAGADO", BLANCO);
        textoDerecha(negrita, 13, x + ancho - 12, yFila - 21, formatearMoneda(detalle.montoPagado(), detalle.moneda()), BLANCO);

        sello(MARGEN + 100, yArriba - 52);
        y = yFila - 32 - 20;
    }

    /** Sello "CANCELADO" inclinado: el honorario esta pagado por completo. */
    private void sello(float centroX, float centroY) throws IOException {
        float ancho = 140f;
        float alto = 38f;
        lienzo.saveGraphicsState();
        lienzo.transform(Matrix.getRotateInstance(Math.toRadians(10), centroX, centroY));
        lienzo.setStrokingColor(VERDE);
        lienzo.setLineWidth(2.2f);
        lienzo.addRect(-ancho / 2, -alto / 2, ancho, alto);
        lienzo.stroke();
        lienzo.setLineWidth(0.8f);
        lienzo.addRect(-ancho / 2 + 3, -alto / 2 + 3, ancho - 6, alto - 6);
        lienzo.stroke();
        float anchoTexto = negrita.getStringWidth("CANCELADO") / 1000f * 20;
        lienzo.beginText();
        lienzo.setFont(negrita, 20);
        lienzo.setNonStrokingColor(VERDE);
        lienzo.newLineAtOffset(-anchoTexto / 2, -7);
        lienzo.showText("CANCELADO");
        lienzo.endText();
        lienzo.restoreGraphicsState();
    }

    // =========================================================
    // PIE (SE DIBUJA AL FINAL, CUANDO SE SABE EL TOTAL DE PAGINAS)
    // =========================================================

    private void pieEnTodasLasPaginas() throws IOException {
        int total = documento.getNumberOfPages();
        for (int i = 0; i < total; i++) {
            PDPage pagina = documento.getPage(i);
            try (PDPageContentStream pie = new PDPageContentStream(
                    documento, pagina, PDPageContentStream.AppendMode.APPEND, true, true)) {
                lienzo = pie;
                rectangulo(0, 0, ANCHO, 5, INDIGO);
                linea(MARGEN, 66, ANCHO - MARGEN, 66, LINEA);
                float yLinea = 54;
                for (String l : envolver(normal, 7.5f, 380, AVISO_LEGAL, 3)) {
                    texto(normal, 7.5f, MARGEN, yLinea, l, APAGADO);
                    yLinea -= 9.5f;
                }
                textoDerecha(negrita, 8, ANCHO - MARGEN, 54, "Página " + (i + 1) + " de " + total, APAGADO);
                textoDerecha(normal, 7.5f, ANCHO - MARGEN, 44, "Emitido el " + formatearFecha(fechaEmision), APAGADO);
                texto(normal, 6.5f, MARGEN, 22, "Ref. completa: " + textoSeguro(detalle.uuid()), APAGADO);
            }
        }
        lienzo = null;
    }

    // =========================================================
    // PAGINACION
    // =========================================================

    private void asegurarEspacio(float necesario) throws IOException {
        if (y - necesario < LIMITE_INFERIOR) {
            paginaNueva(false);
        }
    }

    // =========================================================
    // PRIMITIVAS DE DIBUJO
    // =========================================================

    private void rectangulo(float x, float yBase, float ancho, float alto, Color color) throws IOException {
        lienzo.setNonStrokingColor(color);
        lienzo.addRect(x, yBase, ancho, alto);
        lienzo.fill();
    }

    private void contorno(float x, float yBase, float ancho, float alto, Color color) throws IOException {
        lienzo.setStrokingColor(color);
        lienzo.setLineWidth(0.7f);
        lienzo.addRect(x, yBase, ancho, alto);
        lienzo.stroke();
    }

    private void linea(float x1, float y1, float x2, float y2, Color color) throws IOException {
        lienzo.setStrokingColor(color);
        lienzo.setLineWidth(0.7f);
        lienzo.moveTo(x1, y1);
        lienzo.lineTo(x2, y2);
        lienzo.stroke();
    }

    private void texto(PDFont fuente, float tamano, float x, float yBase, String texto, Color color)
            throws IOException {
        String limpio = limpiar(fuente, texto);
        if (limpio.isEmpty()) {
            return;
        }
        lienzo.beginText();
        lienzo.setFont(fuente, tamano);
        lienzo.setNonStrokingColor(color);
        lienzo.newLineAtOffset(x, yBase);
        lienzo.showText(limpio);
        lienzo.endText();
    }

    private void textoDerecha(PDFont fuente, float tamano, float xDerecha, float yBase, String texto, Color color)
            throws IOException {
        String limpio = limpiar(fuente, texto);
        texto(fuente, tamano, xDerecha - fuente.getStringWidth(limpio) / 1000f * tamano, yBase, limpio, color);
    }

    private void textoCentrado(PDFont fuente, float tamano, float xCentro, float yBase, String texto, Color color)
            throws IOException {
        String limpio = limpiar(fuente, texto);
        texto(fuente, tamano, xCentro - fuente.getStringWidth(limpio) / 1000f * tamano / 2, yBase, limpio, color);
    }

    // =========================================================
    // TEXTO: LIMPIEZA Y AJUSTE DE LINEA
    // =========================================================

    /**
     * Deja el texto dibujable con la fuente: saltos y tabulaciones pasan a espacio,
     * los signos tipograficos de Word se normalizan y lo que la fuente no puede
     * codificar (emojis, flechas) se reemplaza por "?".
     */
    private String limpiar(PDFont fuente, String texto) {
        if (texto == null) {
            return "";
        }
        String base = texto
                .replace('‘', '\'')
                .replace('’', '\'')
                .replace('“', '"')
                .replace('”', '"')
                .replace('–', '-')
                .replace('—', '-')
                .replace('•', '-')
                .replace(' ', ' ')
                .replaceAll("[\\p{Cntrl}\\s]+", " ")
                .trim();
        StringBuilder limpio = new StringBuilder(base.length());
        base.codePoints().forEach(cp -> {
            String caracter = new String(Character.toChars(cp));
            try {
                fuente.encode(caracter);
                limpio.append(caracter);
            } catch (IOException | IllegalArgumentException e) {
                limpio.append('?');
            }
        });
        return limpio.toString();
    }

    /**
     * Parte el texto en lineas de a lo sumo `ancho` puntos. Si hay mas de
     * `maximoLineas`, la ultima termina en "...". Una palabra mas ancha que la
     * linea se corta por caracteres. Nunca devuelve una lista vacia.
     */
    private List<String> envolver(PDFont fuente, float tamano, float ancho, String texto, int maximoLineas)
            throws IOException {
        String limpio = limpiar(fuente, texto);
        if (limpio.length() > MAXIMO_CARACTERES_CAMPO) {
            limpio = limpio.substring(0, MAXIMO_CARACTERES_CAMPO);
        }
        if (limpio.isEmpty()) {
            return List.of("-");
        }

        List<String> lineas = new ArrayList<>();
        StringBuilder actual = new StringBuilder();
        for (String palabra : limpio.split(" ")) {
            String candidata = actual.length() == 0 ? palabra : actual + " " + palabra;
            if (anchoDe(fuente, tamano, candidata) <= ancho) {
                actual = new StringBuilder(candidata);
                continue;
            }
            if (actual.length() > 0) {
                lineas.add(actual.toString());
                actual = new StringBuilder();
            }
            String resto = palabra;
            while (anchoDe(fuente, tamano, resto) > ancho && resto.length() > 1) {
                int corte = resto.length() - 1;
                while (corte > 1 && anchoDe(fuente, tamano, resto.substring(0, corte)) > ancho) {
                    corte--;
                }
                lineas.add(resto.substring(0, corte));
                resto = resto.substring(corte);
            }
            actual = new StringBuilder(resto);
        }
        if (actual.length() > 0) {
            lineas.add(actual.toString());
        }

        if (lineas.size() > maximoLineas) {
            lineas = new ArrayList<>(lineas.subList(0, maximoLineas));
            String ultima = lineas.get(maximoLineas - 1);
            while (ultima.length() > 1 && anchoDe(fuente, tamano, ultima + "...") > ancho) {
                ultima = ultima.substring(0, ultima.length() - 1);
            }
            lineas.set(maximoLineas - 1, ultima + "...");
        }
        return lineas;
    }

    private float anchoDe(PDFont fuente, float tamano, String texto) throws IOException {
        return fuente.getStringWidth(texto) / 1000f * tamano;
    }

    // =========================================================
    // FORMATO
    // =========================================================

    private static String referenciaCorta(String uuid) {
        String limpio = uuid == null ? "" : uuid.replace("-", "").toUpperCase(Locale.ROOT);
        return limpio.isEmpty() ? "-" : limpio.substring(0, Math.min(8, limpio.length()));
    }

    private static String vacioSiNulo(String valor) {
        return valor == null ? "" : valor;
    }

    private static String textoSeguro(String valor) {
        return valor == null || valor.isBlank() ? "-" : valor.trim();
    }

    private static String prefijo(String prefijo, String valor) {
        return valor == null || valor.isBlank() ? "" : prefijo + valor.trim();
    }

    private static String unir(String separador, String... partes) {
        List<String> presentes = new ArrayList<>();
        for (String parte : partes) {
            if (parte != null && !parte.isBlank()) {
                presentes.add(parte.trim());
            }
        }
        return String.join(separador, presentes);
    }

    private static String formatearFecha(LocalDate fecha) {
        return fecha == null ? "-" : fecha.format(FORMATO_FECHA);
    }

    private static String formatearMoneda(BigDecimal monto, String moneda) {
        NumberFormat formato = NumberFormat.getNumberInstance(LOCALE_PERU);
        formato.setMinimumFractionDigits(2);
        formato.setMaximumFractionDigits(2);
        String codigo = moneda != null && !moneda.isBlank() ? moneda.trim().toUpperCase(Locale.ROOT) : "PEN";
        String simbolo = "PEN".equals(codigo) ? "S/" : codigo;
        return simbolo + " " + formato.format(monto != null ? monto : BigDecimal.ZERO);
    }
}
