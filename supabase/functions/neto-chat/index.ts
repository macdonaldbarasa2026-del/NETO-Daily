import { withSupabase } from "npm:@supabase/server";

const GEMINI_URL = "https://generativelanguage.googleapis.com/v1/interactions";
const DEFAULT_MODEL = "gemini-3.8-flash";

function corsHeaders() {
  return {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Headers":
      "authorization, x-client-info, apikey, content-type",
    "Access-Control-Allow-Methods": "POST, OPTIONS",
    "Content-Type": "application/json",
  };
}

function json(data: unknown, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: corsHeaders(),
  });
}

function extractText(data: any): string {
  if (typeof data?.output_text === "string" && data.output_text.trim()) {
    return data.output_text.trim();
  }

  const chunks: string[] = [];

  for (const step of Array.isArray(data?.steps) ? data.steps : []) {
    if (step?.type !== "model_output") continue;

    for (const item of Array.isArray(step?.content) ? step.content : []) {
      if (item?.type === "text" && typeof item?.text === "string") {
        chunks.push(item.text);
      }
    }
  }

  return chunks.join("\n").trim();
}

export default {
  fetch: withSupabase({ auth: "user" }, async (req, ctx) => {
    if (req.method === "OPTIONS") {
      return new Response(null, {
        status: 204,
        headers: corsHeaders(),
      });
    }

    if (req.method !== "POST") {
      return json(
        { ok: false, error: "method_not_allowed" },
        405,
      );
    }

    const geminiKey = Deno.env.get("GEMINI_API_KEY");

    if (!geminiKey) {
      return json(
        { ok: false, error: "gemini_not_configured" },
        503,
      );
    }

    const userId = ctx.userClaims?.sub;

    if (!userId) {
      return json(
        { ok: false, error: "unauthenticated" },
        401,
      );
    }

    let body: any;

    try {
      body = await req.json();
    } catch {
      return json(
        { ok: false, error: "invalid_json" },
        400,
      );
    }

    const message =
      typeof body?.message === "string"
        ? body.message.trim()
        : "";

    if (!message || message.length > 20000) {
      return json(
        { ok: false, error: "invalid_message" },
        400,
      );
    }

    const requestedConversationId =
      typeof body?.conversationId === "string"
        ? body.conversationId
        : null;

    const previousInteractionId =
      typeof body?.previousInteractionId === "string"
        ? body.previousInteractionId
        : null;

    const model =
      typeof body?.model === "string" && body.model.trim()
        ? body.model.trim()
        : Deno.env.get("GEMINI_MODEL") || DEFAULT_MODEL;

    let conversationId = requestedConversationId;

    if (conversationId) {
      const { data: conversation, error } = await ctx.supabase
        .from("conversations")
        .select("id")
        .eq("id", conversationId)
        .eq("user_id", userId)
        .maybeSingle();

      if (error) {
        return json(
          { ok: false, error: "conversation_lookup_failed" },
          500,
        );
      }

      if (!conversation) {
        return json(
          { ok: false, error: "conversation_not_found" },
          404,
        );
      }
    } else {
      const { data: conversation, error } = await ctx.supabase
        .from("conversations")
        .insert({
          user_id: userId,
          title: message.slice(0, 80),
        })
        .select("id")
        .single();

      if (error || !conversation) {
        return json(
          { ok: false, error: "conversation_create_failed" },
          500,
        );
      }

      conversationId = conversation.id;
    }

    const geminiBody: Record<string, unknown> = {
      model,
      input: message,
    };

    if (previousInteractionId) {
      geminiBody.previous_interaction_id =
        previousInteractionId;
    }

    const geminiResponse = await fetch(GEMINI_URL, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "x-goog-api-key": geminiKey,
      },
      body: JSON.stringify(geminiBody),
    });

    const geminiData =
      await geminiResponse.json().catch(() => null);

    if (!geminiResponse.ok) {
      return json(
        {
          ok: false,
          error: "gemini_request_failed",
          status: geminiResponse.status,
          details:
            typeof geminiData?.error?.message === "string"
              ? geminiData.error.message
              : "Gemini rejected the request.",
        },
        502,
      );
    }

    const assistantText = extractText(geminiData);

    if (!assistantText) {
      return json(
        { ok: false, error: "empty_gemini_response" },
        502,
      );
    }

    const { error: messageError } = await ctx.supabase
      .from("messages")
      .insert([
        {
          conversation_id: conversationId,
          user_id: userId,
          role: "user",
          content: message,
          metadata: {},
        },
        {
          conversation_id: conversationId,
          user_id: userId,
          role: "assistant",
          content: assistantText,
          metadata: {
            provider: "gemini",
            model,
            interaction_id: geminiData?.id ?? null,
          },
        },
      ]);

    if (messageError) {
      return json(
        { ok: false, error: "message_save_failed" },
        500,
      );
    }

    await ctx.supabase
      .from("conversations")
      .update({
        updated_at: new Date().toISOString(),
      })
      .eq("id", conversationId)
      .eq("user_id", userId);

    await ctx.supabase
      .from("usage_events")
      .insert({
        user_id: userId,
        event_type: "chat",
        model,
        input_tokens:
          typeof geminiData?.usage?.total_input_tokens ===
          "number"
            ? geminiData.usage.total_input_tokens
            : null,
        output_tokens:
          typeof geminiData?.usage?.total_output_tokens ===
          "number"
            ? geminiData.usage.total_output_tokens
            : null,
        metadata: {
          provider: "gemini",
          interaction_id: geminiData?.id ?? null,
        },
      });

    return json({
      ok: true,
      conversationId,
      interactionId: geminiData?.id ?? null,
      model,
      message: assistantText,
      usage: geminiData?.usage ?? null,
    });
  }),
};
