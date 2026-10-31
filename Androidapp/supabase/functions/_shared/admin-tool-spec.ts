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

export interface ArgProblem { tool: string; arg: string; problem: string }

function coinDecimals(n: number): number { const s = String(n); const i = s.indexOf("."); return i < 0 ? 0 : s.length - i - 1; }

// Validates supplied values, enums, ranges and cross-field semantics against
// the spec. Essential-arg presence alone is not enough: a malformed value is
// a clarification, never a proposed plan.
export function validateStep(spec: ToolSpec, args: any): ArgProblem[] {
  const problems: ArgProblem[] = [];
  const a = args ?? {};
  const rules: Record<string, any> = (spec as any).argRules ?? {};
  for (const arg of spec.essentialArgs) {
    const v = a[arg];
    if (v === undefined || v === null || v === "" || (Array.isArray(v) && v.length === 0)) {
      problems.push({ tool: spec.name, arg, problem: `missing ${arg}` });
    }
  }
  for (const [arg, rule] of Object.entries(rules)) {
    const v = a[arg];
    if (v === undefined || v === null) continue;
    if (rule.type === "coins") {
      const n = Number(v);
      if (!Number.isFinite(n)) { problems.push({ tool: spec.name, arg, problem: `${arg} must be a number` }); continue; }
      if (!rule.signed && (n < (rule.min ?? 0) || n > rule.max)) problems.push({ tool: spec.name, arg, problem: `${arg} must be ${rule.min ?? 0}-${rule.max}` });
      if (rule.signed && (n === 0 || Math.abs(n) > rule.maxAbs)) problems.push({ tool: spec.name, arg, problem: `${arg} must be a non-zero amount up to ${rule.maxAbs}` });
      if (coinDecimals(n) > rule.decimals) problems.push({ tool: spec.name, arg, problem: `${arg} allows up to ${rule.decimals} decimal places` });
    } else if (rule.type === "enum") {
      if (!rule.values.map(String).includes(String(v).toUpperCase()) && !rule.values.map(String).includes(String(v))) {
        problems.push({ tool: spec.name, arg, problem: `${arg} must be one of ${rule.values.join("/")}` });
      }
    } else if (rule.type === "targets") {
      if (!Array.isArray(v) || v.length === 0) problems.push({ tool: spec.name, arg, problem: `${arg} must name at least one member` });
      else if (v.length > rule.max) problems.push({ tool: spec.name, arg, problem: `${arg} allows at most ${rule.max}` });
    } else if (rule.type === "int") {
      if (!Number.isInteger(Number(v)) || (rule.min !== undefined && Number(v) < rule.min)) problems.push({ tool: spec.name, arg, problem: `${arg} must be a whole number${rule.min !== undefined ? ` >= ${rule.min}` : ""}` });
    } else if (rule.type === "bool") {
      if (typeof v !== "boolean") problems.push({ tool: spec.name, arg, problem: `${arg} must be true or false` });
    } else if (rule.type === "packages") {
      if (!Array.isArray(v) || v.length === 0) problems.push({ tool: spec.name, arg, problem: "packages must be a non-empty list" });
    }
  }
  // Cross-field: a notice goes to an audience OR a named list, never both.
  if (spec.name === "send_notice" && a.audience && Array.isArray(a.targets) && a.targets.length > 0) {
    problems.push({ tool: spec.name, arg: "audience", problem: "choose an audience or named members, not both" });
  }
  return problems;
}

export function validateSteps(steps: any[], specs: ToolSpec[] = TOOL_SPECS): ArgProblem[] {
  const problems: ArgProblem[] = [];
  for (const step of steps ?? []) {
    const spec = specFor(String(step?.tool ?? ""), specs);
    if (!spec) { problems.push({ tool: String(step?.tool ?? ""), arg: "tool", problem: "unknown tool" }); continue; }
    problems.push(...validateStep(spec, step?.args ?? {}));
  }
  return problems;
}
