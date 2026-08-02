package com.gasoilalert;

import org.json.JSONArray;
import org.json.JSONObject;

import jakarta.mail.*;
import jakarta.mail.internet.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;

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
    private static final String EMAIL_FROM = env("EMAIL_FROM", "juanmanuelsanchezgamboa2004@gmail.com");
    private static final String EMAIL_PASSWORD = env("EMAIL_PASSWORD", "aura vbpn wksg mmnx");
    private static final String EMAIL_TO = env("EMAIL_TO", "juanmanuelsanchezgamboa2004@gmail.com");
    // ==========================================================================================

    private static final String API_URL =
            "https://sedeaplicaciones.minetur.gob.es/ServiciosRESTCarburantes/PreciosCarburantes/EstacionesTerrestres/";

    /** Lee una variable de entorno si existe; si no, usa el valor por defecto. Así en Render no hace falta tocar el código. */
    private static String env(String key, String defaultValue) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? defaultValue : v;
    }

    public static void main(String[] args) throws Exception {
        String json = descargarPreciosConReintentos(0); // el nº de intentos ya no se limita; ver TIEMPO_MAX_ESPERA_MIN
        List<Estacion> estaciones = filtrarPorRadio(json, CENTRO_LAT, CENTRO_LON, RADIO_KM);

        if (estaciones.isEmpty()) {
            System.out.println("No se encontraron estaciones con gasóleo A en un radio de " + RADIO_KM + " km.");
            String mensajeSinDatos = "No se encontraron estaciones con precio de Gasóleo A disponible en un radio de "
                    + RADIO_KM + " km alrededor del punto configurado.";
            String htmlSinDatos = "<!DOCTYPE html><html lang=\"es\"><head><meta charset=\"UTF-8\"></head>"
                    + "<body style=\"margin:0;padding:0;background:#f0f2f5;font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;\">"
                    + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"background:#f0f2f5;padding:24px 0;\">"
                    + "<tr><td align=\"center\">"
                    + "<table role=\"presentation\" width=\"600\" cellpadding=\"0\" cellspacing=\"0\" "
                    + "style=\"background:#ffffff;border-radius:12px;overflow:hidden;box-shadow:0 2px 8px rgba(0,0,0,0.06);\">"
                    + "<tr><td style=\"background:linear-gradient(135deg,#1e6091,#1a759f);padding:28px 32px;\">"
                    + "<div style=\"font-size:22px;font-weight:700;color:#ffffff;\">⛽ Precios de Gasóleo A</div>"
                    + "</td></tr>"
                    + "<tr><td style=\"padding:28px 32px;\">"
                    + "<div style=\"font-size:15px;color:#52606d;line-height:1.5;\">" + escapeHtml(mensajeSinDatos) + "</div>"
                    + "</td></tr>"
                    + "</table></td></tr></table></body></html>";
            enviarEmail("Gasoil cerca de Arahal: sin datos hoy", mensajeSinDatos, htmlSinDatos);
            return;
        }

        estaciones.sort(Comparator.comparingDouble(e -> e.precioGasoleoA));
        List<Estacion> topN = estaciones.subList(0, Math.min(MAX_RESULTADOS, estaciones.size()));
        Estacion masBarata = topN.get(0);

        String asunto = String.format("⛽ Gasoil más barato cerca de Arahal: %.3f €/L - %s (%s)",
                masBarata.precioGasoleoA, masBarata.nombre, masBarata.municipio);

        String cuerpoTexto = construirCuerpoTexto(topN);
        String cuerpoHtml = construirCuerpoHtml(topN);

        System.out.println(cuerpoTexto);
        enviarEmail(asunto, cuerpoTexto, cuerpoHtml);
        System.out.println("Correo enviado correctamente.");
    }

    /** Versión en texto plano (fallback para clientes de correo que no muestran HTML). */
    private static String construirCuerpoTexto(List<Estacion> topN) {
        StringBuilder cuerpo = new StringBuilder();
        cuerpo.append("Gasolineras más baratas en Gasóleo A en un radio de ")
              .append((int) RADIO_KM).append(" km alrededor de Arahal:\n\n");
        cuerpo.append(String.format("%-30s %-15s %8s %8s%n", "Estación", "Municipio", "€/L", "km"));
        cuerpo.append("-".repeat(65)).append("\n");
        for (Estacion e : topN) {
            cuerpo.append(String.format("%-30s %-15s %8.3f %7.1f%n",
                    recortar(e.nombre, 30), recortar(e.municipio, 15), e.precioGasoleoA, e.distanciaKm));
        }
        return cuerpo.toString();
    }

    /** Versión en HTML: tabla con estilos, precio más barato destacado y filas alternas. */
    private static String construirCuerpoHtml(List<Estacion> topN) {
        Estacion mejor = topN.get(0);
        double masCara = topN.stream().mapToDouble(e -> e.precioGasoleoA).max().orElse(mejor.precioGasoleoA);
        double ahorro = masCara - mejor.precioGasoleoA;

        StringBuilder filas = new StringBuilder();
        for (int i = 0; i < topN.size(); i++) {
            Estacion e = topN.get(i);
            boolean esMejor = i == 0;
            String fondoFila = esMejor ? "#e8f5e9" : (i % 2 == 0 ? "#ffffff" : "#f7f9fb");
            String medalla = esMejor ? "🏆 " : "";
            filas.append("<tr style=\"background:").append(fondoFila).append(";\">")
                 .append("<td style=\"padding:10px 12px;border-bottom:1px solid #e5e9ec;font-weight:")
                 .append(esMejor ? "600" : "400").append(";color:#1f2933;\">")
                 .append(medalla).append(escapeHtml(e.nombre)).append("</td>")
                 .append("<td style=\"padding:10px 12px;border-bottom:1px solid #e5e9ec;color:#52606d;\">")
                 .append(escapeHtml(e.municipio)).append("</td>")
                 .append("<td style=\"padding:10px 12px;border-bottom:1px solid #e5e9ec;text-align:right;font-weight:600;color:")
                 .append(esMejor ? "#2e7d32" : "#1f2933").append(";\">")
                 .append(String.format("%.3f €", e.precioGasoleoA)).append("</td>")
                 .append("<td style=\"padding:10px 12px;border-bottom:1px solid #e5e9ec;text-align:right;color:#8f9bb3;\">")
                 .append(String.format("%.1f km", e.distanciaKm)).append("</td>")
                 .append("</tr>\n");
        }

        return "<!DOCTYPE html>"
            + "<html lang=\"es\"><head><meta charset=\"UTF-8\"></head>"
            + "<body style=\"margin:0;padding:0;background:#f0f2f5;font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;\">"
            + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"background:#f0f2f5;padding:24px 0;\">"
            + "<tr><td align=\"center\">"
            + "<table role=\"presentation\" width=\"600\" cellpadding=\"0\" cellspacing=\"0\" "
            + "style=\"background:#ffffff;border-radius:12px;overflow:hidden;box-shadow:0 2px 8px rgba(0,0,0,0.06);\">"
            // Cabecera
            + "<tr><td style=\"background:linear-gradient(135deg,#1e6091,#1a759f);padding:28px 32px;\">"
            + "<div style=\"font-size:22px;font-weight:700;color:#ffffff;\">⛽ Precios de Gasóleo A</div>"
            + "<div style=\"font-size:14px;color:#d0e6f3;margin-top:4px;\">Radio de " + (int) RADIO_KM
            + " km alrededor de Arahal</div>"
            + "</td></tr>"
            // Tarjeta resumen
            + "<tr><td style=\"padding:24px 32px 8px 32px;\">"
            + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" "
            + "style=\"background:#e8f5e9;border-radius:10px;padding:16px 20px;\"><tr><td>"
            + "<div style=\"font-size:13px;color:#2e7d32;font-weight:600;text-transform:uppercase;letter-spacing:0.5px;\">Más barata</div>"
            + "<div style=\"font-size:20px;color:#1b5e20;font-weight:700;margin-top:4px;\">"
            + escapeHtml(mejor.nombre) + " · " + escapeHtml(mejor.municipio) + "</div>"
            + "<div style=\"font-size:14px;color:#2e7d32;margin-top:2px;\">"
            + String.format("%.3f €/L a %.1f km", mejor.precioGasoleoA, mejor.distanciaKm)
            + (ahorro > 0.001 ? String.format(" · ahorras hasta %.3f €/L frente a la más cara de la lista", ahorro) : "")
            + "</div></td></tr></table>"
            + "</td></tr>"
            // Tabla de estaciones
            + "<tr><td style=\"padding:20px 32px 8px 32px;\">"
            + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"border-collapse:collapse;font-size:14px;\">"
            + "<tr style=\"background:#1e6091;\">"
            + "<th style=\"padding:10px 12px;text-align:left;color:#ffffff;font-size:12px;text-transform:uppercase;letter-spacing:0.5px;\">Estación</th>"
            + "<th style=\"padding:10px 12px;text-align:left;color:#ffffff;font-size:12px;text-transform:uppercase;letter-spacing:0.5px;\">Municipio</th>"
            + "<th style=\"padding:10px 12px;text-align:right;color:#ffffff;font-size:12px;text-transform:uppercase;letter-spacing:0.5px;\">€/L</th>"
            + "<th style=\"padding:10px 12px;text-align:right;color:#ffffff;font-size:12px;text-transform:uppercase;letter-spacing:0.5px;\">Distancia</th>"
            + "</tr>"
            + filas
            + "</table>"
            + "</td></tr>"
            // Pie
            + "<tr><td style=\"padding:20px 32px 28px 32px;\">"
            + "<div style=\"font-size:12px;color:#9aa5b1;border-top:1px solid #e5e9ec;padding-top:16px;\">"
            + "Datos del Geoportal de Gasolineras (Ministerio para la Transición Ecológica). "
            + "Generado automáticamente por GasAlert."
            + "</div></td></tr>"
            + "</table>"
            + "</td></tr></table>"
            + "</body></html>";
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String recortar(String s, int max) {
        return s.length() > max ? s.substring(0, max - 1) + "…" : s;
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

    /** Minutos máximos que se insiste en descargar el JSON antes de rendirse.
     *  El servidor del Ministerio a veces corta la conexión (handshake TLS) de forma intermitente,
     *  sobre todo desde IPs de datacenter como las de GitHub Actions; casi siempre basta con reintentar.
     *  Configurable por variable de entorno para no dejar el job colgado si el bloqueo es persistente. */
    private static final int TIEMPO_MAX_ESPERA_MIN = Integer.parseInt(env("TIEMPO_MAX_ESPERA_MIN", "30"));

    /** Reintenta la descarga SIN LÍMITE DE INTENTOS hasta conseguirlo, con espera creciente
     *  entre cada intento (backoff exponencial con tope), hasta un tiempo máximo total. */
    private static String descargarPreciosConReintentos(int intentosIgnorado) throws Exception {
        long limiteMillis = System.currentTimeMillis() + TIEMPO_MAX_ESPERA_MIN * 60_000L;
        Exception ultimoError = null;
        int intento = 0;

        while (System.currentTimeMillis() < limiteMillis) {
            intento++;
            try {
                return descargarPrecios();
            } catch (Exception e) {
                ultimoError = e;
                long segundosRestantes = (limiteMillis - System.currentTimeMillis()) / 1000;
                System.out.println("Intento " + intento + " fallido al descargar precios: " + e.getMessage()
                        + " (quedan ~" + Math.max(segundosRestantes, 0) + "s antes de rendirse)");

                if (System.currentTimeMillis() >= limiteMillis) break;

                // Backoff exponencial con tope de 60s, más un poco de aleatoriedad
                // para no repetir el fallo justo en el mismo instante cada vez.
                long esperaMs = Math.min(3000L * (1L << Math.min(intento, 6)), 60_000L);
                esperaMs += (long) (Math.random() * 2000);
                Thread.sleep(esperaMs);
            }
        }

        throw new RuntimeException("No se pudo descargar el JSON de precios tras " + intento
                + " intentos en " + TIEMPO_MAX_ESPERA_MIN + " minutos", ultimoError);
    }

    /** Descarga el JSON completo de la API pública.
     *  Se envían cabeceras de navegador porque el servidor del Ministerio
     *  bloquea peticiones que no las incluyen (protección anti-bot). */
    private static String descargarPrecios() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .header("Accept", "application/json, text/plain, */*")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")
                .header("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
                .header("Referer", "https://sedeaplicaciones.minetur.gob.es/")
                .header("Origin", "https://sedeaplicaciones.minetur.gob.es")
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new RuntimeException("La API del Ministerio devolvió el código " + response.statusCode()
                    + ". Cuerpo: " + response.body().substring(0, Math.min(300, response.body().length())));
        }
        return response.body();
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

    /** Envía el email usando Jakarta Mail vía SMTP (ej. Gmail con contraseña de aplicación).
     *  Se envía en formato multipart/alternative: una parte en texto plano (fallback) y
     *  otra en HTML (la que muestran la mayoría de clientes de correo modernos). */
    private static void enviarEmail(String asunto, String cuerpoTexto, String cuerpoHtml) throws MessagingException {
        Properties props = new Properties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.host", SMTP_HOST);
        props.put("mail.smtp.port", String.valueOf(SMTP_PORT));

        Session session = Session.getInstance(props, new Authenticator() {
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(EMAIL_FROM, EMAIL_PASSWORD);
            }
        });

        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress(EMAIL_FROM));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(EMAIL_TO));
        message.setSubject(asunto, "UTF-8");

        MimeBodyPart partesTexto = new MimeBodyPart();
        partesTexto.setText(cuerpoTexto, "UTF-8");

        MimeBodyPart partesHtml = new MimeBodyPart();
        partesHtml.setContent(cuerpoHtml, "text/html; charset=UTF-8");

        MimeMultipart multipart = new MimeMultipart("alternative");
        multipart.addBodyPart(partesTexto);
        multipart.addBodyPart(partesHtml);

        message.setContent(multipart);

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
