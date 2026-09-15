import test from "node:test";
import assert from "node:assert/strict";
import {
  createWorker,
  FALLBACK_MODEL,
  PRIMARY_MODEL
} from "./ehm-gemini-worker.mjs";

const ENV = { GEMINI_API_KEY: "test" };
const REQUEST_BODY = {
  temperature: 30,
  humidity: 70,
  heatIndex: 35,
  tvoc: 300,
  eco2: 650,
  noiseLevel: 55,
  thermalCondition: "MODERATE",
  airCondition: "GOOD",
  noiseCondition: "GOOD",
  overallCondition: "MODERATE",
  primaryConcerns: ["THERMAL"]
};

test("GET root returns the public health response", async () => {
  const worker = createWorker({
    fetchImpl: async () => {
      throw new Error("Health check must not call Gemini");
    },
    logger: { info() {}, warn() {}, error() {} }
  });
  const response = await worker.fetch(new Request("https://worker.test/"), {});

  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { status: "ok", service: "EHM AI" });
});

test("A primary 200 is returned without fallback", async () => {
  const calls = [];
  const response = await invoke(async (url) => {
    calls.push(url);
    return geminiSuccess("Primary explanation");
  });

  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { explanation: "Primary explanation" });
  assert.equal(calls.length, 1);
  assert.match(calls[0], new RegExp(PRIMARY_MODEL));
});

test("B primary 503 falls back sequentially to a successful model", async () => {
  const calls = [];
  const response = await invoke(async (url) => {
    calls.push(url);
    return url.includes(PRIMARY_MODEL)
      ? new Response(JSON.stringify({ error: { message: "high demand" } }), { status: 503 })
      : geminiSuccess("Fallback explanation");
  });

  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { explanation: "Fallback explanation" });
  assert.equal(calls.length, 2);
  assert.match(calls[0], new RegExp(PRIMARY_MODEL));
  assert.match(calls[1], new RegExp(FALLBACK_MODEL));
});

test("C plain-text 524 cannot crash parsing and uses fallback", async () => {
  const response = await invoke(async (url) =>
    url.includes(PRIMARY_MODEL)
      ? new Response("error code: 524", { status: 524 })
      : geminiSuccess("Recovered after 524")
  );

  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { explanation: "Recovered after 524" });
});

test("D two transient failures return clean HTTP 503 JSON", async () => {
  const response = await invoke(async () =>
    new Response("upstream unavailable", { status: 503 })
  );

  assert.equal(response.status, 503);
  assert.deepEqual(await response.json(), { error: "AI explanation temporarily unavailable" });
});

test("E primary 403 does not spend a fallback request", async () => {
  let calls = 0;
  const response = await invoke(async () => {
    calls += 1;
    return new Response(JSON.stringify({ error: { message: "forbidden" } }), { status: 403 });
  });

  assert.equal(response.status, 502);
  assert.deepEqual(await response.json(), { error: "AI explanation service unavailable" });
  assert.equal(calls, 1);
});

test("F malformed primary 200 response uses fallback", async () => {
  const response = await invoke(async (url) =>
    url.includes(PRIMARY_MODEL)
      ? new Response("not-json", { status: 200 })
      : geminiSuccess("Recovered from malformed response")
  );

  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { explanation: "Recovered from malformed response" });
});

test("primary fetch failure is transient and uses fallback", async () => {
  const response = await invoke(async (url) => {
    if (url.includes(PRIMARY_MODEL)) throw new TypeError("fetch failed");
    return geminiSuccess("Recovered from network failure");
  });

  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { explanation: "Recovered from network failure" });
});

test("per-model AbortError timeout can fall back", async () => {
  const worker = createWorker({
    fetchImpl: (url, options) => {
      if (url.includes(FALLBACK_MODEL)) return Promise.resolve(geminiSuccess("Recovered after timeout"));
      return new Promise((resolve, reject) => {
        options.signal.addEventListener("abort", () => {
          reject(new DOMException("timed out", "AbortError"));
        });
      });
    },
    logger: { info() {}, warn() {}, error() {} },
    timeoutMillis: 5
  });
  const response = await worker.fetch(request(), ENV);

  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { explanation: "Recovered after timeout" });
});

function invoke(fetchImpl) {
  const worker = createWorker({
    fetchImpl,
    logger: { info() {}, warn() {}, error() {} },
    timeoutMillis: 100
  });
  return worker.fetch(request(), ENV);
}

function request() {
  return new Request("https://worker.test/", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(REQUEST_BODY)
  });
}

function geminiSuccess(text) {
  return new Response(
    JSON.stringify({ candidates: [{ content: { parts: [{ text }] } }] }),
    { status: 200, headers: { "Content-Type": "application/json" } }
  );
}
