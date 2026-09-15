const GEMINI_API_BASE = "https://generativelanguage.googleapis.com/v1beta/models";

export const PRIMARY_MODEL = "gemini-3.6-flash";
export const FALLBACK_MODEL = "gemini-3.5-flash";
export const MODEL_TIMEOUT_MILLIS = 6_000;

const TRANSIENT_HTTP_STATUSES = new Set([408, 429, 500, 502, 503, 504, 524]);
const SYSTEM_INSTRUCTION = [
  "You provide an optional concise explanation of an already-computed Environmental Health Monitor assessment.",
  "The supplied deterministic EHM condition and predefined guidance are authoritative; never replace or contradict them.",
  "Respond in no more than 90 words.",
  "Write one short sentence explaining the main environmental concern, followed by exactly 3 short numbered recommendations.",
  "Use plain text only: no Markdown, headings, asterisks, tables, or bullet symbols.",
  "Do not repeat all sensor values unnecessarily. Do not diagnose illness or present emergency advice.",
  "Treat eCO2 as an equivalent estimate, not direct CO2, and estimated noise as non-calibrated.",
  "Return only the requested sentence and numbered recommendations."
].join(" ");

export function createWorker({
  fetchImpl = globalThis.fetch,
  logger = console,
  timeoutMillis = MODEL_TIMEOUT_MILLIS
} = {}) {
  return {
    async fetch(request, env) {
      const url = new URL(request.url);
      if (request.method === "GET" && url.pathname === "/") {
        return jsonResponse({ status: "ok", service: "EHM AI" }, 200);
      }
      if (request.method !== "POST") {
        return jsonResponse({ error: "Method not allowed" }, 405);
      }

      if (!env?.GEMINI_API_KEY) {
        safeLog(logger, "error", "Gemini configuration unavailable: missing Worker secret binding");
        return jsonResponse({ error: "AI explanation service unavailable" }, 500);
      }

      let requestBody;
      try {
        requestBody = await request.json();
      } catch {
        return jsonResponse({ error: "Invalid request" }, 400);
      }
      if (!requestBody || typeof requestBody !== "object" || Array.isArray(requestBody)) {
        return jsonResponse({ error: "Invalid request" }, 400);
      }

      const prompt = buildPrompt(requestBody);
      safeLog(logger, "info", `Gemini primary started: model=${PRIMARY_MODEL}`);
      const primary = await attemptModel({
        model: PRIMARY_MODEL,
        prompt,
        apiKey: env.GEMINI_API_KEY,
        fetchImpl,
        timeoutMillis
      });
      if (primary.ok) {
        safeLog(logger, "info", `Gemini primary succeeded: model=${PRIMARY_MODEL} status=${primary.status}`);
        return jsonResponse({ explanation: primary.explanation }, 200);
      }

      logAttemptFailure(logger, "primary", PRIMARY_MODEL, primary);
      if (!primary.transient) {
        return jsonResponse({ error: "AI explanation service unavailable" }, 502);
      }

      safeLog(logger, "info", `Gemini fallback started: model=${FALLBACK_MODEL}`);
      const fallback = await attemptModel({
        model: FALLBACK_MODEL,
        prompt,
        apiKey: env.GEMINI_API_KEY,
        fetchImpl,
        timeoutMillis
      });
      if (fallback.ok) {
        safeLog(logger, "info", `Gemini fallback succeeded: model=${FALLBACK_MODEL} status=${fallback.status}`);
        return jsonResponse({ explanation: fallback.explanation }, 200);
      }

      logAttemptFailure(logger, "fallback", FALLBACK_MODEL, fallback);
      safeLog(
        logger,
        "warn",
        `Gemini models exhausted: primary=${primary.status} fallback=${fallback.status}`
      );
      if (fallback.transient) {
        return jsonResponse({ error: "AI explanation temporarily unavailable" }, 503);
      }
      return jsonResponse({ error: "AI explanation service unavailable" }, 502);
    }
  };
}

