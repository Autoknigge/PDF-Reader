# PDF-Reader

Android-App: PDFs öffnen, blättern, zoomen und vorlesen lassen – einfach und schnell. Öffnet auch passwortgeschützte PDFs (Passwortabfrage) und erscheint im System unter „Öffnen mit“, wenn man eine PDF antippt. Gratis mit AdMob-Banner. Alles läuft lokal auf dem Gerät.

Paketname: `de.autoknigge.pdfreader` – nach der Veröffentlichung im Play Store nicht mehr änderbar.

## Auf GitHub bauen
Unter **Actions → Build** läuft der Build bei jedem Push. Ergebnis: Artefakt **PdfReader** (`PdfReader.apk`) zum Testen.

## Werbung (AdMob)
Standard sind die **Google-Test-IDs** (nur Testanzeigen). Vor der Veröffentlichung in AdMob eine App „PDF-Reader“ anlegen und die echten IDs als GitHub-Secrets `ADMOB_APP_ID`, `ADMOB_BANNER_ID`, `ADMOB_OPEN_ID` hinterlegen (App-Start-Anzeige ist derzeit nicht aktiv, nur Banner).

## Signiert für den Play Store
Secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` (gleicher Schlüssel wie die anderen Apps). Dann entstehen `PdfReader-signiert.apk` und `PdfReader.aab`.

## Vor dem Play-Store-Start
- Datenschutzerklärung: `https://autoknigge.de/pdfreader/datenschutz.html` (in der App hinterlegt, `URL_PRIVACY` in `MainActivity.kt`).
- Play Console → Datensicherheit: Werbe-ID und Geräte-/App-Daten werden von Google AdMob erhoben; Dateien der Nutzer werden **nicht** erhoben.

## Technik
- Anzeige: Android-`PdfRenderer` (eine Seite je Bildschirm, Wischen und Zoom).
- Passwortgeschützte PDFs: [PdfBox-Android](https://github.com/TomRoush/PdfBox-Android) entschlüsselt mit dem eingegebenen Passwort eine lokale Arbeitskopie.
