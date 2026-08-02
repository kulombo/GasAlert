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

## 5. Ejecución diaria GRATIS con GitHub Actions (recomendado)

> **Nota:** Render eliminó su plan gratuito de Cron Jobs — ahora cobra desde
> ~$1/mes. Como este programa solo necesita ejecutarse una vez al día
> durante unos segundos, **GitHub Actions es la opción gratuita** más
> sencilla, y como el código ya está en GitHub, no hace falta añadir ningún
> servicio externo.

El archivo `.github/workflows/gasoil-alert.yml` ya incluido en este
proyecto hace todo el trabajo: cada día, a la hora programada, GitHub
levanta una máquina temporal, compila el proyecto con Maven, ejecuta el
jar (que envía el email) y apaga la máquina. No pagas nada mientras no se
esté ejecutando.

### Configurar las credenciales (sin tocar el código)

1. En tu repositorio de GitHub: **Settings → Secrets and variables → Actions**.
2. Pestaña **"Secrets"** → **New repository secret** → crea estos tres
   (son datos sensibles, no aparecerán en los logs):
   - `EMAIL_FROM`
   - `EMAIL_PASSWORD` (la contraseña de aplicación de Gmail)
   - `EMAIL_TO`
3. Pestaña **"Variables"** (opcional, solo si quieres cambiar los valores
   por defecto) → **New repository variable**:
   - `RADIO_KM` (por defecto 15 si no la defines)
   - `CENTRO_LAT`, `CENTRO_LON` (por defecto, Arahal)
   - `MAX_RESULTADOS`, `SMTP_HOST`, `SMTP_PORT`

### Probarlo sin esperar a la hora programada

1. Ve a la pestaña **"Actions"** de tu repositorio.
2. Selecciona el workflow **"Gasoil Alert diario"**.
3. Botón **"Run workflow"** (aparece gracias a `workflow_dispatch` en el
   archivo yml) → **Run workflow**.
4. En unos segundos verás los logs de la ejecución y, si todo va bien, el
   email te llegará igual que en local.

### Sobre la hora exacta

Las expresiones cron de GitHub Actions van en **UTC**, y GitHub advierte
que en horas de mucha carga la ejecución puede retrasarse unos minutos
(no está garantizada al segundo, pero para un email diario es más que
suficiente). El archivo ya viene configurado a las `6:00 UTC` (8:00 de la
mañana en España en horario de verano); si quieres cambiar la hora, edita
la línea `cron:` del archivo `.github/workflows/gasoil-alert.yml`.

### Si en el futuro prefieres Render de todos modos

Sigue siendo una opción válida si algún día quieres algo con más control o
dashboard propio, solo que ya no es gratis para Cron Jobs. El mismo
`pom.xml` funciona igual; como Build Command usarías `mvn package` y como
Command `java -jar target/gasoil-alert-1.0.jar`, configurando las mismas
variables de entorno en la sección "Environment" del servicio.

## Notas

- El listado que envía el email incluye la distancia en km a cada
  gasolinera, para que puedas valorar si merece la pena desviarte.
- Si no hay estaciones en el radio configurado, el programa te lo avisa por
  correo igualmente en vez de fallar en silencio.
- Si algún día quieres, además del email, guardar un histórico de precios,
  sería el siguiente paso natural (por ejemplo, con una base de datos
  gestionada de Render).
