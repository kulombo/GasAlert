# Gasoil Alert

Programa Java que consulta la API pública del Ministerio para la Transición
Ecológica, encuentra las gasolineras más baratas de Gasóleo A dentro de un
radio alrededor de Arahal (Sevilla) y te envía un email con el ranking.

## De dónde salen los precios

La fuente es la API pública y gratuita del Ministerio para la Transición
Ecológica y el Reto Demográfico (no hace falta API key ni registro):

```
https://sedeaplicaciones.minetur.gob.es/ServiciosRESTCarburantes/PreciosCarburantes/EstacionesTerrestres/
```

Devuelve un JSON con **todas** las gasolineras de España, cada una con su
municipio, dirección, coordenadas (`Latitud`, `Longitud (WGS84)`) y precios
de cada tipo de combustible (`Precio Gasoleo A`, `Precio Gasolina 95 E5`,
etc.). El programa descarga ese JSON completo y filtra en local por
distancia usando la fórmula de Haversine, así que no depende de que el
nombre del municipio esté escrito exactamente igual.

Se actualiza normalmente cada día laborable con los precios que las propias
estaciones están obligadas a comunicar por ley.

## Sobre el error "no tienes permisos suficientes" al abrir la URL en el navegador

Es normal y **no es un problema de tu código**: el servidor del Ministerio
tiene una protección anti-bot que bloquea peticiones que no llevan pinta de
navegador real (sin `User-Agent`, sin `Referer`, etc.). Por eso al pinchar el
enlace directamente a veces da error, aunque la API en sí funcione bien.

El programa Java ya envía esas cabeceras, así que no debería darte problemas.
Si quieres comprobarlo tú mismo antes de compilar nada, prueba con `curl`:

```bash
curl -s "https://sedeaplicaciones.minetur.gob.es/ServiciosRESTCarburantes/PreciosCarburantes/EstacionesTerrestres/" \
  -H "Accept: application/json, text/plain, */*" \
  -H "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36" \
  -H "Referer: https://sedeaplicaciones.minetur.gob.es/" \
  -o precios.json

head -c 500 precios.json
```

Si eso te devuelve JSON (empieza por `{"Fecha":"...","ListaEESSPrecio":[...`),
la API funciona perfectamente y el problema era solo el navegador.

## 1. Configurar el correo (Gmail como ejemplo)

Gmail no permite usar tu contraseña normal desde apps externas. Necesitas una
**contraseña de aplicación**:

1. Activa la verificación en dos pasos: https://myaccount.google.com/security
2. Ve a "Contraseñas de aplicaciones": https://myaccount.google.com/apppasswords
3. Genera una contraseña para "Correo" y cópiala (16 caracteres, sin espacios).

## 2. Configuración por variables de entorno

El programa lee la configuración de variables de entorno (con valores por
defecto centrados en Arahal si no las defines). **Esto es importante para
Render**: no metas tu contraseña de email directamente en el código si vas
a subirlo a GitHub, aunque el repo sea privado.

| Variable          | Por defecto        | Descripción                                  |
|-------------------|---------------------|-----------------------------------------------|
| `CENTRO_LAT`      | `37.2627`            | Latitud del centro de búsqueda (Arahal)       |
| `CENTRO_LON`      | `-5.5453`            | Longitud del centro de búsqueda (Arahal)      |
| `RADIO_KM`        | `15`                 | Radio de búsqueda en kilómetros               |
| `MAX_RESULTADOS`  | `10`                 | Nº de gasolineras a listar en el email        |
| `SMTP_HOST`       | `smtp.gmail.com`     | Servidor SMTP                                 |
| `SMTP_PORT`       | `587`                | Puerto SMTP                                   |
| `EMAIL_FROM`      | -                    | Correo remitente                              |
| `EMAIL_PASSWORD`  | -                    | Contraseña de aplicación                      |
| `EMAIL_TO`        | -                    | Correo destinatario                           |

Para probar en local, puedes exportarlas antes de ejecutar:

```bash
export EMAIL_FROM="tu_correo@gmail.com"
export EMAIL_PASSWORD="xxxx xxxx xxxx xxxx"
export EMAIL_TO="tu_correo@gmail.com"
```

## 3. Compilar y probar en local

Necesitas **JDK 17+** y **Maven**.

```bash
cd gasoil-alert
mvn package
java -jar target/gasoil-alert-1.0.jar
```

Deberías ver por consola el listado de estaciones ordenado por precio y
recibir el email.

## 4. Subir a GitHub

```bash
git init
git add .
git commit -m "Gasoil alert: precios más baratos cerca de Arahal"
git branch -M main
git remote add origin https://github.com/TU_USUARIO/gasoil-alert.git
git push -u origin main
```

El `.gitignore` ya excluye `target/`, así que no subirás binarios compilados.
**No subas tu `EMAIL_PASSWORD` en el código** — se configura como variable
de entorno en Render (paso siguiente), no en el repositorio.

## 5. Desplegar en Render como Cron Job (ejecución diaria 24/7)

Como el programa solo necesita ejecutarse **una vez al día** y termina, el
servicio ideal en Render no es un "Web Service" que esté siempre encendido,
sino un **Cron Job**:

1. En el dashboard de Render: **New +** → **Cron Job**.
2. Conecta tu repositorio de GitHub (`gasoil-alert`).
3. **Runtime**: Docker, o si Render detecta Java, "Native Environment" con:
   - **Build Command**: `mvn package`
   - **Command** (lo que se ejecuta cada vez): `java -jar target/gasoil-alert-1.0.jar`
4. **Schedule**: expresión cron, por ejemplo `0 7 * * *` (todos los días a
   las 7:00 UTC — ajusta según tu franja horaria, España en verano es UTC+2).
5. En **Environment Variables**, añade `EMAIL_FROM`, `EMAIL_PASSWORD`,
   `EMAIL_TO` y, si quieres cambiar el radio o el centro, `RADIO_KM`,
   `CENTRO_LAT`, `CENTRO_LON`.
6. Guarda y despliega. Render ejecutará el jar a la hora programada, enviará
   el correo, y el proceso terminará (no consume recursos el resto del día).

### Alternativa: Dockerfile (recomendado si Render no detecta Maven/Java bien)

Si prefieres máximo control, añade este `Dockerfile` en la raíz del proyecto:

```dockerfile
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY . .
RUN mvn package -q

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/gasoil-alert-1.0.jar app.jar
CMD ["java", "-jar", "app.jar"]
```

Y en Render, al crear el Cron Job, elige **Runtime: Docker** — detectará el
`Dockerfile` automáticamente y no necesitas configurar build/start command.

## Notas

- El listado que envía el email incluye la distancia en km a cada
  gasolinera, para que puedas valorar si merece la pena desviarte.
- Si no hay estaciones en el radio configurado, el programa te lo avisa por
  correo igualmente en vez de fallar en silencio.
- Si algún día quieres, además del email, guardar un histórico de precios,
  sería el siguiente paso natural (por ejemplo, con una base de datos
  gestionada de Render).
