import { describe, expect, it } from 'vitest';

import { MetaRow, parseProtocol, filenameOf } from './protocol-document';

/**
 * The corpus ships a documented quality mix and a Schichtleiter can upload anything that is text, so
 * what is asserted here is not "it parses the happy case" but "it never leaves the reader with
 * nothing". Every shape below comes from a file that actually exists in `backend/data/protocol-files`.
 */
describe('parseProtocol', () => {
  const WELL_FORMED = [
    'WARTUNGSPROTOKOLL',
    '=================',
    '',
    'Maschine: PR-03',
    'Datum: 08.10.2024',
    'Art: STOERUNG',
    'Fehlercode: E-47',
    'Techniker: MK',
    'Stillstand: 410 Minuten',
    '',
    'E-47 Druckabfall im Presshub',
    '',
    'Symptom:',
    'Bediener meldet: Presse kommt nicht auf Druck.',
    '',
    'Ursache:',
    'Innere Leckage am Hauptzylinder.',
    '',
    'Massnahme:',
    'Dichtsatz erneuert.',
    '',
    'Ersatzteile:',
    'Dichtsatz Hauptzylinder 200/140',
  ].join('\n');

  /** Reads a segment of one kind out of the result, so the assertions stay about content. */
  function meta(text: string): readonly MetaRow[] {
    const segment = parseProtocol(text).segments.find((candidate) => candidate.kind === 'meta');
    return segment?.kind === 'meta' ? segment.rows : [];
  }

  function sections(text: string) {
    return parseProtocol(text).segments.filter((segment) => segment.kind === 'section');
  }

  it('reads the header block as labelled rows and drops the banner', () => {
    const document = parseProtocol(WELL_FORMED);

    expect(document.kind).toBe('parsed');
    expect(meta(WELL_FORMED)).toEqual([
      { label: 'Maschine', value: 'PR-03' },
      { label: 'Datum', value: '08.10.2024' },
      { label: 'Art', value: 'STOERUNG' },
      { label: 'Fehlercode', value: 'E-47' },
      { label: 'Techniker', value: 'MK' },
      { label: 'Stillstand', value: '410 Minuten' },
    ]);
    // The banner and its ===== rule are chrome, not content: repeating them inside a dialog whose
    // head already says "Originalprotokoll" is noise.
    expect(document.raw).toContain('WARTUNGSPROTOKOLL');
    expect(document.segments.some((segment) => segment.kind === 'title')).toBe(true);
  });

  it('keeps the title line out of the header block and out of the sections', () => {
    const title = parseProtocol(WELL_FORMED).segments.find((segment) => segment.kind === 'title');

    expect(title?.kind === 'title' && title.text).toBe('E-47 Druckabfall im Presshub');
  });

  it('groups each labelled section with its own body', () => {
    const parsed = sections(WELL_FORMED);

    expect(parsed.map((section) => section.kind === 'section' && section.heading)).toEqual([
      'Symptom',
      'Ursache',
      'Massnahme',
      'Ersatzteile',
    ]);
    expect(parsed[1].kind === 'section' && parsed[1].paragraphs).toEqual([
      'Innere Leckage am Hauptzylinder.',
    ]);
  });

  it('parses the English protocols on the same rules, without knowing their labels', () => {
    // 42 of the 150 protocols are English. Nothing in the parser is a list of German words, which
    // is why they cost no extra code — the structure is what is recognised, not the vocabulary.
    const english = [
      'MAINTENANCE PROTOCOL',
      '====================',
      '',
      'Machine: FB-04',
      'Error code: FB-12',
      '',
      'Belt tracking off to the right',
      '',
      'Cause:',
      'Material carryback built up on the tail pulley.',
    ].join('\n');

    expect(meta(english)).toEqual([
      { label: 'Machine', value: 'FB-04' },
      // A key with a space in it is still a key; the split is on the first colon, not on a word.
      { label: 'Error code', value: 'FB-12' },
    ]);
    expect(sections(english).length).toBe(1);
  });

  it('parses an uploaded protocol that has no banner, no header block and no title', () => {
    // This one is real: a protocol uploaded during verification, filed as bare sections. The messy
    // end of the quality mix omits whichever part it feels like, so every step has to be optional.
    const bare = [
      'Symptom:',
      'Lautes Klopfen aus dem Pumpenaggregat.',
      '',
      'Ursache:',
      'Ansaugfilter zugesetzt.',
    ].join('\n');
    const document = parseProtocol(bare);

    expect(document.kind).toBe('parsed');
    expect(meta(bare)).toEqual([]);
    expect(sections(bare).length).toBe(2);
  });

  it('joins a wrapped paragraph and keeps blank-line-separated ones apart', () => {
    const wrapped = ['Symptom:', 'Erste Zeile', 'zweite Zeile', '', 'Zweiter Absatz'].join('\n');
    const [section] = sections(wrapped);

    expect(section.kind === 'section' && section.paragraphs).toEqual([
      'Erste Zeile zweite Zeile',
      'Zweiter Absatz',
    ]);
  });

  it('falls back to the raw file when nothing about the structure is recognisable', () => {
    // Not an error and not a blank dialog: the file IS the evidence the reader clicked a citation
    // for, and showing it unchanged is a better answer than showing a guess as if it were structure.
    const messy = 'kein druck an der presse, hab den filter getauscht, laeuft wieder';
    const document = parseProtocol(messy);

    expect(document.kind).toBe('raw');
    expect(document.segments).toEqual([]);
    expect(document.raw).toBe(messy);
  });

  it('never throws on the degenerate inputs', () => {
    for (const input of ['', '\n\n\n', ':::', '=====', 'Maschine:']) {
      expect(() => parseProtocol(input)).not.toThrow();
    }
  });

  it('normalises Windows line endings before splitting', () => {
    const document = parseProtocol('Symptom:\r\nKein Druck.\r\n');

    expect(document.kind).toBe('parsed');
    expect(document.raw).not.toContain('\r');
  });
});

describe('filenameOf', () => {
  it('prefers the UTF-8 form the backend sends alongside the plain one', () => {
    const header =
      'inline; filename="PR-03-Olleckage.txt"; filename*=UTF-8\'\'PR-03-%C3%96lleckage.txt';

    expect(filenameOf(header)).toBe('PR-03-Ölleckage.txt');
  });

  it('falls back to the plain filename, then to a default', () => {
    expect(filenameOf('inline; filename="PR-03-E-47.txt"')).toBe('PR-03-E-47.txt');
    expect(filenameOf(null)).toBe('protokoll.txt');
  });
});
