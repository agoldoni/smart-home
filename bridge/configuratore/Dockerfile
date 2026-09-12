FROM nginx:alpine

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY web /usr/share/nginx/html

# Il client MQTT sta dentro l'immagine e non su una CDN: lo stack deve
# funzionare con la linea di casa giu', che e' esattamente quando si va a
# guardare perche' le prese non rispondono.
