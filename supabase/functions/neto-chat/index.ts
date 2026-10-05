// NETO Daily – Chat Edge Function (Gemini-powered)
// Accepts guest (apikey) and authenticated users.
// Calls Gemini generateContent API (v1beta) — stable REST endpoint.

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers":
    "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
  "Content-Type": "application/json",
};

const GEMINI_GENERATE_URL =
  "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent";

const SYSTEM_PROMPT = `You are NETO, a warm, intelligent, voice-first personal AI agent created by Macdonald Barasa.
You help users with day-to-day tasks: answering questions, planning their day, giving advice, searching for information, and controlling their phone through voice commands.
Keep responses concise and conversational. Avoid unnecessary formatting when the answer will be read aloud.
If asked to perform a phone action (call, send SMS, open app, set alarm, flashlight) – acknowledge it warmly and confirm you've initiated it.`;

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") {
    return new Response(null, { status: 204, headers: corsHeaders });
  }

  if (req.method !== "POST") {
    return Response.json({ ok: false, error: "method_not_allowed" }, { status: 405, headers: corsHeaders });
  }

  const geminiKey = Deno.env.get("GEMINI_API_KEY");
  if (!geminiKey) {
    return Response.json({ ok: false, error: "gemini_not_configured" }, { status: 503, headers: corsHeaders });
  }

  // Allow both authenticated users and guests (apikey header only)
  const authHeader = req.headers.get("authorization") || "";
  const apiKey = req.headers.get("apikey") || "";
  if (!authHeader && !apiKey) {
    return Response.json({ ok: false, error: "unauthenticated" }, { status: 401, headers: corsHeaders });
  }

  let body: any;
  try {
    body = await req.json();
  } catch {
    return Response.json({ ok: false, error: "invalid_json" }, { status: 400, headers: corsHeaders });
  }

  const message = typeof body?.message === "string" ? body.message.trim() : "";
  if (!message || message.length > 20000) {
    return Response.json({ ok: false, error: "invalid_message" }, { status: 400, headers: corsHeaders });
  }

  // Build conversation history if provided
  const history: any[] = Array.isArray(body?.history) ? body.history : [];
  const contents: any[] = [];

  // Add conversation history (alternate user/model turns)
  for (const turn of history) {
    if (turn.role === "user" || turn.role === "model") {
      contents.push({
        role: turn.role,
        parts: [{ text: String(turn.text || "") }],
      });
    }
  }

  // Add current user message
  contents.push({ role: "user", parts: [{ text: message }] });

  const geminiResponse = await fetch(`${GEMINI_GENERATE_URL}?key=${geminiKey}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      contents,
      systemInstruction: {
        parts: [{ text: SYSTEM_PROMPT }],
      },
      generationConfig: {
        temperature: 0.8,
        maxOutputTokens: 800,
      },
    }),
  });

  const geminiData = await geminiResponse.json().catch(() => null);

  if (!geminiResponse.ok) {
    console.error("Gemini API error:", geminiResponse.status, JSON.stringify(geminiData));
    return Response.json(
      {
        ok: false,
        error: "gemini_request_failed",
        details: geminiData?.error?.message ?? "Gemini rejected the request.",
      },
      { status: 502, headers: corsHeaders },
    );
  }

  // Extract text from Gemini generateContent response format
  const replyText: string =
    geminiData?.candidates?.[0]?.content?.parts?.[0]?.text?.trim() ?? "";

  if (!replyText) {
    return Response.json({ ok: false, error: "empty_gemini_response" }, { status: 502, headers: corsHeaders });
  }

  return Response.json(
    {
      ok: true,
      message: replyText,
      model: "gemini-2.0-flash",
    },
    { headers: corsHeaders },
  );
});
