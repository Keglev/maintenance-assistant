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
/**
 * One of the demo questions offered on the landing page and in the help dialog.
 *
 * Each is tied to a seeded demo case, so the three of them together show the three behaviours worth
 * showing: several sources for one fault, a German question answered from an English protocol, and
 * the deliberate gap that produces a Mode B answer.
 */
export interface DemoExample {
  /** The machine to pick before asking — the query is filtered by machine, so this is not decoration. */
  readonly machine: string;
  readonly question: string;
  /** What the reader should watch for in the answer. */
  readonly shows: string;
}

export interface Dictionary {
  readonly appTitle: string;
  readonly appTagline: string;
  /** Labels that belong to no one view — the shared dialog shell reads them. */
  readonly common: {
    readonly close: string;
    readonly cancel: string;
  };
  /** The theme control. `system` is a state of its own, so it needs a name of its own. */
  readonly theme: {
    readonly label: string;
    readonly system: string;
    readonly light: string;
    readonly dark: string;
  };
  readonly nav: {
    readonly search: string;
    readonly upload: string;
    readonly signOut: string;
    readonly language: string;
    readonly help: string;
    readonly menu: string;
  };
  readonly roles: {
    readonly operator: string;
    readonly techniker: string;
    readonly schichtleiter: string;
    readonly admin: string;
  };
  readonly footer: {
    readonly docs: string;
    readonly repo: string;
    readonly aiUsage: string;
    readonly note: string;
  };
  readonly signOut: {
    readonly title: string;
    readonly body: string;
    readonly notice: string;
    readonly dismiss: string;
  };
  readonly landing: {
    readonly headline: string;
    readonly problem: string;
    readonly solution: string;
    readonly trustHeading: string;
    readonly trustSourced: string;
    readonly trustSourcedBody: string;
    readonly trustEu: string;
    readonly trustEuBody: string;
    readonly trustRoles: string;
    readonly trustRolesBody: string;
    readonly demoHeading: string;
    readonly demoIntro: string;
    /** What kind of environment this is, so a public password reads as a boundary and not a leak. */
    readonly demoBoundary: string;
    readonly demoPassword: string;
    /** "Sign in as operator" — the username is the argument, and it is never translated. */
    readonly signInAs: (username: string) => string;
    readonly signIn: string;
    readonly signInHint: string;
    readonly plateModel: string;
    readonly plateStack: string;
    readonly plateHosting: string;
  };
  readonly demo: {
    readonly operator: string;
    readonly techniker: string;
    readonly schichtleiter: string;
    readonly admin: string;
    readonly examplesHeading: string;
    readonly examplesIntro: string;
    readonly exampleMachine: string;
    readonly examples: readonly DemoExample[];
  };
  readonly help: {
    readonly title: string;
    readonly modesHeading: string;
    readonly modeAHeading: string;
    readonly modeABody: string;
    readonly modeBHeading: string;
    readonly modeBBody: string;
    readonly rolesHeading: string;
    readonly roleOperator: string;
    readonly roleTechniker: string;
    readonly roleSchichtleiter: string;
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
  readonly viewer: {
    readonly title: string;
    readonly loading: string;
    readonly download: string;
    readonly fallbackNote: string;
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
    readonly documentMissing: string;
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
  appTagline: 'Antworten aus den Protokollen Ihrer Anlage — mit Quelle.',
  common: {
    close: 'Schließen',
    cancel: 'Abbrechen',
  },
  theme: {
    label: 'Darstellung',
    system: 'System',
    light: 'Hell',
    dark: 'Dunkel',
  },
  nav: {
    search: 'Suche',
    upload: 'Protokoll hochladen',
    signOut: 'Abmelden',
    language: 'Sprache',
    help: 'Hilfe',
    menu: 'Menü',
  },
  roles: {
    operator: 'Bediener',
    techniker: 'Techniker',
    schichtleiter: 'Schichtleiter',
    admin: 'Administrator',
  },
  footer: {
    docs: 'Dokumentation',
    repo: 'Quellcode auf GitHub',
    aiUsage: 'KI-Einsatz im Projekt',
    note: 'Demo-System mit synthetischen Protokollen. Keine echten Anlagendaten.',
  },
  signOut: {
    title: 'Wirklich abmelden?',
    body: 'Sie werden abgemeldet und müssen sich für die nächste Frage neu anmelden.',
    notice: 'Sie wurden abgemeldet.',
    dismiss: 'Hinweis ausblenden',
  },
  landing: {
    headline: 'Wartungswissen, das nicht mit der Schicht nach Hause geht',
    problem:
      'Das Wissen einer Instandhaltung steckt in PDFs, in Ordnern und in den Köpfen erfahrener Kollegen. Nachts ist keine Fachkraft im Haus, und eine Störung wird neu diagnostiziert, obwohl sie vor vier Wochen schon einmal gelöst wurde.',
    solution:
      'Der Wartungsassistent beantwortet Fragen in normaler Sprache — aus den Wartungsprotokollen der eigenen Anlage, mit Angabe des Protokolls, aus dem die Antwort stammt.',
    trustHeading: 'Worauf Sie sich verlassen können',
    trustSourced: 'Belegt oder ausdrücklich gekennzeichnet',
    trustSourcedBody:
      'Eine Antwort ist entweder durch Protokolle belegt (Modus A, grün) oder sie ist als allgemeiner Vorschlag ohne Quelle gekennzeichnet (Modus B, gelb). Etwas dazwischen gibt es nicht.',
    trustEu: 'Verarbeitung in der EU',
    trustEuBody:
      'Einbettung und Antwort laufen über den IONOS AI Model Hub in Berlin. Keine Übermittlung in Drittländer, keine externen Schriftarten, keine Tracker.',
    trustRoles: 'Antworten nach Rolle',
    trustRolesBody:
      'Bediener erhalten nur Schritte, die eine unterwiesene Person ausführen darf, samt Hinweis, wann eine Fachkraft zu holen ist.',
    demoHeading: 'Demo-Zugänge',
    demoIntro: 'Vier Benutzer, ein Passwort. Jede Rolle zeigt eine andere Sicht auf dieselben Daten.',
    demoBoundary:
      'Bewusst öffentliche Demo-Umgebung: eigener, isolierter Realm, ausschließlich synthetische Daten, Anfragen begrenzt, Sitzung nach 15 Minuten beendet.',
    demoPassword: 'Passwort für alle',
    signInAs: (username) => `Als ${username} anmelden`,
    signIn: 'Anmelden',
    signInHint:
      'Die Anmeldung läuft über Keycloak. Sie werden dorthin weitergeleitet und kommen anschließend hierher zurück.',
    plateModel: 'Typ',
    plateStack: 'Technik',
    plateHosting: 'Betrieb',
  },
  demo: {
    operator: 'Fragen stellen, bedienerseitige Antworten.',
    techniker: 'Fragen stellen, vollständige technische Antworten.',
    schichtleiter: 'Wie Techniker, zusätzlich Protokolle hochladen.',
    admin: 'Verwaltung in Keycloak, keine Fachfunktion in der Anwendung.',
    examplesHeading: 'Zum Ausprobieren',
    examplesIntro: 'Erst die Maschine wählen, dann die Frage stellen — gesucht wird je Maschine.',
    exampleMachine: 'Maschine',
    examples: [
      {
        machine: 'Presse 3',
        question: 'Presse 3 zeigt Störung E-47, was kann die Ursache sein?',
        shows:
          'Vier Protokolle mit vier verschiedenen Ursachen zur selben Störung — eine belegte Antwort mit mehreren Quellen.',
      },
      {
        machine: 'Förderband FB-04',
        question: 'Förderband FB-04 läuft schief, was tun?',
        shows:
          'Deutsche Frage, englisches Protokoll: die Antwort kommt trotzdem belegt zurück und bleibt deutsch.',
      },
      {
        machine: 'Abfüllanlage AB-02',
        question: 'Die Dosierung an AB-02 ist ungenau, woran liegt das?',
        shows:
          'Zu dieser Frage gibt es kein Protokoll im Bestand — die Antwort erscheint gelb und ausdrücklich unbelegt.',
      },
    ],
  },
  help: {
    title: 'Hilfe',
    modesHeading: 'Die zwei Antwortarten',
    modeAHeading: 'Modus A — Belegte Antwort (grün)',
    modeABody:
      'Zur Frage gibt es Protokolle im Bestand. Jede Aussage nennt in eckigen Klammern das Protokoll, aus dem sie stammt; ein Klick darauf öffnet das Originalprotokoll.',
    modeBHeading: 'Modus B — Allgemeiner Vorschlag (gelb)',
    modeBBody:
      'Zur Frage gibt es kein passendes Protokoll. Die Schritte sind allgemeines Erfahrungswissen und werden ohne Quellenbereich gezeigt — es gibt hier nichts zu belegen.',
    rolesHeading: 'Wer darf was',
    roleOperator:
      'Bediener: Fragen stellen. Antworten enthalten nur Schritte für unterwiesene Personen, keine Elektro- oder Mechanikarbeiten.',
    roleTechniker: 'Techniker: Fragen stellen, vollständige technische Antworten ohne Einschränkung.',
    roleSchichtleiter: 'Schichtleiter: wie Techniker, zusätzlich neue Protokolle hochladen.',
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
  viewer: {
    title: 'Originalprotokoll',
    loading: 'Protokoll wird geladen …',
    download: 'Herunterladen',
    fallbackNote:
      'Dieses Protokoll folgt nicht der üblichen Gliederung. Es wird unverändert angezeigt.',
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
    documentMissing:
      'Das Originalprotokoll lässt sich nicht mehr öffnen. Die Angaben in der Antwort stammen aus diesem Protokoll, die Datei ist aber nicht mehr im Bestand.',
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
  appTagline: "Answers from your plant's own protocols — with the source.",
  common: {
    close: 'Close',
    cancel: 'Cancel',
  },
  theme: {
    label: 'Appearance',
    system: 'System',
    light: 'Light',
    dark: 'Dark',
  },
  nav: {
    search: 'Search',
    upload: 'Upload protocol',
    signOut: 'Sign out',
    language: 'Language',
    help: 'Help',
    menu: 'Menu',
  },
  roles: {
    operator: 'Operator',
    techniker: 'Technician',
    schichtleiter: 'Shift lead',
    admin: 'Administrator',
  },
  footer: {
    docs: 'Documentation',
    repo: 'Source code on GitHub',
    aiUsage: 'AI use in this project',
    note: 'Demo system with synthetic protocols. No real plant data.',
  },
  signOut: {
    title: 'Sign out?',
    body: 'You will be signed out and will have to sign in again for your next question.',
    notice: 'You have been signed out.',
    dismiss: 'Dismiss this notice',
  },
  landing: {
    headline: 'Maintenance knowledge that does not go home with the shift',
    problem:
      "A plant's maintenance knowledge sits in PDFs, in folders and in the heads of experienced colleagues. At night no technician is on site, and a fault gets diagnosed from scratch although it was solved four weeks ago.",
    solution:
      "The Maintenance Assistant answers questions in plain language — from the plant's own maintenance protocols, naming the protocol each answer came from.",
    trustHeading: 'What you can rely on',
    trustSourced: 'Sourced, or explicitly labelled',
    trustSourcedBody:
      'An answer is either backed by protocols (Mode A, green) or labelled as a general suggestion with no source (Mode B, amber). There is nothing in between.',
    trustEu: 'Processing stays in the EU',
    trustEuBody:
      'Embedding and answering run on the IONOS AI Model Hub in Berlin. No transfer to third countries, no external fonts, no trackers.',
    trustRoles: 'Answers by role',
    trustRolesBody:
      'Operators only get steps an instructed person may carry out, together with a note on when to call a qualified technician.',
    demoHeading: 'Demo accounts',
    demoIntro: 'Four users, one password. Each role shows a different view of the same data.',
    demoBoundary:
      'A deliberately public demo environment: its own isolated realm, synthetic data only, rate-limited requests, sessions ending after 15 minutes.',
    demoPassword: 'Password for all',
    signInAs: (username) => `Sign in as ${username}`,
    signIn: 'Sign in',
    signInHint:
      'Sign-in is handled by Keycloak. You will be redirected there and returned here afterwards.',
    plateModel: 'Type',
    plateStack: 'Stack',
    plateHosting: 'Hosting',
  },
  demo: {
    operator: 'Ask questions, operator-safe answers.',
    techniker: 'Ask questions, full technical answers.',
    schichtleiter: 'Like the technician, and may upload protocols.',
    admin: 'Administration in Keycloak, no shop-floor function in the app.',
    examplesHeading: 'Try these',
    examplesIntro: 'Choose the machine first, then ask — retrieval is filtered per machine.',
    exampleMachine: 'Machine',
    // The questions stay German even in the English interface: they are meant to be pasted and to
    // actually retrieve something, and the corpus and the machine names are German. Translating
    // them would hand an English reader three questions that return nothing.
    examples: [
      {
        machine: 'Presse 3',
        question: 'Presse 3 zeigt Störung E-47, was kann die Ursache sein?',
        shows:
          'Four protocols with four different root causes for the same fault — one sourced answer citing several of them.',
      },
      {
        machine: 'Förderband FB-04',
        question: 'Förderband FB-04 läuft schief, was tun?',
        shows:
          'A German question answered from an English protocol — still cited, and still German.',
      },
      {
        machine: 'Abfüllanlage AB-02',
        question: 'Die Dosierung an AB-02 ist ungenau, woran liegt das?',
        shows:
          'No protocol in the records covers this — the answer comes back amber and explicitly unsourced.',
      },
    ],
  },
  help: {
    title: 'Help',
    modesHeading: 'The two kinds of answer',
    modeAHeading: 'Mode A — sourced answer (green)',
    modeABody:
      'Protocols in the records cover the question. Every statement names the protocol it came from in square brackets; clicking it opens the original protocol.',
    modeBHeading: 'Mode B — general suggestion (amber)',
    modeBBody:
      'No protocol matches the question. The steps are general engineering knowledge and are shown without a source area — there is nothing here to cite.',
    rolesHeading: 'Who may do what',
    roleOperator:
      'Operator: ask questions. Answers contain only steps for instructed persons, no electrical or mechanical repair work.',
    roleTechniker: 'Technician: ask questions, full technical answers with no restriction.',
    roleSchichtleiter: 'Shift lead: like the technician, and may upload new protocols.',
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
  viewer: {
    title: 'Original protocol',
    loading: 'Loading the protocol …',
    download: 'Download',
    fallbackNote:
      'This protocol does not follow the usual structure. It is shown exactly as it is filed.',
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
    documentMissing:
      'The original protocol can no longer be opened. The answer above was taken from it, but the file is no longer in the records.',
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
