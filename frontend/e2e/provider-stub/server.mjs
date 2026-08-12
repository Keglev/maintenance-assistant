/**
 * A stand-in for the LLM provider, and ONLY for the provider.
 *
 * WHY THIS EXISTS. `reindex.e2e.ts` asserts the one behaviour Carlos hand-drills before every
 * release: an edited protocol changes the answer that cites it. It is also the only test in the
 * suite that cannot run without a language model, so it was skipped by default — and a test that
 * never runs is worth nothing. It had never executed once before PR #51, and when it finally did it
 * revealed four defects in itself.
 *
 * WHAT IS FAKED AND WHAT IS NOT — this is the whole design, and the line is drawn deliberately.
 * Faked: the two paid HTTP calls to IONOS, and nothing else. Real: the upload, the ingestion
 * pipeline, the chunker, the pgvector write, the status transition, retrieval, the similarity
 * threshold, the citation validation, the re-index on edit, the delete-then-write of chunks, and
 * every byte of the answer the user sees. The backend does not know it is talking to a stub; it is
 * pointed at a different `LLM_BASE_URL`, which is a configuration value the application already
 * supports because ADR-002 requires the provider to be swappable. NO PRODUCTION CODE CHANGES.
 *
 * THE CHAT STUB IS A PARROT, AND THAT IS WHAT MAKES THE TEST MEANINGFUL. It reads the retrieved
 * sources out of the prompt the backend built and answers by quoting them. So the answer changes
 * when — and only when — the retrieved text changes, which is precisely the property the re-index
 * test is about. A stub that returned a fixed string would pass whether or not re-indexing worked.
 *
 * Zero dependencies, so CI needs no install step for it.
 *
 *   node server.mjs --port 8099
 */

import { createServer } from 'node:http';

const PORT = Number(process.argv.includes('--port')
  ? process.argv[process.argv.indexOf('--port') + 1]
  : (process.env.PORT ?? 8099));

/** bge-m3's width. The backend asserts it on every response, so a wrong value must fail loudly. */
const DIMENSIONS = 1024;

/**
 * A deterministic embedding: hashed character trigrams, L2-normalised.
 *
 * <p>Character trigrams rather than words because German compounds would otherwise defeat it —
 * "Verpackungslinie" in a question and "Linie" in a protocol share no word token and most of a
 * trigram set. The result is not semantic in any real sense; it is *lexically* faithful, which is
 * all retrieval needs here: a question and the protocol it is about score above the 0.55 threshold,
 * and the corrected protocol moves when its text is rewritten.
 *
 * <p>Deterministic, so the same text always embeds to the same vector — which is what makes a
 * re-index observable rather than noisy.
 */
function embed(text) {
  const vector = new Float64Array(DIMENSIONS);
  const normalised = ` ${String(text).toLowerCase().replace(/[^\p{L}\p{N}]+/gu, ' ').trim()} `;

  for (let i = 0; i + 3 <= normalised.length; i++) {
    const gram = normalised.slice(i, i + 3);
    // FNV-1a, for a cheap well-spread bucket.
    let hash = 0x811c9dc5;
    for (let c = 0; c < gram.length; c++) {
      hash ^= gram.charCodeAt(c);
      hash = Math.imul(hash, 0x01000193) >>> 0;
    }
    vector[hash % DIMENSIONS] += 1;
  }

  let magnitude = 0;
  for (const value of vector) magnitude += value * value;
  magnitude = Math.sqrt(magnitude) || 1;
  return Array.from(vector, (value) => value / magnitude);
}

/**
 * Pulls the labelled sources back out of the prompt the backend built.
 *
 * `GroundedPrompt` lays the user message out as `Sources:\n\n[P1] title\ncontent\n\n…\nQuestion: …`,
 * so the labels the answer is allowed to cite are readable from the prompt itself. Citing a label
 * that was not retrieved is rejected by the backend, which is a rule this stub has to obey exactly
 * as a real model does.
 */
function parseSources(userPrompt) {
  const sources = [];
  const body = userPrompt.split(/\nQuestion:/)[0];
  const pattern = /\[(P\d+)\]\s*([^\n]*)\n([\s\S]*?)(?=\n\[P\d+\]|\s*$)/g;
  let match;
  while ((match = pattern.exec(body)) !== null) {
    sources.push({ label: match[1], title: match[2].trim(), content: match[3].trim() });
  }
  return sources;
}

