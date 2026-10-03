import { withSupabase } from "npm:@supabase/server@1";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers":
    "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

export default {
  fetch: withSupabase({ auth: "user" }, async (req, ctx) => {
    if (req.method === "OPTIONS") {
      return new Response("ok", {
        headers: corsHeaders,
      });
    }

    if (req.method !== "POST") {
      return Response.json(
        { error: "Method not allowed" },
        {
          status: 405,
          headers: corsHeaders,
        },
      );
    }

    const userId = ctx.userClaims?.sub;

    if (!userId) {
      return Response.json(
        { error: "Authentication required" },
        {
          status: 401,
          headers: corsHeaders,
        },
      );
    }

    const geminiKey = Deno.env.get("GEMINI_API_KEY");

    if (!geminiKey) {
      console.error("GEMINI_API_KEY is not configured");

      return Response.json(
        { error: "Live voice is not configured." },
        {
          status: 500,
          headers: corsHeaders,
        },
      );
    }

    const now = Date.now();

    const expireTime = new Date(
      now + 30 * 60 * 1000,
    ).toISOString();

    const newSessionExpireTime = new Date(
      now + 60 * 1000,
    ).toISOString();

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
            model: "models/gemini-3.8-live",
            config: {
              sessionResumption: {},
              responseModalities: ["AUDIO"],
            },
          },
        }),
      },
    );

    const data = await response.json();

    if (!response.ok) {
      console.error(
        "Gemini token provisioning failed:",
        response.status,
        JSON.stringify(data),
      );

      return Response.json(
        {
          error: "Could not start NETO Live.",
        },
        {
          status: 502,
          headers: corsHeaders,
        },
      );
    }

    return Response.json(
      {
        ok: true,
        token: data.name,
        model: "gemini-3.8-live",
        expiresAt: expireTime,
        userId,
      },
      {
        headers: {
          ...corsHeaders,
          "Cache-Control": "no-store",
        },
      },
    );
  }),
};
