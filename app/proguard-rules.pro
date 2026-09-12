# Paho carica per nome le classi di trasporto (TCP/SSL/WebSocket) e legge i
# messaggi d'errore da un ResourceBundle: senza queste regole la release si
# rompe solo a runtime, alla prima connessione.
-keep class org.eclipse.paho.client.mqttv3.** { *; }
-keep class org.eclipse.paho.client.mqttv3.internal.nls.** { *; }
-dontwarn org.eclipse.paho.client.mqttv3.**

# L'API di debug esiste anche in release, e meta di quello che racconta sono
# messaggi d'errore che ripiegano sul nome della classe dell'eccezione quando il
# messaggio e vuoto. Offuscati diventano "q5.a" e non dicono piu niente proprio
# dove servirebbero: qui i nomi restano, il codice resta comunque offuscato.
-keepnames class * extends java.lang.Throwable
