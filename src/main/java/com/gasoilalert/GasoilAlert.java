package com.gasoilalert;

import org.json.JSONArray;
import org.json.JSONObject;

import jakarta.mail.*;
import jakarta.mail.internet.*;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Descarga los precios de carburantes publicados por el Ministerio
 * para la Transición Ecológica (Geoportal de Gasolineras), busca las
 * gasolineras dentro de un radio alrededor de un punto (por defecto,
 * Arahal, Sevilla), ordena por precio de Gasóleo A y envía un email
 * con el listado, de la más barata a la más cara.
 *
 * Fuente de datos (pública y gratuita, sin necesidad de API key):
 * https://sedeaplicaciones.minetur.gob.es/ServiciosRESTCarburantes/PreciosCarburantes/EstacionesTerrestres/
 */
public class GasoilAlert {

    // ==== CONFIGURACIÓN: EDITA ESTOS VALORES (o usa variables de entorno, ver más abajo) ====
    private static final double CENTRO_LAT = Double.parseDouble(env("CENTRO_LAT", "37.2627"));   // Arahal, Sevilla
    private static final double CENTRO_LON = Double.parseDouble(env("CENTRO_LON", "-5.5453"));   // Arahal, Sevilla
    private static final double RADIO_KM = Double.parseDouble(env("RADIO_KM", "15"));            // radio de búsqueda
    private static final int MAX_RESULTADOS = Integer.parseInt(env("MAX_RESULTADOS", "10"));     // cuántas mostrar en el email

    private static final String SMTP_HOST = env("SMTP_HOST", "smtp.gmail.com");
    private static final int SMTP_PORT = Integer.parseInt(env("SMTP_PORT", "587"));
    private static final String EMAIL_FROM = env("EMAIL_FROM", "tu_correo@gmail.com");
    private static final String EMAIL_PASSWORD = env("EMAIL_PASSWORD", "tu_contraseña_de_aplicacion");
    private static final String EMAIL_TO = env("EMAIL_TO", "tu_correo@gmail.com");
    // ==========================================================================================

    private static final String API_URL =
            "https://sedeaplicaciones.minetur.gob.es/ServiciosRESTCarburantes/PreciosCarburantes/EstacionesTerrestres/";

