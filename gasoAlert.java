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
    private static final String EMAIL_PASSWORD = env("EMAIL_PASSWORD", "tu_contraseña_de_aplicacion");
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
        String json = descargarPrecios();
        List<Estacion> estaciones = filtrarPorRadio(json, CENTRO_LAT, CENTRO_LON, RADIO_KM);

        if (estaciones.isEmpty()) {
            System.out.println("No se encontraron estaciones con gasóleo A en un radio de " + RADIO_KM + " km.");
            enviarEmail(
                "Gasoil cerca de Arahal: sin datos hoy",
                "No se encontraron estaciones con precio de Gasóleo A disponible en un radio de "
                    + RADIO_KM + " km alrededor del punto configurado."
            );
            return;
        }

        estaciones.sort(Comparator.comparingDouble(e -> e.precioGasoleoA));
        List<Estacion> topN = estaciones.subList(0, Math.min(MAX_RESULTADOS, estaciones.size()));
        Estacion masBarata = topN.get(0);

        String asunto = String.format("Gasoil más barato cerca de Arahal: %.3f €/L - %s (%s)",
                masBarata.precioGasoleoA, masBarata.nombre, masBarata.municipio);

        StringBuilder cuerpo = new StringBuilder();
        cuerpo.append("Gasolineras más baratas en Gasóleo A en un radio de ")
              .append((int) RADIO_KM).append(" km alrededor de Arahal:\n\n");
        cuerpo.append(String.format("%-30s %-15s %8s %8s%n", "Estación", "Municipio", "€/L", "km"));
        cuerpo.append("-".repeat(65)).append("\n");
        for (Estacion e : topN) {
            cuerpo.append(String.format("%-30s %-15s %8.3f %7.1f%n",
                    recortar(e.nombre, 30), recortar(e.municipio, 15), e.precioGasoleoA, e.distanciaKm));
        }

        System.out.println(cuerpo);
        enviarEmail(asunto, cuerpo.toString());
        System.out.println("Correo enviado correctamente.");
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

    /** Envía el email usando Jakarta Mail vía SMTP (ej. Gmail con contraseña de aplicación). */
    private static void enviarEmail(String asunto, String cuerpo) throws MessagingException {
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

        Message message = new MimeMessage(session);
        message.setFrom(new InternetAddress(EMAIL_FROM));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(EMAIL_TO));
        message.setSubject(asunto, "UTF-8");
        message.setText(cuerpo, "UTF-8");

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