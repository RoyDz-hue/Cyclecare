import { corsHeaders } from "../_shared/cors.ts";

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }

  try {
    const supabaseUrl = Deno.env.get("SUPABASE_URL") ?? "";
    const serviceRoleKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";

    const authHeader = req.headers.get("Authorization");
    if (!authHeader) {
      return new Response(JSON.stringify({ error: "Missing authorization header" }), {
        status: 401,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    const { action, topicId, messageId, isActive, isFlagged } = await req.json();

    const headers = {
      "apikey": serviceRoleKey,
      "Authorization": `Bearer ${serviceRoleKey}`,
      "Content-Type": "application/json",
      "Prefer": "return=representation",
    };

    if (action === "list_topics") {
      const res = await fetch(`${supabaseUrl}/rest/v1/chat_topics?select=*&order=created_at.desc`, { headers });
      const data = await res.json();
      return new Response(JSON.stringify({ success: true, topics: data }), {
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    if (action === "toggle_topic" && topicId) {
      const res = await fetch(`${supabaseUrl}/rest/v1/chat_topics?id=eq.${topicId}`, {
        method: "PATCH",
        headers,
        body: JSON.stringify({ is_active: isActive }),
      });
      const data = await res.json();
      return new Response(JSON.stringify({ success: true, topic: data }), {
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    if (action === "flag_message" && messageId) {
      const res = await fetch(`${supabaseUrl}/rest/v1/chat_messages?id=eq.${messageId}`, {
        method: "PATCH",
        headers,
        body: JSON.stringify({ is_flagged: isFlagged }),
      });
      const data = await res.json();
      return new Response(JSON.stringify({ success: true, message: data }), {
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    if (action === "delete_message" && messageId) {
      const res = await fetch(`${supabaseUrl}/rest/v1/chat_messages?id=eq.${messageId}`, {
        method: "DELETE",
        headers,
      });
      return new Response(JSON.stringify({ success: true, deleted: true }), {
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    return new Response(JSON.stringify({ error: "Invalid action" }), {
      status: 400,
      headers: { ...corsHeaders, "Content-Type": "application/json" },
    });
  } catch (err) {
    return new Response(JSON.stringify({ error: (err as Error).message }), {
      status: 500,
      headers: { ...corsHeaders, "Content-Type": "application/json" },
    });
  }
});