async function attemptModel({ model, prompt, apiKey, fetchImpl, timeoutMillis }) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), timeoutMillis);
  try {
    const response = await fetchImpl(`${GEMINI_API_BASE}/${model}:generateContent`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "x-goog-api-key": apiKey
      },
      body: JSON.stringify({
        systemInstruction: {
          parts: [{ text: SYSTEM_INSTRUCTION }]
        },
        contents: [
          {
            role: "user",
            parts: [{ text: prompt }]
          }
        ],
        generationConfig: {
          maxOutputTokens: 160,
          thinkingConfig: {
            thinkingLevel: "minimal"
          }
        }
      }),
      signal: controller.signal
    });

    // Always read text first. Error pages may be JSON, HTML, or plain text.
    const responseText = await response.text();
    if (response.ok) {
      const explanation = parseExplanation(responseText);
      if (explanation) return { ok: true, explanation, status: response.status };
      return {
        ok: false,
        transient: true,
        status: "invalid_response",
        safeMessage: "Successful upstream response contained no usable text"
      };
    }

    return {
      ok: false,
      transient: TRANSIENT_HTTP_STATUSES.has(response.status),
      status: response.status,
      safeMessage: safeUpstreamMessage(responseText)
    };
  } catch (error) {
    const timedOut = error?.name === "AbortError";
    return {
      ok: false,
      transient: true,
      status: timedOut ? "timeout" : "network_error",
      safeMessage: timedOut ? "Gemini request timed out" : sanitizeMessage(error?.message)
    };
  } finally {
    clearTimeout(timeout);
  }
}

function parseExplanation(responseText) {
  try {
    const payload = JSON.parse(responseText);
    const parts = payload?.candidates?.[0]?.content?.parts;
    if (!Array.isArray(parts)) return null;
    const text = parts
      .filter((part) => part?.thought !== true && typeof part?.text === "string")
      .map((part) => part.text.trim())
      .filter(Boolean)
      .join("\n")
      .trim();
    return text || null;
  } catch {
    return null;
  }
}

function safeUpstreamMessage(responseText) {
  if (!responseText) return "No safe upstream message provided";
  try {
    const payload = JSON.parse(responseText);
    const candidate = payload?.error?.message ?? payload?.message;
    if (typeof candidate === "string" && candidate.trim()) {
      return sanitizeMessage(candidate);
    }
  } catch {
    // Plain-text and HTML upstream bodies are handled below.
  }
  return sanitizeMessage(responseText);
}

function sanitizeMessage(value) {
  if (typeof value !== "string" || !value.trim()) return "No safe upstream message provided";
  return value
    .replace(/<[^>]*>/g, " ")
    .replace(/https?:\/\/[^\s?]+\?[^\s]+/gi, "[URL REDACTED]")
    .replace(/AIza[0-9A-Za-z_-]{20,}/g, "[REDACTED]")
    .replace(/(api[ _-]?key|authorization|bearer|token|password|credential)(\s*[:=]\s*)([^\s,;]+)/gi, "$1$2[REDACTED]")
    .replace(/\s+/g, " ")
    .trim()
    .slice(0, 200);
}

function logAttemptFailure(logger, stage, model, attempt) {
  safeLog(
    logger,
    "warn",
    `Gemini ${stage} failed: model=${model} status=${attempt.status} message=${attempt.safeMessage}`
  );
}

function safeLog(logger, level, message) {
  try {
    const logFunction = logger?.[level] ?? logger?.log;
    if (typeof logFunction === "function") logFunction.call(logger, message);
  } catch {
    // Logging must never affect the response path.
  }
}

function buildPrompt(input) {
  const context = {
    temperature: finiteNumberOrNull(input.temperature),
    humidity: finiteNumberOrNull(input.humidity),
    heatIndex: finiteNumberOrNull(input.heatIndex),
    tvoc: finiteNumberOrNull(input.tvoc),
    eco2: finiteNumberOrNull(input.eco2),
    noiseLevel: finiteNumberOrNull(input.noiseLevel),
    thermalCondition: safeLabel(input.thermalCondition),
    airCondition: safeLabel(input.airCondition),
    noiseCondition: safeLabel(input.noiseCondition),
    overallCondition: safeLabel(input.overallCondition),
    primaryConcerns: Array.isArray(input.primaryConcerns)
      ? input.primaryConcerns.slice(0, 3).map(safeLabel)
      : []
  };
  return `Briefly explain this deterministic EHM assessment and its practical predefined context: ${JSON.stringify(context)}`;
}

function finiteNumberOrNull(value) {
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

function safeLabel(value) {
  return typeof value === "string" && value.trim() ? value.trim().slice(0, 80) : "UNAVAILABLE";
}

function jsonResponse(body, status) {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": "no-store"
    }
  });
}

export default createWorker();
