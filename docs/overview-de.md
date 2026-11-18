# Architektur-Überblick

Der kurze Einstieg in die Architektur. Diese Seite fasst die arc42-Abschnitte 1, 3 und 4 zusammen;
die vollständige Dokumentation liegt unter [Architecture](architecture/index.html) (englisch), die
Begründungen in den [Architekturentscheidungen](adr/index.html) (englisch).

## Das Problem

Instandhaltungswissen in der Fertigung steckt in verstreuten PDFs, Scans und Papierordnern — und in
den Köpfen erfahrener Techniker. Viele Maschinen laufen 15–20 Jahre mit minimaler Software (nur
SPS/SCADA), die Maschine selbst liefert also außer einem Fehlercode kaum Diagnosehilfe. Steht eine
Maschine, kostet jede Eskalationsstufe Stillstandszeit — und viel zu oft war die Störung letzten
Monat schon einmal gelöst, nur findet niemand das Protokoll. Am schwersten wiegt das in der
Nachtschicht ohne Techniker vor Ort.

**maintenance-assistant** lässt Mitarbeitende Instandhaltungsprotokolle in natürlicher Sprache
(Deutsch oder Englisch) durchsuchen und antwortet mit Quellenangaben. Es ist ausdrücklich **kein**
Ticket- oder CMMS-System; die Eskalationskette ist organisatorischer Kontext, keine Systemfunktion.

## Nutzerrollen

| Rolle | Was sie erhält |
|---|---|
| **Operator** | Suche; ausschließlich bedienerseitig zulässige Maßnahmen — Sensor reinigen, Material entfernen, Programm wechseln — niemals elektrische oder mechanische Reparaturschritte. Bei Bedarf sofort der Hinweis zu eskalieren. |
| **Techniker** | Suche; vollständige technische Antworten inklusive Reparaturschritten, mit Quellenangaben zur Überprüfung und Filter auf die Maschine. Lädt Protokolle hoch, bearbeitet aber keines. |
| **Schichtleiter** | Alles Vorgenannte, dazu das Korrigieren: als einzige Rolle darf er Protokolle bearbeiten, und er sieht den Verarbeitungsstatus der Uploads. |
| **Admin** | Verwaltet Benutzer und Rollenzuordnungen in Keycloak, ohne Codeänderung. |

## Systemkontext

Die Nutzer arbeiten mit einem Angular-Client im Browser. Die Authentifizierung übernimmt
**Keycloak** (Realm `maintenance`) per OIDC; das Backend sieht nie ein Passwort. Die
**Spring-Boot**-Anwendung validiert das JWT als OAuth2 Resource Server und filtert Antworten
serverseitig nach Rolle. Protokolle und ihre Embeddings liegen in einer **PostgreSQL**-Instanz mit
**pgvector**; der einzige ausgehende Aufruf zur Abfragezeit geht an einen **EU-gehosteten,
OpenAI-kompatiblen LLM-Endpunkt**. Hochgeladene Originaldateien liegen auf einem Docker-Volume, in
der Datenbank steht nur der Pfad.

## Lösungsstrategie

Das System ist ein **modularer Monolith**: eine Spring-Boot-Anwendung, intern getrennt in
`ingestion`, `query` und `web`, die über Spring-Events kommunizieren. Genau an dieser Grenze wird
`ingestion` in Phase 2 zu einem eigenen Service mit Kafka — eine geplante Entwicklung, kein
nachträglicher Umbau.

Das Retrieval ist eine einzige SQL-Anweisung, die den relationalen Maschinenfilter mit der
Kosinus-Sortierung über die Chunk-Tabelle verbindet; deshalb liegen die Vektoren in PostgreSQL und
nicht in einer eigenen Vektordatenbank. Ein Ähnlichkeitsschwellwert auf den Treffern entscheidet zur
Laufzeit über den Antwortmodus; der Modus ist Verhalten, kein gespeicherter Zustand.

Drei Container tragen den gesamten Stack — Anwendung, PostgreSQL, Keycloak — auf einem einzigen
8-GB-VPS mit einem `docker compose up`.

## In zwei Minuten ausprobieren

Auf [maintenance.smartsupply.com.de](https://maintenance.smartsupply.com.de) als `techniker` anmelden;
das Passwort steht auf der Anmeldeseite.

1. **Presse 3 (PR-03) wählen und eine Beispielfrage anklicken.** Unter dem Fragefeld erscheinen vier
   Vorschläge in der Sprache der Oberfläche. Ein Klick füllt das Feld und schickt die Frage nicht ab: Man
   liest sie selbst und drückt selbst auf „Fragen“, und was man dabei lernt, ist die Form einer Frage, die
   funktioniert. Die Antwort kommt mit ihren Quellen als aufklappbare Karten.
   [ADR-011](adr/ADR-011-guided-questions.html) (englisch) · [die Fragen selbst](https://github.com/Keglev/maintenance-assistant/blob/main/backend/src/main/resources/examples/example-questions.json)
2. **Oberfläche auf EN stellen, Kompressor 1 (KP-01) wählen und auf Deutsch fragen:** *„Kompressor
   schaltet nach einer halben Stunde wegen zu hoher Temperatur ab“*. Das Protokoll zu diesem Fehler ist
   auf Englisch geschrieben. Es gibt nirgends einen Übersetzungsschritt: Ein Embedding-Modell hält beide
   Sprachen in einem Raum. [Die Golden Questions](https://github.com/Keglev/maintenance-assistant/blob/main/backend/src/test/resources/retrieval/golden-questions.json), Fall G10
3. **Auf Kompressor 1 bleiben und „sensor fehler“ fragen.** Keines seiner 14 Protokolle handelt von einem
   Sensor. Die Antwort kommt als allgemeiner Vorschlag zurück, als nicht belegt gekennzeichnet, ganz ohne
   Quellenbereich, und sie nennt, wie viele Protokolle die Maschine tatsächlich hat.
   [Der Korpus](https://github.com/Keglev/maintenance-assistant/blob/main/backend/src/main/resources/corpus/protocols.ndjson) · [die zwei Antwortmodi](llm-approach.html) (englisch)

## Weiterlesen

- [Architekturentscheidungen (ADRs)](adr/index.html) — warum diese Entscheidungen, und was verworfen wurde
- [Architektur (arc42)](architecture/index.html) — die vollständige Dokumentation
- [Anforderungen](requirements.html) — User Stories und die sieben nicht-funktionalen Anforderungen
- [Lokal starten](run-locally.html) — die ganze Anwendung auf einem Arbeitsplatzrechner, mit einem kostenlosen Provider-Stub (englisch)
