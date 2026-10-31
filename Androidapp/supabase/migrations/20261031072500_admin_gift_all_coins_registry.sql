-- Register the bulk gift tool so admin_command_propose accepts gift_all_coins plans.
-- The deterministic gift dialog (deployed 2026-10-22/30) proposes gift_all_coins steps,
-- but the registry seed never included the tool, so every proposal failed closed.
INSERT INTO public.ai_tool_registry (tool_name, agent, kind, description, limits) VALUES
    ('gift_all_coins', 'admin_command', 'RISKY', 'Gift coins to every member at once, idempotent by run key', '{}')
ON CONFLICT (tool_name) DO NOTHING;
