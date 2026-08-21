import { describe, expect, it } from 'vitest';

import { DE, EN } from './dictionary';

/**
 * The dictionary entries that are functions rather than strings.
 *
 * <p>Most of the dictionary is data, and data does not need a test. These six do: they take a count
 * or a threshold and choose a sentence, which makes them the only place in the translations where a
 * wrong answer is possible rather than merely a wrong word.
 *
 * <p><b>Both languages, and the English half is why this file exists.</b> Every component spec pins
 * German, so the English branches of these functions had never run — the survey found six uncovered
 * functions here, all of them English. A pluralisation bug in them would have reached a reader
 * before it reached a test.
 *
 * <p>OUT OF SCOPE, DELIBERATELY: that the two languages hold the same keys. The dictionary is typed
 * so that a key present in one and missing from the other does not compile, and re-asserting a
 * compile-time invariant at runtime would only add a test that cannot fail while the build is green.
 */
describe('dictionary — the entries that compute a sentence', () => {
  const de = DE;
  const en = EN;

  describe('singular and plural', () => {
    it('counts unapproved sources without saying "1 sources"', () => {
      // The line a technician reads above an answer. It appears exactly when some of the evidence
      // is unreviewed, which is the moment the sentence has to be readable rather than templated.
      expect(en.modeA.unapprovedSources(1)).toContain('One of the sources');
      expect(en.modeA.unapprovedSources(3)).toContain('3 of the sources');
      expect(de.modeA.unapprovedSources(1)).toContain('Eine der Quellen');
      expect(de.modeA.unapprovedSources(3)).toContain('3 der Quellen');
    });

    it('counts the history entries it is not showing', () => {
      // The viewer shows the last few events and says how many it left out. "1 older entries" in a
      // ledger is the kind of detail that makes a reader doubt the rest of it.
      expect(en.viewer.historyMore(1)).toContain('One older entry');
      expect(en.viewer.historyMore(4)).toContain('4 older entries');
      expect(de.viewer.historyMore(1)).toContain('Ein älterer Eintrag');
      expect(de.viewer.historyMore(4)).toContain('4 ältere Einträge');
    });

    it('counts the similar protocols it found on the machine', () => {
      expect(en.duplicates.intro(1)).toContain('One protocol');
      expect(en.duplicates.intro(2)).toContain('2 protocols');
      expect(de.duplicates.intro(1)).toContain('Ein Protokoll');
      expect(de.duplicates.intro(2)).toContain('2 Protokolle');
    });
  });

  describe('the numbers the sentence exists to carry', () => {
    it('names the archive cap it is warning about', () => {
      // The cap comes from the backend so the hint cannot drift from the number the purge enforces.
      // That only holds if the sentence actually prints the value it was handed.
      expect(en.moderation.archiveHint(50)).toContain('50');
      expect(de.moderation.archiveHint(50)).toContain('50');
    });

    it('names the similarity threshold as the percentage it is shown as', () => {
      expect(en.duplicates.method(92)).toContain('92');
      expect(de.duplicates.method(92)).toContain('92');
    });

    it('counts the candidates above the threshold that the list does not show', () => {
      // The tail is counted rather than dropped: an approver told about three similar protocols
      // when there are nine has been told something false about the corpus.
      expect(en.duplicates.more(6)).toContain('6');
      expect(de.duplicates.more(6)).toContain('6');
    });

    it('states the page, the page count and the total in one line', () => {
      const english = en.pager.pageOf(2, 5, 23);
      expect(english).toContain('2');
      expect(english).toContain('5');
      expect(english).toContain('23');

      const german = de.pager.pageOf(2, 5, 23);
      expect(german).toContain('2');
      expect(german).toContain('5');
      expect(german).toContain('23');
    });
  });
});