/**
 * The statements in a chunk, as a real answer would quote them.
 *
 * <p>HEADER LINES ARE DROPPED, and that is not tidiness. A stored protocol opens with a machine and
 * title line ("VP-01 · Kettenriss im Transportsystem") and a document-type banner
 * ("WARTUNGSPROTOKOLL"), so a stub that simply took the first two lines quoted the letterhead and
 * never reached "Ursache:" — where the interesting sentence, and the whole point of the re-index
 * test, actually lives. Measured: the answer came back citing the right protocol and saying nothing
 * about it.
 */
function sentences(text, limit) {
  return text
    .split(/\n+|(?<=\.)\s+/)
    .map((line) => line.trim())
    .filter((line) => line.length > 0)
    // A banner (all caps, no sentence punctuation) or a "machine · title" header line.
    .filter((line) => !/^[A-ZÄÖÜ\s]{6,}$/.test(line) && !line.includes(' · '))
    .slice(0, limit);
}

function chatCompletion(request) {
  const messages = request.messages ?? [];
  const userPrompt = messages.find((m) => m.role === 'user')?.content ?? '';
  const schemaName = request.response_format?.json_schema?.name ?? '';
  const sources = parseSources(userPrompt);

  // Mode B — the ungrounded path. The backend asks for a different schema with no source field at
  // all, so the stub must answer in that shape or the parse fails.
  if (sources.length === 0 || /ungrounded|mode_b|general/i.test(schemaName)) {
    return JSON.stringify({
      answer_language: 'de',
      steps: [
        'Sichtpruefung der betroffenen Baugruppe durchfuehren.',
        'Fehlerspeicher der Steuerung auslesen.',
        'Instandhaltung hinzuziehen, wenn der Fehler erneut auftritt.',
      ],
    });
  }

  // Mode A — one claim per retrieved source, each quoting the source it names. EVERY source is
  // quoted rather than only the top one: retrieval ranking is not what this stub should be deciding,
  // and quoting all of them means the answer reflects the whole retrieved set, so a change to any
  // protocol in it is visible.
  const claims = [];
  for (const source of sources) {
    for (const sentence of sentences(source.content, 4)) {
      claims.push({ text: sentence, source: source.label });
    }
  }

  return JSON.stringify({ answer_language: 'de', claims: claims.slice(0, 20) });
}

function readBody(request) {
  return new Promise((resolve, reject) => {
    let raw = '';
    request.on('data', (chunk) => (raw += chunk));
    request.on('end', () => {
      try {
        resolve(raw ? JSON.parse(raw) : {});
      } catch (error) {
        reject(error);
      }
    });
    request.on('error', reject);
  });
}

const server = createServer(async (request, response) => {
  const send = (status, payload) => {
    const body = JSON.stringify(payload);
    response.writeHead(status, { 'content-type': 'application/json' });
    response.end(body);
  };

  if (request.method === 'GET' && request.url === '/health') {
    return send(200, { status: 'UP' });
  }

  if (request.method !== 'POST') {
    return send(405, { error: { message: `unsupported method ${request.method}` } });
  }

  let payload;
  try {
    payload = await readBody(request);
  } catch {
    return send(400, { error: { message: 'malformed JSON' } });
  }

  if (request.url.endsWith('/embeddings')) {
    const inputs = Array.isArray(payload.input) ? payload.input : [payload.input ?? ''];
    return send(200, {
      object: 'list',
      model: payload.model ?? 'BAAI/bge-m3',
      data: inputs.map((text, index) => ({
        object: 'embedding',
        index,
        embedding: embed(text),
      })),
      usage: { prompt_tokens: inputs.length, total_tokens: inputs.length },
    });
  }

  if (request.url.endsWith('/chat/completions')) {
    const content = chatCompletion(payload);
    return send(200, {
      id: 'stub-completion',
      object: 'chat.completion',
      model: payload.model ?? 'stub',
      choices: [{ index: 0, message: { role: 'assistant', content }, finish_reason: 'stop' }],
      usage: { prompt_tokens: 0, completion_tokens: 0, total_tokens: 0 },
    });
  }

  return send(404, { error: { message: `no stub for ${request.url}` } });
});

server.listen(PORT, '127.0.0.1', () => {
  // eslint-disable-next-line no-console
  console.log(`llm provider stub listening on http://127.0.0.1:${PORT}/v1`);
});
