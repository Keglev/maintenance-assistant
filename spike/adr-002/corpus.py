"""Test corpus and queries for the ADR-002 provider spike.

Ten synthetic maintenance protocols in the style of docs/DOMAIN-MODEL.md
(symptom / cause / action, structured), seven German and three English,
each on a distinct machine and fault.

Two properties matter for the spike and must not be edited away:

1. P-08 is the ONLY protocol about hydraulic press error E-47 and it is
   written in English. There is deliberately no German E-47 protocol, so
   query (b) can only be answered across the language boundary.
2. Nothing in the corpus is about paint/coating robots, so query (c) has
   no correct answer at all (the Mode-B case from REQUIREMENTS.md NFR-2).

P-01 and P-07 are German protocols about hydraulic presses that are NOT
about E-47. They are same-language, same-machine-type noise: query (b) is
only a real multilingual result if the English P-08 outranks them.
"""

# --------------------------------------------------------------------------
# Corpus
# --------------------------------------------------------------------------

CORPUS = [
    {
        "id": "P-01",
        "lang": "de",
        "machine": "Presse 2 (PR-02, Hydraulikpresse, Bj. 2005)",
        "type": "STOERUNG",
        "error_code": "E-12",
        "text": (
            "Protokoll P-01 | Presse 2 (PR-02) | Hydraulikpresse | Stoerung E-12 | 12.03.2026 | TK\n"
            "Symptom: Oelaustritt am Hauptzylinder, Pfuetze unter der Maschine, Anlage nach "
            "Sicherheitsabschaltung gestoppt. Fuellstandsanzeige Hydraulikaggregat im roten Bereich.\n"
            "Ursache: Stangendichtung am Hauptzylinder verschlissen (Laufleistung seit letztem "
            "Wechsel ueber 4 Jahre), Abstreifring eingerissen.\n"
            "Massnahme: Zylinder ausgebaut, Dichtsatz komplett erneuert, Hydraulikoel HLP 46 "
            "nachgefuellt und Anlage 30 Minuten im Leerlauf auf Dichtheit gefahren.\n"
            "Ersatzteile: Dichtsatz 80x60, 12 l HLP 46 | Stillstand: 240 min"
        ),
    },
    {
        "id": "P-02",
        "lang": "de",
        "machine": "CNC-Fraese 1 (FR-01, Bearbeitungszentrum)",
        "type": "STOERUNG",
        "error_code": "ALM-512",
        "text": (
            "Protokoll P-02 | CNC-Fraese 1 (FR-01) | Bearbeitungszentrum | Stoerung ALM-512 | 04.04.2026 | MS\n"
            "Symptom: Steuerung meldet Alarm 512 Spindel Uebertemperatur, Abschaltung nach etwa "
            "20 Minuten Bearbeitung. Kuehlmitteldruck an der Anzeige zu niedrig.\n"
            "Ursache: Kuehlmittelfilter stark verschmutzt, dadurch zu geringer Durchfluss an der "
            "Spindelkuehlung. Spaenesieb ebenfalls zugesetzt.\n"
            "Massnahme: Filterpatrone getauscht, Spaenesieb gereinigt, Kuehlmittel aufgefuellt und "
            "Konzentration auf 8 Prozent eingestellt. Testlauf ohne Alarm.\n"
            "Ersatzteile: Filterpatrone 25 my | Stillstand: 90 min"
        ),
    },
    {
        "id": "P-03",
        "lang": "de",
        "machine": "Schweissroboter Zelle 5 (RB-05)",
        "type": "STOERUNG",
        "error_code": None,
        "text": (
            "Protokoll P-03 | Schweissroboter Zelle 5 (RB-05) | Roboterschweisszelle | Stoerung | 18.04.2026 | AH\n"
            "Symptom: Schweissnaehte an der Charge poroes, sichtbare Spritzer, Qualitaetspruefung "
            "hat die letzten 40 Teile aussortiert.\n"
            "Ursache: Gasduese stark verschmutzt und Schutzgasflasche fast leer, dadurch "
            "unzureichende Schutzgasabdeckung am Lichtbogen.\n"
            "Massnahme: Gasduese und Stromduese gereinigt beziehungsweise ersetzt, Flasche "
            "gewechselt, Gasdurchfluss auf 14 l/min eingestellt, Probenaht gefahren und freigegeben.\n"
            "Ersatzteile: Stromduese M6, Schutzgasflasche 82 Prozent Argon | Stillstand: 65 min"
        ),
    },
    {
        "id": "P-04",
        "lang": "de",
        "machine": "Verpackungslinie 1 (VL-01)",
        "type": "STOERUNG",
        "error_code": "F-208",
        "text": (
            "Protokoll P-04 | Verpackungslinie 1 (VL-01) | Folienverpackung | Stoerung F-208 | 22.04.2026 | RB\n"
            "Symptom: Folie reisst mehrmals pro Schicht direkt hinter der Abrollung, Linie stoppt "
            "jedes Mal mit Meldung F-208 Folienriss.\n"
            "Ursache: Bremse der Abrollung zu stark eingestellt, zusaetzlich Rollenkante durch "
            "Transportschaden eingedrueckt.\n"
            "Massnahme: Bremsmoment auf Sollwert 4 Nm reduziert, beschaedigte Rolle aus dem Bestand "
            "genommen, Bahnspannung im Betrieb kontrolliert.\n"
            "Ersatzteile: keine | Stillstand: 55 min"
        ),
    },
    {
        "id": "P-05",
        "lang": "de",
        "machine": "Kompressor 1 (KP-01, Schraubenkompressor)",
        "type": "STOERUNG",
        "error_code": None,
        "text": (
            "Protokoll P-05 | Kompressor 1 (KP-01) | Schraubenkompressor | Stoerung | 29.04.2026 | TK\n"
            "Symptom: Netzdruck in Halle 2 faellt im Schichtbetrieb von 7 auf 5,2 bar ab, "
            "Kompressor laeuft dauerhaft ohne Abschaltung, pneumatische Greifer arbeiten zu langsam.\n"
            "Ursache: Kondensatableiter haengt offen und blaest permanent Druckluft ab; zusaetzlich "
            "Leckage an einer Verschraubung im Ringleitungsabgang.\n"
            "Massnahme: Kondensatableiter ersetzt, Verschraubung nachgezogen und mit Lecksuchspray "
            "geprueft. Netzdruck wieder stabil bei 7 bar.\n"
            "Ersatzteile: Kondensatableiter elektronisch | Stillstand: 0 min (im Betrieb behoben)"
        ),
    },
    {
        "id": "P-06",
        "lang": "de",
        "machine": "Foerderband 2 (FB-02)",
        "type": "STOERUNG",
        "error_code": None,
        "text": (
            "Protokoll P-06 | Foerderband 2 (FB-02) | Gurtfoerderer | Stoerung | 06.05.2026 | MS\n"
            "Symptom: Gurt laeuft nach rechts aus der Spur, streift am Rahmen, Gummiabrieb unter "
            "dem Band, Notaus durch Bediener ausgeloest.\n"
            "Ursache: Umlenkrolle einseitig verstellt nach Reinigungsarbeiten, Gurtspannung "
            "ungleichmaessig.\n"
            "Massnahme: Umlenkrolle ausgerichtet, Gurtspannung beidseitig gleich eingestellt, "
            "Kante nach 30 Minuten Testlauf kontrolliert. Bediener zum Thema Spurlauf unterwiesen.\n"
            "Ersatzteile: keine | Stillstand: 45 min"
        ),
    },
    {
        "id": "P-07",
        "lang": "de",
        "machine": "Presse 3 (PR-03, Hydraulikpresse, Bj. 2021)",
        "type": "WARTUNG",
        "error_code": None,
        "text": (
            "Protokoll P-07 | Presse 3 (PR-03) | Hydraulikpresse | Wartung | 15.05.2026 | AH\n"
            "Symptom: Planmaessige Jahreswartung Hydraulik, keine Stoerung gemeldet.\n"
            "Ursache: entfaellt (geplante Wartung nach Wartungsplan H-3).\n"
            "Massnahme: Hydraulikoel komplett gewechselt (180 l HLP 46), Rueck- und Druckfilter "
            "erneuert, Schlaeuche auf Scheuerstellen geprueft, Speicherdruck kontrolliert und auf "
            "90 bar Vorspannung gebracht. Wartungsprotokoll an Schichtleitung uebergeben.\n"
            "Ersatzteile: Rueckfilter, Druckfilter, 180 l HLP 46 | Stillstand: 300 min (geplant)"
        ),
    },
    {
        "id": "P-08",
        "lang": "en",
        "machine": "Press 3 (PR-03, hydraulic press, built 2021)",
        "type": "STOERUNG",
        "error_code": "E-47",
        "text": (
            "Protocol P-08 | Press 3 (PR-03) | hydraulic press | fault E-47 | 2026-05-21 | JD\n"
            "Symptom: The hydraulic press stops mid-cycle and the HMI shows error E-47. Operators "
            "report that the pressure reading fluctuates between 180 and 240 bar during the "
            "pressing stroke although the setpoint is 210 bar.\n"
            "Cause: Drift on the main pressure sensor. The sensor signal had shifted by roughly "
            "12 percent against the reference gauge; the hydraulic circuit itself was in order.\n"
            "Action: Recalibrated the pressure sensor against a certified reference gauge, "
            "corrected the offset in the PLC parameter set and ran ten test cycles. Pressure now "
            "stable at 210 bar, error E-47 did not reappear.\n"
            "Parts: none | Downtime: 120 min"
        ),
    },
    {
        "id": "P-09",
        "lang": "en",
        "machine": "CNC mill 2 (FR-02, machining centre)",
        "type": "STOERUNG",
        "error_code": "ALM-330",
        "text": (
            "Protocol P-09 | CNC mill 2 (FR-02) | machining centre | fault ALM-330 | 2026-05-27 | JD\n"
            "Symptom: Tool changer jams during the swap to pocket 7, controller raises alarm 330 "
            "tool change timeout, magazine stops halfway.\n"
            "Cause: Worn gripper fingers on the changer arm plus dried-out grease on the cam "
            "track, so the arm no longer reached the end position within the timeout window.\n"
            "Action: Replaced both gripper fingers, cleaned and re-greased the cam track, ran "
            "twenty tool changes without fault and reset the alarm.\n"
            "Parts: 2x gripper finger, cam grease | Downtime: 150 min"
        ),
    },
    {
        "id": "P-10",
        "lang": "en",
        "machine": "Packaging line 2 (VL-02)",
        "type": "STOERUNG",
        "error_code": None,
        "text": (
            "Protocol P-10 | Packaging line 2 (VL-02) | labelling section | fault | 2026-06-02 | RB\n"
            "Symptom: Labels are applied crooked and about every tenth carton leaves the line "
            "without a label at all. Quality gate rejected two pallets.\n"
            "Cause: The photo eye that triggers the label applicator was covered in adhesive dust, "
            "so the trigger point drifted; the applicator plate was also 4 mm too high.\n"
            "Action: Cleaned the photo eye, re-taught the trigger point and lowered the applicator "
            "plate to the nominal 2 mm gap. Fifty cartons ran labelled correctly.\n"
            "Parts: none | Downtime: 70 min"
        ),
    },
]

# --------------------------------------------------------------------------
# Queries
# --------------------------------------------------------------------------
# expected: protocol id that must come out on top, or None for the Mode-B case.

QUERIES = [
    {
        "key": "a",
        "label": "DE query -> DE protocol (same-language baseline)",
        "text": "Kompressor liefert zu wenig Druck, Netzdruck in der Halle faellt ab",
        "expected": "P-05",
        "kind": "hit",
    },
    {
        "key": "b",
        "label": "DE query -> EN protocol (multilingual case, the hard requirement)",
        "text": "Presse zeigt Fehler E-47, Druck schwankt",
        "expected": "P-08",
        "kind": "hit",
    },
    {
        "key": "c",
        "label": "DE query with no matching protocol (Mode-B case)",
        "text": "Lackierroboter traegt die Farbe ungleichmaessig auf, Farbnebel in der Kabine",
        "expected": None,
        "kind": "miss",
    },
]
