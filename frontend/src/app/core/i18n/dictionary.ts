/** The two languages the interface is offered in (DECISIONS.txt: bilingual DE/EN). */
export type UiLanguage = 'de' | 'en';

/**
 * Every string the interface can show, as one type.
 *
 * Declaring the German dictionary first and typing the English one as `Dictionary` means a key
 * added to one and forgotten in the other is a compile error rather than a blank label discovered
 * by a reader who cannot read the other language. That is the whole reason this is a typed object
 * and not a `Record<string, string>`.
 *
 * Functions rather than placeholder strings where a value is interpolated: the two languages put
 * the value in different places, and a `{0}` convention would push that difference into a template.
 */
export interface Dictionary {
  readonly appTitle: string;
  readonly nav: {
    readonly search: string;
    readonly upload: string;
    readonly signOut: string;
    readonly language: string;
  };
  readonly search: {
    readonly heading: string;
    readonly machine: string;
    readonly machinePlaceholder: string;
    readonly question: string;
    readonly questionPlaceholder: string;
    readonly submit: string;
    readonly working: string;
    readonly workingHint: string;
    readonly machinesUnavailable: string;
    readonly questionRequired: string;
    readonly machineRequired: string;
  };
  readonly modeA: {
    readonly badge: string;
    readonly explanation: string;
    readonly sources: string;
    readonly openSource: string;
    readonly similarity: string;
    readonly errorCode: string;
    readonly incidentDate: string;
  };
  readonly modeB: {
    readonly badge: string;
    readonly explanation: string;
    readonly steps: string;
  };
  readonly errors: {
    readonly rateLimited: string;
    readonly budgetExhausted: string;
    readonly unavailable: string;
    readonly forbidden: string;
    readonly generic: string;
  };
  readonly upload: {
    readonly heading: string;
    readonly intro: string;
    readonly file: string;
    readonly machine: string;
    readonly type: string;
    readonly typeFault: string;
    readonly typeMaintenance: string;
    readonly title: string;
    readonly errorCode: string;
    readonly language: string;
    readonly submit: string;
    readonly submitting: string;
    readonly accepted: string;
    readonly acceptedHint: string;
    readonly myUploads: string;
    readonly refresh: string;
    readonly refreshHint: string;
    readonly none: string;
    readonly columnProtocol: string;
    readonly columnStatus: string;
    readonly columnUploaded: string;
    readonly statusReceived: string;
    readonly statusIndexed: string;
    readonly statusFailed: string;
    readonly optional: string;
  };
}

/**
 * German. The plant's language and the default the demo is shown in.
 *
 * Written without an eszett in the answer-mode badge on purpose — it has to match the wording in
 * DECISIONS.txt, which is what the two modes are called throughout the project's documentation.
 */
export const DE: Dictionary = {
  appTitle: 'Wartungsassistent',
  nav: {
    search: 'Suche',
    upload: 'Protokoll hochladen',
    signOut: 'Abmelden',
    language: 'Sprache',
  },
  search: {
    heading: 'Frage zu einer Maschine',
    machine: 'Maschine',
    machinePlaceholder: 'Maschine wählen …',
    question: 'Ihre Frage',
    questionPlaceholder: 'z. B. Presse kommt nicht auf Druck, Fehler E-47',
    submit: 'Frage stellen',
    working: 'Antwort wird erstellt …',
    workingHint: 'Das dauert in der Regel 5 bis 20 Sekunden.',
    machinesUnavailable: 'Maschinenliste nicht verfügbar.',
    questionRequired: 'Bitte eine Frage eingeben.',
    machineRequired: 'Bitte eine Maschine wählen.',
  },
  modeA: {
    badge: 'Belegte Antwort',
    explanation: 'Jede Aussage stammt aus einem Protokoll aus dem Bestand und ist belegt.',
    sources: 'Quellen',
    openSource: 'Originalprotokoll öffnen',
    similarity: 'Übereinstimmung',
    errorCode: 'Fehlercode',
    incidentDate: 'Datum',
  },
  modeB: {
    badge: 'Allgemeiner Vorschlag — keine Quelle im Bestand',
    explanation:
      'Zu dieser Frage gibt es kein Protokoll im Bestand. Die folgenden Schritte sind allgemeines Erfahrungswissen und NICHT durch ein Protokoll dieser Anlage belegt.',
    steps: 'Vorgeschlagene Schritte',
  },
  errors: {
    rateLimited: 'Zu viele Fragen in kurzer Zeit. Bitte kurz warten und erneut versuchen.',
    budgetExhausted:
      'Das Tageslimit für Antworten ist erreicht. Suche und Upload funktionieren weiter, Antworten wieder ab morgen.',
    unavailable: 'Der Antwortdienst ist gerade nicht erreichbar. Bitte später erneut versuchen.',
    forbidden: 'Für diese Aktion fehlt Ihnen die Berechtigung.',
    generic: 'Die Anfrage ist fehlgeschlagen.',
  },
  upload: {
    heading: 'Protokoll hochladen',
    intro:
      'Nur Textdateien (UTF-8). PDF und Scans werden noch nicht ausgewertet.',
    file: 'Datei',
    machine: 'Maschine',
    type: 'Art',
    typeFault: 'Störung',
    typeMaintenance: 'Wartung',
    title: 'Titel',
    errorCode: 'Fehlercode',
    language: 'Sprache des Dokuments',
    submit: 'Hochladen',
    submitting: 'Wird hochgeladen …',
    accepted: 'Protokoll angenommen.',
    acceptedHint:
      'Es ist gespeichert, aber noch nicht durchsuchbar — die Indizierung läuft im Hintergrund. Status unten aktualisieren.',
    myUploads: 'Meine Uploads',
    refresh: 'Status aktualisieren',
    refreshHint: 'Die Indizierung dauert wenige Sekunden.',
    none: 'Noch keine Uploads.',
    columnProtocol: 'Protokoll',
    columnStatus: 'Status',
    columnUploaded: 'Hochgeladen',
    statusReceived: 'Angenommen, wird indiziert',
    statusIndexed: 'Durchsuchbar',
    statusFailed: 'Fehlgeschlagen',
    optional: 'optional',
  },
};

