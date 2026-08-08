/**
 * One piece of a parsed protocol.
 *
 * Typed segments, exactly like the answer renderer's: the template switches on `kind` and binds
 * text, so there is no path from a protocol file into `innerHTML`. The corpus is synthetic today and
 * a Schichtleiter can upload arbitrary text tomorrow, which makes this the same rule and not merely
 * the same style.
 */
export type ProtocolSegment =
  /** The `Key: Value` header block — machine, date, type, fault code, technician, downtime. */
  | { readonly kind: 'meta'; readonly rows: readonly MetaRow[] }
  /** The one-line description that follows the header block. */
  | { readonly kind: 'title'; readonly text: string }
  /** A labelled section: `Symptom:`, `Ursache:`, `Massnahme:`, `Ersatzteile:` and their EN twins. */
  | { readonly kind: 'section'; readonly heading: string; readonly paragraphs: readonly string[] };

export interface MetaRow {
  readonly label: string;
  readonly value: string;
}

/**
 * A protocol as the viewer will render it.
 *
 * `kind` is the honest half: `'parsed'` means the structure below was recognised, `'raw'` means it
 * was not and the viewer must fall back to the file as it stands. The corpus ships a documented
 * quality mix (90 well-written, 45 terse, 15 messy) and one uploaded protocol already has no banner,
 * no header block and no title at all — so "the parser did not recognise this" is a normal outcome
 * to be rendered, not an error to be reported.
 */
export interface ProtocolDocument {
  readonly kind: 'parsed' | 'raw';
  readonly segments: readonly ProtocolSegment[];
  readonly raw: string;
}

/** A `Key: Value` header line. The key is short and colon-free; the value is whatever follows. */
const META_LINE = /^([^:]{1,40}):[ \t]+(\S.*)$/;

/** A section label alone on its line — the value-less form is what separates it from a meta row. */
const SECTION_LINE = /^(.{1,40}):[ \t]*$/;

/** The `=====` rule under the `WARTUNGSPROTOKOLL` / `MAINTENANCE PROTOCOL` banner. */
const RULE_LINE = /^[=-]{3,}$/;

/**
 * Parses a protocol file into typed segments, and never throws.
 *
 * The structure is stable across the corpus but not guaranteed: it is plain text written by people,
 * and the messy end of the quality mix omits whichever part it feels like. So every step is
 * optional. A file with only sections parses; a file with only a header block parses; a file that
 * matches nothing at all comes back as `'raw'` and the viewer shows it preformatted. What must never
 * happen is a blank dialog — the reader clicked a citation and is entitled to the evidence behind
 * it, in whatever shape it exists.
 */
export function parseProtocol(text: string): ProtocolDocument {
  const raw = text.replace(/\r\n?/g, '\n');
  const lines = raw.split('\n');
  const segments: ProtocolSegment[] = [];

  // The banner is followed by a blank line in every well-formed file, and the header block starts
  // after it — so the blanks are stepped over before anything is read, not after.
  let index = skipBlank(lines, skipBanner(lines));

  const rows = readMeta(lines, index);
  if (rows.length > 0) {
    segments.push({ kind: 'meta', rows });
    index += rows.length;
  }

  index = skipBlank(lines, index);

  // The title is the first non-empty line that is neither a section label nor another header row.
  // A protocol that starts straight into `Symptom:` simply has none.
  if (index < lines.length && !SECTION_LINE.test(lines[index]) && !META_LINE.test(lines[index])) {
    segments.push({ kind: 'title', text: lines[index].trim() });
    index += 1;
  }

  segments.push(...readSections(lines, index));

  // Recognition means a header block or at least one LABELLED section. An unlabelled run of prose
  // does not count: splitting arbitrary text into a "title" and some paragraphs is the parser
  // guessing, and a guess rendered as structure is worse than the file shown as it stands.
  const recognised = segments.some(
    (segment) =>
      segment.kind === 'meta' || (segment.kind === 'section' && segment.heading.length > 0),
  );
  return recognised ? { kind: 'parsed', segments, raw } : { kind: 'raw', segments: [], raw };
}

/** Steps over the banner line and its `=====` rule, if the file has them. */
function skipBanner(lines: readonly string[]): number {
  const start = skipBlank(lines, 0);
  if (start + 1 < lines.length && !lines[start].includes(':') && RULE_LINE.test(lines[start + 1])) {
    return start + 2;
  }
  return start;
}

/** The run of `Key: Value` lines starting at `from`, which ends at the first line that is not one. */
function readMeta(lines: readonly string[], from: number): MetaRow[] {
  const rows: MetaRow[] = [];
  for (let at = from; at < lines.length; at += 1) {
    const match = META_LINE.exec(lines[at]);
    if (!match) {
      break;
    }
    rows.push({ label: match[1].trim(), value: match[2].trim() });
  }
  return rows;
}

/**
 * Everything from `from` on, grouped under the section labels it contains.
 *
 * Text appearing before the first label is kept under an empty heading rather than dropped: an
 * unlabelled paragraph is still what the technician wrote, and losing it would make the viewer a
 * less complete record than the file.
 */
function readSections(lines: readonly string[], from: number): ProtocolSegment[] {
  const sections: { heading: string; paragraphs: string[] }[] = [];
  let current: { heading: string; paragraphs: string[] } | null = null;
  let buffer: string[] = [];

  const flush = () => {
    const paragraph = buffer.join(' ').trim();
    buffer = [];
    if (paragraph && current) {
      current.paragraphs.push(paragraph);
    }
  };

  for (let at = from; at < lines.length; at += 1) {
    const line = lines[at];
    const heading = SECTION_LINE.exec(line);

    if (heading) {
      flush();
      current = { heading: heading[1].trim(), paragraphs: [] };
      sections.push(current);
      continue;
    }
    if (line.trim() === '') {
      flush();
      continue;
    }
    if (!current) {
      current = { heading: '', paragraphs: [] };
      sections.push(current);
    }
    buffer.push(line.trim());
  }
  flush();

  return sections
    .filter((section) => section.heading !== '' || section.paragraphs.length > 0)
    .map((section) => ({
      kind: 'section' as const,
      heading: section.heading,
      paragraphs: section.paragraphs,
    }));
}

function skipBlank(lines: readonly string[], from: number): number {
  let at = from;
  while (at < lines.length && lines[at].trim() === '') {
    at += 1;
  }
  return at;
}

/** The readable filename the backend put in `Content-Disposition`, for the download action. */
export function filenameOf(contentDisposition: string | null): string {
  if (!contentDisposition) {
    return 'protokoll.txt';
  }
  // The UTF-8 form first: the backend sends both, and it is the one that survives umlauts.
  const encoded = /filename\*=UTF-8''([^;]+)/i.exec(contentDisposition);
  if (encoded) {
    try {
      return decodeURIComponent(encoded[1]);
    } catch {
      // A malformed header is not worth failing a download over.
    }
  }
  const plain = /filename="?([^";]+)"?/i.exec(contentDisposition);
  return plain ? plain[1] : 'protokoll.txt';
}
