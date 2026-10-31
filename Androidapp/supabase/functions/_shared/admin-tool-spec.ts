// Single declarative capability spec per ACI tool. The DATA lives in
// Androidapp/supabase/functions/admin-tool-spec-data.json, mirrored to
// AdminPWA/src/lib/adminToolSpec.json, so the edge function and the PWA
// review card share one source; this module types and exposes it.
import SPEC_DATA from "../admin-tool-spec-data.json" with { type: "json" };

export type ToolKind = "READ" | "WRITE" | "RISKY";

export interface ToolSpec {
  name: string;
  kind: ToolKind;
  /** Exact planner-catalog line (keep wording stable; the planner reads it). */
  catalogLine: string;
  /** Args the admin must supply; absence is the ONLY reason to clarify. */
  essentialArgs: string[];
  /** Bound args the review card must show before Confirm. */
  reviewFields: { key: string; label: string }[];
  /** A successful WRITE/RISKY step must have a read-back verifier. */
  requiresVerifier: boolean;
}

export const TOOL_SPECS: ToolSpec[] = SPEC_DATA.tools as ToolSpec[];

export function buildToolCatalog(specs: ToolSpec[] = TOOL_SPECS): string {
  return `${SPEC_DATA.header}\n${specs.map((s) => s.catalogLine).join("\n")}`;
}

export function specFor(name: string, specs: ToolSpec[] = TOOL_SPECS): ToolSpec | undefined {
  return specs.find((s) => s.name === name);
}