export const EN: Dictionary = {
  appTitle: 'Maintenance Assistant',
  nav: {
    search: 'Search',
    upload: 'Upload protocol',
    signOut: 'Sign out',
    language: 'Language',
  },
  search: {
    heading: 'Ask about a machine',
    machine: 'Machine',
    machinePlaceholder: 'Choose a machine …',
    question: 'Your question',
    questionPlaceholder: 'e.g. press does not build up pressure, fault E-47',
    submit: 'Ask',
    working: 'Preparing the answer …',
    workingHint: 'This usually takes 5 to 20 seconds.',
    machinesUnavailable: 'Machine list unavailable.',
    questionRequired: 'Please enter a question.',
    machineRequired: 'Please choose a machine.',
  },
  modeA: {
    badge: 'Sourced answer',
    explanation: 'Every statement comes from a protocol in the records and is cited.',
    sources: 'Sources',
    openSource: 'Open the original protocol',
    similarity: 'Match',
    errorCode: 'Fault code',
    incidentDate: 'Date',
  },
  modeB: {
    badge: 'General suggestion — no source in the records',
    explanation:
      'No protocol in the records covers this question. The steps below are general engineering knowledge and are NOT backed by a protocol for this machine.',
    steps: 'Suggested steps',
  },
  errors: {
    rateLimited: 'Too many questions in a short time. Please wait a moment and try again.',
    budgetExhausted:
      "Today's answer limit has been reached. Search and upload still work; answering resumes tomorrow.",
    unavailable: 'The answer service is temporarily unavailable. Please try again later.',
    forbidden: 'You do not have permission for this action.',
    generic: 'The request failed.',
  },
  upload: {
    heading: 'Upload a protocol',
    intro: 'Text files only (UTF-8). PDFs and scans are not extracted yet.',
    file: 'File',
    machine: 'Machine',
    type: 'Type',
    typeFault: 'Fault',
    typeMaintenance: 'Maintenance',
    title: 'Title',
    errorCode: 'Fault code',
    language: 'Document language',
    submit: 'Upload',
    submitting: 'Uploading …',
    accepted: 'Protocol accepted.',
    acceptedHint:
      'It is stored but not searchable yet — indexing runs in the background. Refresh the status below.',
    myUploads: 'My uploads',
    refresh: 'Refresh status',
    refreshHint: 'Indexing takes a few seconds.',
    none: 'No uploads yet.',
    columnProtocol: 'Protocol',
    columnStatus: 'Status',
    columnUploaded: 'Uploaded',
    statusReceived: 'Accepted, indexing',
    statusIndexed: 'Searchable',
    statusFailed: 'Failed',
    optional: 'optional',
  },
};

export const DICTIONARIES: Readonly<Record<UiLanguage, Dictionary>> = { de: DE, en: EN };
