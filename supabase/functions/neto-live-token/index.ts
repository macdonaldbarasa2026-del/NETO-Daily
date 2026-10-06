// NETO Daily – Live Token Edge Function
// Provisions a short-lived Gemini Live ephemeral token for the Android client.
// Allows guest access (apikey header) and authenticated users.

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers":
    "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

// Model names to try in order (newest first).
// We fall back if a model is not available in the region.
const LIVE_MODEL = "models/gemini-3.8-live";

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }

  if (req.method !== "POST") {
    return Response.json(
      { ok: false, error: "Method not allowed" },
      { status: 405, headers: corsHeaders },
    );
  }

  // Allow both authenticated users and guests (apikey header)
  const authHeader = req.headers.get("authorization") || "";
  const apiKey = req.headers.get("apikey") || "";
  if (!authHeader && !apiKey) {
    return Response.json(
      { ok: false, error: "Authentication required" },
      { status: 401, headers: corsHeaders },
    );
  }

  const geminiKey = Deno.env.get("GEMINI_API_KEY");
  if (!geminiKey) {
    console.error("GEMINI_API_KEY secret is not set in Supabase Edge Function secrets.");
    return Response.json(
      { ok: false, error: "Live voice is not configured on the server." },
      { status: 500, headers: corsHeaders },
    );
  }

  const now = Date.now();
  const expireTime = new Date(now + 30 * 60 * 1000).toISOString();   // 30-min token
  const newSessionExpireTime = new Date(now + 2 * 60 * 1000).toISOString(); // 2-min new session

  // Use the current Gemini Live model.
  const model = LIVE_MODEL;
    const response = await fetch(
      "https://generativelanguage.googleapis.com/v1beta/auth_tokens",
      {
        method: "POST",
        headers: {
          "x-goog-api-key": geminiKey,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({
          uses: 1,
          expireTime,
          newSessionExpireTime,
          liveConnectConstraints: {
            model,
            config: {
              sessionResumption: {},
              responseModalities: ["AUDIO"],
              inputAudioTranscription: {},
              outputAudioTranscription: {},
              speechConfig: {
                voiceConfig: {
                  prebuiltVoiceConfig: {
                    voiceName: "Aoede",
                  },
                },
              },
              systemInstruction: {
                parts: [
                  {
                    text: `You are NETO, a warm, concise, voice-first personal AI agent.
Speak naturally and conversationally like a helpful friend.
Help the user ask questions, do tasks, remember things, find information, and control their phone.
When speaking aloud keep responses brief and natural – avoid markdown formatting.
Created by Macdonald Barasa.`,
                  },
                ],
              },
            },
          },
        }),
      },
    );

    const data = await response.json().catch(() => ({}));

  const token = data.name || data.token;

  if (response.ok && token) {
    return Response.json(
      {
        ok: true,
        token,
        model,
        expiresAt: expireTime,
      },
      {
        headers: {
          ...corsHeaders,
          "Cache-Control": "no-store",
        },
      },
    );
  }

  console.error(
    `Gemini Live token provisioning failed: ${response.status} ${JSON.stringify(data)}`
  );

  return Response.json(
    {
      ok: false,
      error:
        data?.error?.message ||
        `Gemini Live token provisioning failed (HTTP ${response.status}).`,
    },
    { status: 502, headers: corsHeaders },
  );
});
