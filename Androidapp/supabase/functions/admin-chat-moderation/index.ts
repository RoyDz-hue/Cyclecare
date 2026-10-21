import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { corsHeaders } from "../_shared/cors.ts";
import { authenticatedUserId, jsonResponse, supabaseSecretKey } from "../_shared/payments.ts";

// Admin moderation for Guided Chats. Every action requires a valid user session
// AND a row in cyclecare_admin_roles; the service key never substitutes for that.
serve(async (req) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }
  if (req.method !== "POST") {
    return jsonResponse({ error: "Method not allowed" }, 405);
  }

  try {
    const userId = await authenticatedUserId(req);
    if (!userId) return jsonResponse({ error: "Authentication required" }, 401);

    const supabaseUrl = Deno.env.get("SUPABASE_URL")?.replace(/\/$/, "");
    const serviceRoleKey = supabaseSecretKey() ?? "";
    if (!supabaseUrl || !serviceRoleKey) return jsonResponse({ error: "Backend is not configured" }, 503);

    const headers = {
      "apikey": serviceRoleKey,
      "Authorization": `Bearer ${serviceRoleKey}`,
      "Content-Type": "application/json",
      "Prefer": "return=representation",
    };

    const roleRes = await fetch(
      `${supabaseUrl}/rest/v1/cyclecare_admin_roles?select=user_id&user_id=eq.${encodeURIComponent(userId)}`,
      { headers },
    );
    if (!roleRes.ok) return jsonResponse({ error: "Administrator access could not be verified" }, 503);
    const roles = await roleRes.json();
    if (!Array.isArray(roles) || roles.length === 0) {
      return jsonResponse({ error: "Administrator access required" }, 403);
    }

    const { action, topicId, messageId, isActive, isFlagged } = await req.json();

    if (action === "list_topics") {
      const res = await fetch(`${supabaseUrl}/rest/v1/chat_topics?select=*&order=created_at.desc`, { headers });
      const data = await res.json();
      return jsonResponse({ success: true, topics: data });
    }

    if (action === "toggle_topic" && topicId) {
      const res = await fetch(`${supabaseUrl}/rest/v1/chat_topics?id=eq.${topicId}`, {
        method: "PATCH",
        headers,
        body: JSON.stringify({ is_active: isActive }),
      });
      const data = await res.json();
      return jsonResponse({ success: true, topic: data });
    }

    if (action === "flag_message" && messageId) {
      const res = await fetch(`${supabaseUrl}/rest/v1/chat_messages?id=eq.${messageId}`, {
        method: "PATCH",
        headers,
        body: JSON.stringify({ is_flagged: isFlagged }),
      });
      const data = await res.json();
      return jsonResponse({ success: true, message: data });
    }

    if (action === "delete_message" && messageId) {
      const res = await fetch(`${supabaseUrl}/rest/v1/chat_messages?id=eq.${messageId}`, {
        method: "DELETE",
        headers,
      });
      return jsonResponse({ success: true, deleted: res.ok });
    }

    return jsonResponse({ error: "Invalid action" }, 400);
  } catch (err) {
    return jsonResponse({ error: (err as Error).message }, 500);
  }
});
