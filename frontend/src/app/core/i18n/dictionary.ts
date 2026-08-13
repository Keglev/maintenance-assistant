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
/**
 * A worked pair for the search help panel: a question that retrieves well, and the one people
 * actually type first.
 *
 * The weak half is not a strawman — it is what a single word does to a semantic search, and showing
 * it beside its fix teaches the rule faster than the rule does.
 */
export interface SearchExample {
  readonly good: string;
  readonly weak: string;
}

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
  /** The theme control. Two states; the operating system is the silent default, not a button. */
  readonly theme: {
    readonly label: string;
    readonly light: string;
    readonly dark: string;
  };
  /** The gear dialog: what this is, who is signed in, and how large to draw it. */
  readonly settings: {
    readonly title: string;
    readonly open: string;
    readonly aboutHeading: string;
    readonly app: string;
    readonly version: string;
    readonly accountHeading: string;
    readonly user: string;
    readonly signedInSince: string;
    readonly fontHeading: string;
    readonly fontHint: string;
    readonly fontNormal: string;
    readonly fontLarge: string;
    readonly fontExtraLarge: string;
  };
  readonly nav: {
    readonly search: string;
    readonly upload: string;
    readonly moderation: string;
    /**
     * The same route as {@link moderation}, named for the OTHER job behind it.
     *
     * The Schichtleiter corrects and neither reviews nor removes, so "Verwaltung" would describe
     * powers they do not have. Naming the entry after the job is part of what makes the trust chain
     * legible from the screen.
     */
    readonly corrections: string;
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
    /**
     * The server-status indicator. Three states, and every one of them says so in words — a
     * status carried only by a colour is a status some readers do not get.
     */
    readonly serverUp: string;
    readonly serverDown: string;
    readonly serverUnknown: string;
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
    readonly tryDemo: string;
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
    /**
     * The "how do I search" panel.
     *
     * It exists because there is no keyword search to fall back on: retrieval is semantic by
     * architecture (ADR-004), so a one-word question is not a narrower search, it is a worse one.
     * A user who does not know that writes "frei" and concludes the assistant is broken.
     */
    readonly helpHeading: string;
    readonly helpToggle: string;
    readonly helpMeaning: string;
    readonly helpContext: string;
    readonly helpSentences: string;
    readonly helpExamplesHeading: string;
    readonly helpGood: string;
    readonly helpWeak: string;
    readonly helpExamples: readonly SearchExample[];
    /**
     * The approved-only facet.
     *
     * OFF by default, and that default is decision 1 of 2026-08-11 rather than a convenience: the
     * admin may not review at a weekend and the factory does not stop, so the protocol about the
     * fault happening right now has to be findable before anyone signs it off.
     */
    readonly approvedOnly: string;
    readonly approvedOnlyHint: string;
    /** Why a filtered search found nothing — otherwise it reads as "no protocols exist". */
    readonly approvedOnlyEmpty: string;
    readonly approvedOnlyEmptyAction: string;
  };
  readonly modeA: {
    readonly badge: string;
    readonly explanation: string;
    readonly sources: string;
    readonly openSource: string;
    readonly similarity: string;
    readonly errorCode: string;
    readonly incidentDate: string;
    /** Collapsing the source list once it is long enough to bury the answer above it. */
    readonly showAllSources: string;
    readonly hideSources: string;
    readonly expandSource: string;
    readonly collapseSource: string;
    /** The drawer over a long answer. The text is clamped, never cut — see SearchAnswer. */
    readonly expandAnswer: string;
    readonly collapseAnswer: string;
    /**
     * Said ONCE at the answer level when any cited source is unapproved.
     *
     * A technician reading an answer must not have to audit the source list to learn that part of
     * it is unreviewed — but this is a line, not a banner: an unapproved source is a normal, decided
     * state of this system, and dressing it as a fault would make the feature look broken.
     */
    readonly unapprovedSources: (count: number) => string;
  };
  readonly viewer: {
    readonly title: string;
    readonly loading: string;
    readonly download: string;
    readonly fallbackNote: string;
    /**
     * The history section: what has been done to this protocol, and by whom.
     *
     * <p>Carlos's ruling of 2026-08-14: the records TABLE shows the approval STATE and nothing
     * else, and the provenance — who, when, why — moves here. A column that had to carry
     * "Freigegeben system:corpus-seed · 12.08.2026" truncated in production; the same sentence read
     * once, beside the document it is about, is worth having.
     */
    readonly historyHeading: string;
    /** One verb per ledger action, so a reader never sees `UNAPPROVE` on screen. */
    readonly historyEdited: string;
    readonly historyApproved: string;
    readonly historyUnapproved: string;
    readonly historyDeleted: string;
    /** "und 4 ältere Einträge" — the tail the viewer deliberately does not show. */
    readonly historyMore: (count: number) => string;
    /**
     * The seeded case: approved by a migration, with no ledger row behind it.
     *
     * The 150 seeded protocols were born approved (decision 2 of 2026-08-11) and no human act
     * produced that, so `system:corpus-seed` is the truthful record rather than a placeholder — and
     * saying so is the point. A viewer that showed nothing here would imply the approval came from
     * somewhere it did not.
     */
    readonly historySeeded: string;
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
    readonly uploadTooLarge: string;
    readonly uploadRejected: string;
    readonly uploadRateLimited: string;
    readonly generic: string;
  };
  /**
   * The Protokollverwaltung view.
   *
   * The key stays `moderation` on purpose. The user-visible name changed because "Verwaltung" and
   * "Moderation" read like a discussion forum rather than like a maintenance record; the API paths
   * (`/api/moderation/**`), the component and this key did not, because renaming an identifier to
   * follow a label churns the tests and the wire contract for something no user can see.
   */
  readonly moderation: {
    readonly heading: string;
    readonly intro: string;
    /**
     * The same view, named for the corrector.
     *
     * Two roles reach this route since 2026-08-14 and do different things on it: the admin reviews,
     * approves and archives; the Schichtleiter corrects and does none of those. A heading that
     * described powers the reader does not have would contradict the buttons underneath it.
     */
    readonly headingCorrector: string;
    readonly introCorrector: string;
    /**
     * Shown in the correction dialog when the protocol is currently APPROVED.
     *
     * An edit resets approval unconditionally (#53), and the person about to make one is owed that
     * before they press save — a correction is how an approved protocol quietly stops being
     * approved, and nobody should discover it from the list afterwards.
     */
    readonly editResetsApproval: string;
    readonly uploadedBy: string;
    readonly actions: string;
    readonly open: string;
    readonly delete: string;
    readonly loading: string;
    readonly empty: string;
    /** The other "nothing here": a corpus that has rows, and a filter that matched none of them. */
    readonly emptyFiltered: string;
    readonly filterLegend: string;
    readonly filterMachineAll: string;
    readonly filterTitle: string;
    readonly filterFrom: string;
    readonly filterTo: string;
    /** Why two of the four fields are disabled — an unexplained disabled field reads as broken. */
    readonly filterHint: string;
    readonly filterApply: string;
    readonly filterReset: string;
    readonly removed: string;
    readonly alreadyGone: string;
    readonly confirmTitle: string;
    readonly confirmBody: string;
    /** The two halves of the view: the live corpus and what was removed from it. */
    readonly tabCorpus: string;
    readonly tabArchive: string;
    readonly edit: string;
    readonly editTitle: string;
    readonly editIntro: string;
    /** Why machine and type are shown but locked — an unexplained disabled field reads as broken. */
    readonly editLockedHint: string;
    readonly editContent: string;
    readonly editContentHint: string;
    readonly editLoading: string;
    readonly editSave: string;
    readonly editSaving: string;
    readonly edited: string;
    readonly reason: string;
    readonly reasonHint: string;
    readonly reasonPlaceholder: string;
    readonly reasonRequired: string;
    readonly archiveIntro: string;
    /** The 50-per-machine cap and the fact that restore does not exist. */
    readonly archiveHint: (cap: number) => string;
    readonly archiveEmpty: string;
    readonly archiveLoading: string;
    readonly archiveDeletedAt: string;
    readonly archiveDeletedBy: string;
    readonly archiveComment: string;
    readonly archiveNoComment: string;
    readonly identityLocked: string;
    readonly alreadyArchived: string;
    /** Native date inputs follow the BROWSER locale, so the expected format is spelled out. */
    readonly filterDateFormatHint: string;
    /** The approval column and its filter. Not subject to the machine-first rule — a queue is whole. */
    readonly filterApproval: string;
    readonly filterApprovalAll: string;
  };
  /**
   * Approval: the state, and the administrator's control over it.
   *
   * Its own section rather than more `moderation` keys, because the STATE is read on three surfaces
   * that are not the Verwaltung view — a source card under an answer, the viewer dialog, and the
   * answer-level note — while only the verbs below belong to moderation. Splitting them by owner
   * keeps a technician-facing word out of an admin-facing section.
   */
  readonly approval: {
    readonly approved: string;
    readonly unapproved: string;
    /** The table column, and the label the viewer's head uses. */
    readonly column: string;
    readonly approve: string;
    readonly withdraw: string;
    /** The button in a table row. The full phrase sets the width of six other columns. */
    readonly withdrawShort: string;
    readonly approving: string;
    /** Confirmation of the act, named so the reviewer knows which of twenty rows moved. */
    readonly approvedNotice: string;
    readonly withdrawnNotice: string;
    readonly withdrawTitle: string;
    readonly withdrawBody: string;
    readonly withdrawReasonPlaceholder: string;
    /** Approving something that is in the archive. Archived is final. */
    readonly archived: string;
  };
  /**
   * Duplicate detection, shown before an approval when the corpus already holds something close.
   *
   * <p><b>The wording is the feature.</b> Every string here is written to inform rather than to
   * accuse: no "warning", no "problem", no "are you sure". The approve button in this dialog is
   * enabled the whole time, and the text has to match — a sentence that implies wrongdoing beside a
   * button that works anyway teaches the reader to distrust one of the two. Four legitimate E-47
   * protocols on one machine are the reason: being similar is normal in this corpus.
   */
  readonly duplicates: {
    readonly title: string;
    /** One sentence: what was found, and that the decision is still the reader's. */
    readonly intro: (count: number) => string;
    /** What "similar" is measured on, said once so the percentage is not a mystery number. */
    readonly method: (percent: number) => string;
    readonly match: string;
    readonly openCandidate: string;
    /** "und 2 weitere" — the tail that is counted rather than shown. */
    readonly more: (count: number) => string;
  };
  /**
   * The paging control, shared by the three tables that have one.
   *
   * Its own section rather than a copy under each view: the labels are the control's, not the
   * table's, and three copies of "Nächste Seite" is three chances for them to drift apart.
   */
  readonly pager: {
    readonly label: string;
    readonly previous: string;
    readonly next: string;
    /** "Seite 2 von 16 (151 Einträge)" — three numbers, one sentence per language. */
    readonly pageOf: (page: number, pages: number, total: number) => string;
  };
  readonly upload: {
    readonly heading: string;
    readonly intro: string;
    readonly inputMode: string;
    readonly modeFile: string;
    readonly modeText: string;
    readonly file: string;
    readonly text: string;
    readonly textHint: string;
    readonly textPlaceholder: string;
    readonly machine: string;
    readonly type: string;
    readonly typeFault: string;
    readonly typeMaintenance: string;
    readonly title: string;
    readonly errorCode: string;
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
    readonly required: string;
    /** What the endpoint accepts, said before the file picker rather than by a 400 afterwards. */
    readonly formatHint: string;
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
    light: 'Hell',
    dark: 'Dunkel',
  },
  settings: {
    title: 'System',
    open: 'Systemeinstellungen',
    aboutHeading: 'Anwendung',
    app: 'Programm',
    version: 'Version',
    accountHeading: 'Angemeldet',
    user: 'Benutzer',
    signedInSince: 'Angemeldet seit',
    fontHeading: 'Schriftgröße',
    fontHint:
      'Gilt für die ganze Anwendung, auch für Schaltflächen und Abstände. Die Einstellung bleibt erhalten.',
    fontNormal: 'Normal',
    fontLarge: 'Groß',
    fontExtraLarge: 'Sehr groß',
  },
  nav: {
    search: 'Suche',
    upload: 'Protokoll hochladen',
    moderation: 'Protokollverwaltung',
    corrections: 'Korrekturen',
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
    serverUp: 'Server: online',
    serverDown: 'Server: offline',
    serverUnknown: 'Server: wird geprüft …',
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
    tryDemo: 'Demo ausprobieren',
    signIn: 'Anmelden',
    signInHint:
      'Die Anmeldung läuft über Keycloak. Sie werden dorthin weitergeleitet und kommen anschließend hierher zurück.',
    plateModel: 'Typ',
    plateStack: 'Technik',
    plateHosting: 'Betrieb',
  },
  demo: {
    operator: 'Fragen stellen, bedienerseitige Antworten.',
    techniker: 'Fragen stellen, vollständige technische Antworten, Protokolle schreiben.',
    schichtleiter: 'Wie Techniker, zusätzlich Protokolle korrigieren.',
    admin:
      'Gibt Protokolle frei, prüft und entfernt sie in der Protokollverwaltung. Stellt keine Fragen und schreibt nichts.',
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
    roleTechniker:
      'Techniker: Fragen stellen, vollständige technische Antworten ohne Einschränkung, Protokolle schreiben — korrigieren nie, auch nicht die eigenen.',
    roleSchichtleiter:
      'Schichtleiter: wie Techniker, zusätzlich neue Protokolle korrigieren — mit Begründung und sofortiger Neuindexierung.',
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
    helpHeading: 'Wie suche ich?',
    helpToggle: 'Wie suche ich?',
    helpMeaning:
      'Der Assistent sucht nach der BEDEUTUNG Ihrer Fehlerbeschreibung — nicht nach Titeln und nicht nach exakten Wörtern. Ein Protokoll wird auch dann gefunden, wenn darin andere Wörter stehen als in Ihrer Frage.',
    helpContext:
      'Maschine, Fehlercode und Titel stehen in jedem indexierten Abschnitt. Nennen Sie den Fehlercode, wenn Sie ihn haben — das ist der stärkste einzelne Hinweis.',
    helpSentences:
      'Ganze kurze Sätze schlagen einzelne Wörter. Ein Wort beschreibt keinen Fehler, und die Suche hat nichts, womit sie vergleichen kann.',
    helpExamplesHeading: 'Beispiele',
    helpGood: 'Gut',
    helpWeak: 'Schwach',
    helpExamples: [
      { good: 'Kompressor startet nicht, Fehlercode X-99', weak: 'frei' },
      { good: 'Presse kommt nicht auf Druck, E-47 steht an', weak: 'Druck' },
      { good: 'Band läuft schief nach dem Rollenwechsel', weak: 'Band' },
    ],
    approvedOnly: 'Nur freigegebene Protokolle',
    approvedOnlyHint:
      'Standardmässig wird der ganze Bestand durchsucht — auch Protokolle, die noch niemand freigegeben hat.',
    approvedOnlyEmpty:
      'Gesucht wurde nur in freigegebenen Protokollen. Möglicherweise gibt es ein passendes Protokoll, das noch nicht freigegeben ist.',
    approvedOnlyEmptyAction: 'Im ganzen Bestand suchen',
  },
  modeA: {
    badge: 'Belegte Antwort',
    explanation: 'Jede Aussage stammt aus einem Protokoll aus dem Bestand und ist belegt.',
    sources: 'Quellen',
    openSource: 'Originalprotokoll öffnen',
    similarity: 'Übereinstimmung',
    errorCode: 'Fehlercode',
    incidentDate: 'Datum',
    showAllSources: 'Alle Quellen anzeigen',
    hideSources: 'Quellen einklappen',
    expandSource: 'Quelle aufklappen',
    collapseSource: 'Quelle zuklappen',
    expandAnswer: 'Vollständige Antwort anzeigen',
    collapseAnswer: 'Antwort einklappen',
    unapprovedSources: (count) =>
      count === 1
        ? 'Eine der Quellen ist noch nicht freigegeben — geprüft hat sie noch niemand.'
        : `${count} der Quellen sind noch nicht freigegeben — geprüft hat sie noch niemand.`,
  },
  viewer: {
    title: 'Originalprotokoll',
    loading: 'Protokoll wird geladen …',
    download: 'Herunterladen',
    fallbackNote:
      'Dieses Protokoll folgt nicht der üblichen Gliederung. Es wird unverändert angezeigt.',
    historyHeading: 'Verlauf',
    historyEdited: 'Korrigiert',
    historyApproved: 'Freigegeben',
    historyUnapproved: 'Freigabe zurückgezogen',
    historyDeleted: 'Aus dem Bestand entfernt',
    historyMore: (count) =>
      count === 1
        ? 'Ein älterer Eintrag wird hier nicht angezeigt.'
        : `${count} ältere Einträge werden hier nicht angezeigt.`,
    historySeeded:
      'Dieses Protokoll wurde beim Aufbau des Bestands freigegeben, nicht von einer Person geprüft.',
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
    uploadTooLarge:
      'Die Datei ist zu groß. Ein Protokoll ist eine Seite Text — bitte kürzen oder als Text eingeben.',
    uploadRejected:
      'Diese Datei kann nicht gelesen werden. Angenommen werden nur Textdateien (.txt) mit Inhalt — PDF und Scans noch nicht.',
    uploadRateLimited:
      'Zu viele Uploads in kurzer Zeit. Bitte kurz warten und erneut versuchen.',
    generic: 'Die Anfrage ist fehlgeschlagen.',
  },
  moderation: {
    heading: 'Protokollverwaltung',
    intro:
      'Alle Protokolle im Bestand. Öffnen zum Prüfen, entfernen was nicht hineingehört.',
    headingCorrector: 'Protokolle korrigieren',
    introCorrector:
      'Alle Protokolle im Bestand. Öffnen zum Nachlesen, korrigieren was fachlich falsch ist — mit Begründung und sofortiger Neuindexierung.',
    editResetsApproval:
      'Dieses Protokoll ist freigegeben. Mit der Korrektur verliert es die Freigabe und muss erneut geprüft werden.',
    uploadedBy: 'Hochgeladen von',
    actions: 'Aktionen',
    open: 'Öffnen',
    delete: 'Löschen',
    loading: 'Protokolle werden geladen …',
    empty: 'Keine Protokolle im Bestand.',
    emptyFiltered: 'Zu diesem Filter gibt es kein Protokoll. Filter ändern oder zurücksetzen.',
    filterLegend: 'Protokolle filtern',
    filterMachineAll: 'Alle Maschinen',
    filterTitle: 'Titel enthält',
    filterFrom: 'Von',
    filterTo: 'Bis',
    filterHint:
      'Titel und Zeitraum stehen zur Verfügung, sobald eine Maschine gewählt ist — über den ganzen Bestand hinweg findet ein Titelstück vor allem Protokolle anderer Maschinen.',
    filterApply: 'Filtern',
    filterReset: 'Zurücksetzen',
    removed: 'Protokoll entfernt',
    alreadyGone: 'Dieses Protokoll ist nicht mehr im Bestand.',
    confirmTitle: 'Protokoll entfernen?',
    confirmBody:
      'Sofort aus Suche und Bestand entfernt, für alle Rollen. Es gibt kein Wiederherstellen. Das Protokoll bleibt für die Prüfung unter „Gelöschte Protokolle“ lesbar.',
    tabCorpus: 'Bestand',
    tabArchive: 'Gelöschte Protokolle',
    edit: 'Bearbeiten',
    editTitle: 'Protokoll korrigieren',
    editIntro:
      'Der Text wird ersetzt und das Protokoll sofort neu indexiert — die Suche arbeitet danach mit der korrigierten Fassung.',
    editLockedHint:
      'Maschine und Art sind nicht änderbar — bei falscher Zuordnung: löschen und neu anlegen.',
    editContent: 'Protokolltext',
    editContentHint: 'Der gespeicherte Text, wie er im Protokoll steht. Freitext ist in Ordnung.',
    editLoading: 'Protokolltext wird geladen …',
    editSave: 'Korrektur speichern',
    editSaving: 'Wird gespeichert …',
    edited: 'Protokoll korrigiert — wird neu indexiert',
    reason: 'Grund',
    reasonHint: 'Wird mit Name und Zeitpunkt protokolliert.',
    reasonPlaceholder: 'z. B. Anzugsmoment war falsch, laut Herstellerangabe 120 Nm.',
    reasonRequired: 'Ohne Grund geht es nicht — eine Änderung am Bestand muss nachvollziehbar sein.',
    archiveIntro:
      'Entfernte Protokolle. Aus Suche und Bestand verschwunden, hier für die Prüfung noch lesbar.',
    archiveHint: (cap) =>
      `Kein Wiederherstellen — das ist Absicht. Je Maschine werden die letzten ${cap} Löschungen aufbewahrt, ältere werden endgültig gelöscht.`,
    archiveEmpty: 'Es wurde noch nichts entfernt.',
    archiveLoading: 'Gelöschte Protokolle werden geladen …',
    archiveDeletedAt: 'Entfernt am',
    archiveDeletedBy: 'Entfernt von',
    archiveComment: 'Grund',
    archiveNoComment: '—',
    identityLocked:
      'Maschine und Art lassen sich nicht ändern. Ist die Zuordnung falsch, ist das Protokoll an der Wurzel falsch: löschen und neu anlegen.',
    alreadyArchived:
      'Dieses Protokoll wurde entfernt und lässt sich nicht mehr bearbeiten. Entfernt ist endgültig.',
    filterDateFormatHint: 'Format: TT.MM.JJJJ',
    filterApproval: 'Freigabe',
    filterApprovalAll: 'Alle',
  },
  approval: {
    approved: 'Freigegeben',
    unapproved: 'Nicht freigegeben',
    column: 'Freigabe',
    approve: 'Freigeben',
    withdraw: 'Freigabe zurückziehen',
    withdrawShort: 'Zurückziehen',
    approving: 'Wird gespeichert …',
    approvedNotice: 'Protokoll freigegeben',
    withdrawnNotice: 'Freigabe zurückgezogen',
    withdrawTitle: 'Freigabe zurückziehen?',
    withdrawBody:
      'Das Protokoll bleibt im Bestand und weiterhin durchsuchbar. Es gilt danach als ungeprüft und erscheint wieder in der Freigabeliste.',
    withdrawReasonPlaceholder: 'z. B. Massnahme passt nicht zur beschriebenen Ursache.',
    archived:
      'Dieses Protokoll wurde entfernt und lässt sich nicht mehr freigeben. Entfernt ist endgültig.',
  },
  duplicates: {
    title: 'Ähnliche Protokolle an dieser Maschine',
    intro: (count) =>
      count === 1
        ? 'Ein Protokoll an derselben Maschine beschreibt Ähnliches. Das ist häufig in Ordnung — dieselbe Störung kann mehrere Ursachen haben. Bitte kurz vergleichen und dann entscheiden.'
        : `${count} Protokolle an derselben Maschine beschreiben Ähnliches. Das ist häufig in Ordnung — dieselbe Störung kann mehrere Ursachen haben. Bitte kurz vergleichen und dann entscheiden.`,
    method: (percent) =>
      `Verglichen wird der gesamte Protokolltext, nicht nur der Titel. Angezeigt wird alles ab ${percent} % Übereinstimmung.`,
    match: 'Übereinstimmung',
    openCandidate: 'Protokoll öffnen',
    more: (count) => `und ${count} weitere über der Schwelle`,
  },
  pager: {
    label: 'Seitenblättern',
    previous: 'Vorherige Seite',
    next: 'Nächste Seite',
    pageOf: (page, pages, total) => `Seite ${page} von ${pages} (${total} Einträge)`,
  },
  upload: {
    heading: 'Protokoll hochladen',
    intro:
      'Protokoll direkt eingeben oder als Textdatei hochladen (UTF-8). PDF und Scans werden noch nicht ausgewertet.',
    inputMode: 'Eingabeart',
    modeFile: 'Datei hochladen',
    modeText: 'Text eingeben',
    file: 'Datei',
    text: 'Protokolltext',
    textHint: 'Symptom, Ursache und Massnahme in eigenen Worten — Freitext ist in Ordnung.',
    textPlaceholder:
      'z. B. Presse kommt nicht auf Druck, E-47 steht an. Dichtsatz am Hauptzylinder getauscht, Druck wieder stabil.',
    machine: 'Maschine',
    type: 'Art',
    typeFault: 'Störung',
    typeMaintenance: 'Wartung',
    title: 'Titel',
    errorCode: 'Fehlercode',
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
    required: 'Pflichtfeld',
    formatHint: 'Nur Textdateien: .txt, max. 256 KB',
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
    light: 'Light',
    dark: 'Dark',
  },
  settings: {
    title: 'System',
    open: 'System settings',
    aboutHeading: 'Application',
    app: 'Program',
    version: 'Version',
    accountHeading: 'Signed in',
    user: 'User',
    signedInSince: 'Signed in since',
    fontHeading: 'Text size',
    fontHint:
      'Applies to the whole application, including buttons and spacing. The setting is remembered.',
    fontNormal: 'Normal',
    fontLarge: 'Large',
    fontExtraLarge: 'Extra large',
  },
  nav: {
    search: 'Search',
    upload: 'Upload protocol',
    moderation: 'Protocol management',
    corrections: 'Corrections',
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
    serverUp: 'Server: online',
    serverDown: 'Server: offline',
    serverUnknown: 'Server: checking …',
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
    tryDemo: 'Try the demo',
    signIn: 'Sign in',
    signInHint:
      'Sign-in is handled by Keycloak. You will be redirected there and returned here afterwards.',
    plateModel: 'Type',
    plateStack: 'Stack',
    plateHosting: 'Hosting',
  },
  demo: {
    operator: 'Ask questions, operator-safe answers.',
    techniker: 'Ask questions, full technical answers, file protocols.',
    schichtleiter: 'Like the technician, and may correct protocols.',
    admin:
      'Approves protocols, reviews and removes them in Protocol management. Does not ask questions and does not write.',
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
    roleTechniker:
      'Technician: ask questions, full technical answers with no restriction, and file protocols — never corrects one, not even their own.',
    roleSchichtleiter:
      'Shift lead: like the technician, and may correct protocols — with a reason and an immediate re-index.',
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
    helpHeading: 'How do I search?',
    helpToggle: 'How do I search?',
    helpMeaning:
      'The assistant searches by the MEANING of your fault description — not by title and not by exact words. A protocol is found even when it uses different words than your question does.',
    helpContext:
      'Machine, fault code and title are part of every indexed passage. Name the fault code when you have it: it is the single strongest hint you can give.',
    helpSentences:
      'Whole short sentences beat single words. One word does not describe a fault, and the search has nothing to compare it against.',
    helpExamplesHeading: 'Examples',
    helpGood: 'Good',
    helpWeak: 'Weak',
    helpExamples: [
      { good: 'Compressor does not start, fault code X-99', weak: 'free' },
      { good: 'Press does not build up pressure, E-47 showing', weak: 'pressure' },
      { good: 'Belt mistracks after the roller change', weak: 'belt' },
    ],
    approvedOnly: 'Approved protocols only',
    approvedOnlyHint:
      'By default the whole corpus is searched — including protocols nobody has approved yet.',
    approvedOnlyEmpty:
      'Only approved protocols were searched. There may be a matching protocol that has not been approved yet.',
    approvedOnlyEmptyAction: 'Search the whole corpus',
  },
  modeA: {
    badge: 'Sourced answer',
    explanation: 'Every statement comes from a protocol in the records and is cited.',
    sources: 'Sources',
    openSource: 'Open the original protocol',
    similarity: 'Match',
    errorCode: 'Fault code',
    incidentDate: 'Date',
    showAllSources: 'Show all sources',
    hideSources: 'Collapse sources',
    expandSource: 'Expand source',
    collapseSource: 'Collapse source',
    expandAnswer: 'Show full answer',
    collapseAnswer: 'Collapse answer',
    unapprovedSources: (count) =>
      count === 1
        ? 'One of the sources has not been approved yet — nobody has reviewed it.'
        : `${count} of the sources have not been approved yet — nobody has reviewed them.`,
  },
  viewer: {
    title: 'Original protocol',
    loading: 'Loading the protocol …',
    download: 'Download',
    fallbackNote:
      'This protocol does not follow the usual structure. It is shown exactly as it is filed.',
    historyHeading: 'History',
    historyEdited: 'Corrected',
    historyApproved: 'Approved',
    historyUnapproved: 'Approval withdrawn',
    historyDeleted: 'Removed from the records',
    historyMore: (count) =>
      count === 1
        ? 'One older entry is not shown here.'
        : `${count} older entries are not shown here.`,
    historySeeded:
      'This protocol was approved when the records were seeded, not reviewed by a person.',
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
    uploadTooLarge:
      'The file is too large. A protocol is a page of text — please shorten it or type it in instead.',
    uploadRejected:
      'This file cannot be read. Only text files (.txt) with content are accepted — PDFs and scans are not supported yet.',
    uploadRateLimited: 'Too many uploads in a short time. Please wait a moment and try again.',
    generic: 'The request failed.',
  },
  moderation: {
    heading: 'Protocol management',
    intro: 'Every protocol in the records. Open one to review it, remove what does not belong.',
    headingCorrector: 'Correct protocols',
    introCorrector:
      'Every protocol in the records. Open one to read it, correct what is technically wrong — with a reason and an immediate re-index.',
    editResetsApproval:
      'This protocol is approved. Correcting it withdraws that approval, and it has to be reviewed again.',
    uploadedBy: 'Uploaded by',
    actions: 'Actions',
    open: 'Open',
    delete: 'Delete',
    loading: 'Loading protocols …',
    empty: 'No protocols in the records.',
    emptyFiltered: 'No protocol matches this filter. Change it or reset it.',
    filterLegend: 'Filter protocols',
    filterMachineAll: 'All machines',
    filterTitle: 'Title contains',
    filterFrom: 'From',
    filterTo: 'To',
    filterHint:
      'Title and date range become available once a machine is chosen — across the whole corpus a title fragment mostly finds protocols from other machines.',
    filterApply: 'Filter',
    filterReset: 'Reset',
    removed: 'Protocol removed',
    alreadyGone: 'This protocol is no longer in the records.',
    confirmTitle: 'Remove this protocol?',
    confirmBody:
      'Removed from search and from the records immediately, for every role. There is no restore. The protocol stays readable for review under "Deleted protocols".',
    tabCorpus: 'Records',
    tabArchive: 'Deleted protocols',
    edit: 'Edit',
    editTitle: 'Correct this protocol',
    editIntro:
      'The text is replaced and the protocol is re-indexed straight away — search works from the corrected version afterwards.',
    editLockedHint:
      'Machine and type cannot be changed — if either is wrong, delete this protocol and file a new one.',
    editContent: 'Protocol text',
    editContentHint: 'The stored text, as the protocol has it. Free text is fine.',
    editLoading: 'Loading the protocol text …',
    editSave: 'Save correction',
    editSaving: 'Saving …',
    edited: 'Protocol corrected — re-indexing',
    reason: 'Reason',
    reasonHint: 'Recorded with your name and the time.',
    reasonPlaceholder: 'e.g. Torque figure was wrong; the manufacturer specifies 120 Nm.',
    reasonRequired: 'A reason is required — a change to the records has to be accountable.',
    archiveIntro:
      'Protocols that were removed. Gone from search and from the records, still readable here for review.',
    archiveHint: (cap) =>
      `There is no restore, by design. The last ${cap} deletions per machine are kept; older ones are deleted for good.`,
    archiveEmpty: 'Nothing has been removed yet.',
    archiveLoading: 'Loading deleted protocols …',
    archiveDeletedAt: 'Removed on',
    archiveDeletedBy: 'Removed by',
    archiveComment: 'Reason',
    archiveNoComment: '—',
    identityLocked:
      'Machine and type cannot be changed. If the assignment is wrong, the protocol is wrong at its root: delete it and file a new one.',
    alreadyArchived:
      'This protocol was removed and can no longer be edited. Removed is final.',
    filterDateFormatHint: 'Format: MM/DD/YYYY',
    filterApproval: 'Approval',
    filterApprovalAll: 'All',
  },
  approval: {
    approved: 'Approved',
    unapproved: 'Not approved',
    column: 'Approval',
    approve: 'Approve',
    withdraw: 'Withdraw approval',
    withdrawShort: 'Withdraw',
    approving: 'Saving …',
    approvedNotice: 'Protocol approved',
    withdrawnNotice: 'Approval withdrawn',
    withdrawTitle: 'Withdraw approval?',
    withdrawBody:
      'The protocol stays in the records and stays searchable. It counts as unreviewed afterwards and returns to the approval queue.',
    withdrawReasonPlaceholder: 'e.g. The action taken does not match the stated cause.',
    archived: 'This protocol was removed and can no longer be approved. Removed is final.',
  },
  duplicates: {
    title: 'Similar protocols on this machine',
    intro: (count) =>
      count === 1
        ? 'One protocol on the same machine describes something similar. That is often fine — one fault can have several causes. Have a quick look, then decide.'
        : `${count} protocols on the same machine describe something similar. That is often fine — one fault can have several causes. Have a quick look, then decide.`,
    method: (percent) =>
      `The comparison uses the whole protocol text, not just the title. Everything from ${percent} % agreement upwards is listed.`,
    match: 'agreement',
    openCandidate: 'Open protocol',
    more: (count) => `and ${count} more above the threshold`,
  },
  pager: {
    label: 'Pagination',
    previous: 'Previous page',
    next: 'Next page',
    pageOf: (page, pages, total) => `Page ${page} of ${pages} (${total} entries)`,
  },
  upload: {
    heading: 'Upload a protocol',
    intro:
      'Write the protocol here or upload it as a text file (UTF-8). PDFs and scans are not extracted yet.',
    inputMode: 'Input',
    modeFile: 'Upload a file',
    modeText: 'Write the text',
    file: 'File',
    text: 'Protocol text',
    textHint: 'Symptom, cause and action in your own words — free text is fine.',
    textPlaceholder:
      'e.g. Press does not build up pressure, E-47 showing. Seal kit on the main cylinder replaced, pressure stable again.',
    machine: 'Machine',
    type: 'Type',
    typeFault: 'Fault',
    typeMaintenance: 'Maintenance',
    title: 'Title',
    errorCode: 'Fault code',
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
    required: 'required',
    formatHint: 'Text files only: .txt, max 256 KB',
  },
};

export const DICTIONARIES: Readonly<Record<UiLanguage, Dictionary>> = { de: DE, en: EN };