    /** Lee una variable de entorno si existe; si no, usa el valor por defecto. Así en Render no hace falta tocar el código. */
    private static String env(String key, String defaultValue) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? defaultValue : v;
    }

    public static void main(String[] args) throws Exception {
        String json = descargarPreciosConReintentos(3);
        List<Estacion> estaciones = filtrarPorRadio(json, CENTRO_LAT, CENTRO_LON, RADIO_KM);

        if (estaciones.isEmpty()) {
            System.out.println("No se encontraron estaciones con gasóleo A en un radio de " + RADIO_KM + " km.");
            enviarEmail(
                "Gasoil cerca de Arahal: sin datos hoy",
                htmlSinDatos()
            );
            return;
        }

        estaciones.sort(Comparator.comparingDouble(e -> e.precioGasoleoA));
        List<Estacion> topN = estaciones.subList(0, Math.min(MAX_RESULTADOS, estaciones.size()));
        Estacion masBarata = topN.get(0);

        String asunto = String.format("⛽ Gasoil más barato cerca de Arahal: %.3f €/L - %s",
                masBarata.precioGasoleoA, masBarata.nombre);

        String html = construirHtml(topN);

        // También lo imprimimos en texto plano en el log de la ejecución, útil para depurar
        System.out.println("Gasolineras más baratas en Gasóleo A en un radio de " + (int) RADIO_KM + " km:");
        for (Estacion e : topN) {
            System.out.printf("%-30s %-15s %8.3f %7.1f km%n",
                    recortar(e.nombre, 30), recortar(e.municipio, 15), e.precioGasoleoA, e.distanciaKm);
        }

        enviarEmail(asunto, html);
        System.out.println("Correo enviado correctamente.");
    }

    private static String recortar(String s, int max) {
        return s.length() > max ? s.substring(0, max - 1) + "…" : s;
    }

    /** Construye el cuerpo del email en HTML, con la más barata destacada. */
    private static String construirHtml(List<Estacion> estaciones) {
        String fondo = "#f4f6f8";
        String tarjeta = "#ffffff";
        String textoPrincipal = "#1a1a1a";
        String textoSecundario = "#6b7280";
        String verde = "#16a34a";
        String bordeSuave = "#e5e7eb";

        StringBuilder filas = new StringBuilder();
        for (int i = 0; i < estaciones.size(); i++) {
            Estacion e = estaciones.get(i);
            boolean esLaMasBarata = (i == 0);
            String fondoFila = esLaMasBarata ? "#f0fdf4" : (i % 2 == 0 ? tarjeta : "#fafafa");
            String colorPrecio = esLaMasBarata ? verde : textoPrincipal;
            String medalla = esLaMasBarata ? "🥇 " : "";

            filas.append("<tr style=\"background:").append(fondoFila).append(";\">")
                 .append("<td style=\"padding:10px 12px;border-bottom:1px solid ").append(bordeSuave)
                 .append(";font-size:14px;color:").append(textoPrincipal).append(";\">")
                 .append(medalla).append(escapeHtml(e.nombre)).append("</td>")
                 .append("<td style=\"padding:10px 12px;border-bottom:1px solid ").append(bordeSuave)
                 .append(";font-size:14px;color:").append(textoSecundario).append(";\">")
                 .append(escapeHtml(e.municipio)).append("</td>")
                 .append("<td style=\"padding:10px 12px;border-bottom:1px solid ").append(bordeSuave)
                 .append(";font-size:14px;color:").append(textoSecundario).append(";text-align:right;\">")
                 .append(String.format("%.1f km", e.distanciaKm)).append("</td>")
                 .append("<td style=\"padding:10px 12px;border-bottom:1px solid ").append(bordeSuave)
                 .append(";font-size:15px;font-weight:700;color:").append(colorPrecio).append(";text-align:right;\">")
                 .append(String.format("%.3f €", e.precioGasoleoA)).append("</td>")
                 .append("</tr>\n");
        }

        Estacion masBarata = estaciones.get(0);

        return "<!DOCTYPE html><html><body style=\"margin:0;padding:0;background:" + fondo + ";font-family:Arial, Helvetica, sans-serif;\">"
            + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"background:" + fondo + ";padding:24px 0;\">"
            + "<tr><td align=\"center\">"
            + "<table role=\"presentation\" width=\"600\" cellpadding=\"0\" cellspacing=\"0\" style=\"background:" + tarjeta + ";border-radius:12px;overflow:hidden;box-shadow:0 1px 3px rgba(0,0,0,0.08);\">"

            // Cabecera
            + "<tr><td style=\"background:#0f172a;padding:24px 28px;\">"
            + "<div style=\"font-size:22px;\">⛽</div>"
            + "<div style=\"font-size:19px;font-weight:700;color:#ffffff;margin-top:4px;\">Gasoil cerca de Arahal</div>"
            + "<div style=\"font-size:13px;color:#94a3b8;margin-top:2px;\">Radio de " + (int) RADIO_KM + " km &middot; " + estaciones.size() + " gasolineras encontradas</div>"
            + "</td></tr>"

            // Destacado del más barato
            + "<tr><td style=\"padding:20px 28px 8px 28px;\">"
            + "<div style=\"background:#f0fdf4;border:1px solid #bbf7d0;border-radius:10px;padding:16px 18px;\">"
            + "<div style=\"font-size:12px;color:" + verde + ";font-weight:700;text-transform:uppercase;letter-spacing:0.04em;\">Más barata hoy</div>"
            + "<div style=\"font-size:20px;font-weight:700;color:" + textoPrincipal + ";margin-top:4px;\">" + escapeHtml(masBarata.nombre) + "</div>"
            + "<div style=\"font-size:13px;color:" + textoSecundario + ";margin-top:2px;\">" + escapeHtml(masBarata.municipio) + " &middot; " + String.format("%.1f km", masBarata.distanciaKm) + "</div>"
            + "<div style=\"font-size:28px;font-weight:800;color:" + verde + ";margin-top:8px;\">" + String.format("%.3f €/L", masBarata.precioGasoleoA) + "</div>"
            + "</div></td></tr>"

            // Tabla completa
            + "<tr><td style=\"padding:16px 28px 24px 28px;\">"
            + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"border-collapse:collapse;\">"
            + "<tr>"
            + "<th style=\"text-align:left;padding:8px 12px;font-size:11px;color:" + textoSecundario + ";text-transform:uppercase;letter-spacing:0.04em;border-bottom:2px solid " + bordeSuave + ";\">Estación</th>"
            + "<th style=\"text-align:left;padding:8px 12px;font-size:11px;color:" + textoSecundario + ";text-transform:uppercase;letter-spacing:0.04em;border-bottom:2px solid " + bordeSuave + ";\">Municipio</th>"
            + "<th style=\"text-align:right;padding:8px 12px;font-size:11px;color:" + textoSecundario + ";text-transform:uppercase;letter-spacing:0.04em;border-bottom:2px solid " + bordeSuave + ";\">Dist.</th>"
            + "<th style=\"text-align:right;padding:8px 12px;font-size:11px;color:" + textoSecundario + ";text-transform:uppercase;letter-spacing:0.04em;border-bottom:2px solid " + bordeSuave + ";\">€/L</th>"
            + "</tr>"
            + filas
            + "</table>"
            + "</td></tr>"

            // Pie
            + "<tr><td style=\"padding:16px 28px 24px 28px;border-top:1px solid " + bordeSuave + ";\">"
            + "<div style=\"font-size:12px;color:" + textoSecundario + ";\">Datos de la API pública del Ministerio para la Transición Ecológica. Generado automáticamente cada día.</div>"
            + "</td></tr>"

            + "</table>"
            + "</td></tr></table>"
            + "</body></html>";
    }

    private static String htmlSinDatos() {
        return "<!DOCTYPE html><html><body style=\"font-family:Arial, sans-serif;padding:24px;color:#1a1a1a;\">"
            + "<h2>⛽ Gasoil cerca de Arahal</h2>"
            + "<p>No se encontraron estaciones con precio de Gasóleo A disponible en un radio de "
            + (int) RADIO_KM + " km alrededor del punto configurado hoy.</p>"
            + "</body></html>";
    }

    /** Escapa caracteres especiales de HTML para evitar romper el maquetado con nombres raros de gasolineras. */
    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** Distancia entre dos puntos geográficos en km (fórmula de Haversine). */
    private static double distanciaKm(double lat1, double lon1, double lat2, double lon2) {
        double R = 6371.0; // radio de la Tierra en km
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }

    /** Reintenta la descarga varias veces si el servidor del Ministerio falla de forma intermitente
     *  (corte de conexión, timeout, error 5xx...), esperando un poco más entre cada intento. */
    private static String descargarPreciosConReintentos(int intentosMax) throws Exception {
        Exception ultimoError = null;
        for (int intento = 1; intento <= intentosMax; intento++) {
            try {
                return descargarPrecios();
            } catch (Exception e) {
                ultimoError = e;
                System.out.println("Intento " + intento + "/" + intentosMax
                        + " fallido al descargar precios: " + e.getMessage());
                if (intento < intentosMax) {
                    Thread.sleep(3000L * intento); // 3s, 6s, 9s...
                }
            }
        }
        throw new RuntimeException("No se pudo descargar el JSON de precios tras " + intentosMax + " intentos", ultimoError);
    }

    /** Descarga el JSON completo de la API pública ejecutando `curl` como proceso externo.
     *  Motivo: el cliente HTTP nativo de Java (HttpClient) es bloqueado sistemáticamente
     *  por la protección anti-bot del servidor del Ministerio (corta el handshake TLS),
     *  tanto desde GitHub Actions como desde una IP residencial normal. `curl` sí funciona
     *  de forma consistente en ambos casos (usa el stack TLS del sistema operativo, no el
     *  de la JVM), así que delegamos la descarga en él. `curl` viene preinstalado tanto en
     *  Windows 10/11 como en los runners de GitHub Actions (Ubuntu). */
    private static String descargarPrecios() throws Exception {
        List<String> comando = List.of(
                "curl",
                "-s",                    // modo silencioso, sin barra de progreso
                "--max-time", "25",      // corta sola si tarda más de 25s
                "-H", "Accept: application/json, text/plain, */*",
                "-H", "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
                "-H", "Accept-Language: es-ES,es;q=0.9,en;q=0.8",
                "-H", "Referer: https://sedeaplicaciones.minetur.gob.es/",
                "-H", "Origin: https://sedeaplicaciones.minetur.gob.es",
                API_URL
        );

        Process proceso = new ProcessBuilder(comando).start();

        String salida;
        try (InputStream is = proceso.getInputStream()) {
            salida = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
        String salidaError;
        try (InputStream es = proceso.getErrorStream()) {
            salidaError = new String(es.readAllBytes(), StandardCharsets.UTF_8);
        }

        boolean terminoATiempo = proceso.waitFor(30, TimeUnit.SECONDS);
        if (!terminoATiempo) {
            proceso.destroyForcibly();
            throw new RuntimeException("curl no respondió en 30s (colgado o red muy lenta)");
        }

        int codigoSalida = proceso.exitValue();
        if (codigoSalida != 0) {
            throw new RuntimeException("curl terminó con código " + codigoSalida
                    + (salidaError.isBlank() ? "" : ": " + salidaError.trim()));
        }
        if (salida.isBlank()) {
            throw new RuntimeException("curl devolvió una respuesta vacía");
        }
        return salida;
    }

    /** Extrae las estaciones dentro de un radio (km) alrededor de un punto, con precio de Gasóleo A. */
    private static List<Estacion> filtrarPorRadio(String json, double centroLat, double centroLon, double radioKm) {
        List<Estacion> resultado = new ArrayList<>();
        JSONObject root = new JSONObject(json);
        JSONArray lista = root.getJSONArray("ListaEESSPrecio");

        for (int i = 0; i < lista.length(); i++) {
            JSONObject est = lista.getJSONObject(i);

            String precioStr = est.optString("Precio Gasoleo A", "").replace(",", ".");
            if (precioStr.isBlank()) continue; // esa estación no vende gasóleo A o no reporta precio

            String latStr = est.optString("Latitud", "").replace(",", ".");
            String lonStr = est.optString("Longitud (WGS84)", "").replace(",", ".");
            if (latStr.isBlank() || lonStr.isBlank()) continue;

            try {
                double lat = Double.parseDouble(latStr);
                double lon = Double.parseDouble(lonStr);
                double distancia = distanciaKm(centroLat, centroLon, lat, lon);
                if (distancia > radioKm) continue;

                double precio = Double.parseDouble(precioStr);
                Estacion e = new Estacion();
                e.nombre = est.optString("Rótulo", "Sin nombre");
                e.municipio = est.optString("Municipio", "");
                e.direccion = est.optString("Dirección", "");
                e.precioGasoleoA = precio;
                e.distanciaKm = distancia;
                resultado.add(e);
            } catch (NumberFormatException ignored) {
            }
        }
        return resultado;
    }

    /** Envía el email usando Jakarta Mail vía SMTP (ej. Gmail con contraseña de aplicación). */
    private static void enviarEmail(String asunto, String cuerpoHtml) throws MessagingException {
        Properties props = new Properties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.host", SMTP_HOST);
        props.put("mail.smtp.port", String.valueOf(SMTP_PORT));
        props.put("mail.smtp.connectiontimeout", "15000"); // 15s para conectar
        props.put("mail.smtp.timeout", "15000");            // 15s para leer respuesta
        props.put("mail.smtp.writetimeout", "15000");       // 15s para escribir

        Session session = Session.getInstance(props, new Authenticator() {
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(EMAIL_FROM, EMAIL_PASSWORD);
            }
        });

        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress(EMAIL_FROM));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(EMAIL_TO));
        message.setSubject(asunto, "UTF-8");
        message.setContent(cuerpoHtml, "text/html; charset=UTF-8");

        Transport.send(message);
    }

    private static class Estacion {
        String nombre;
        String municipio;
        String direccion;
        double precioGasoleoA;
        double distanciaKm;
    }
}
