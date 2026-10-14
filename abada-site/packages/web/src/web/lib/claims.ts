import registry from "../../../../../claims.json";
import { GITHUB_URL } from "./links";

export interface Claim {
  id: string;
  status: "released" | "next";
  text: string;
  proof: { path: string; contains?: string }[];
}

const CLAIMS = registry.claims as Claim[];

/**
 * A public claim, by id. Every statement the site makes about what the product does is read from
 * claims.json, where it carries the files that prove it; scripts/check-claims.mjs fails the build when
 * a proof is missing or a page cites a claim that is not there.
 */
export function claim(id: string): Claim {
  const found = CLAIMS.find((entry) => entry.id === id);
  if (!found) throw new Error(`Unknown claim "${id}"`);
  return found;
}

export function claimsWithStatus(status: Claim["status"]): Claim[] {
  return CLAIMS.filter((entry) => entry.status === status);
}

/** Where a proof can be read in the public repository. */
export function proofUrl(path: string): string {
  return `${GITHUB_URL}/blob/main/${path}`;
}

export function proofLabel(path: string): string {
  return path.split("/").slice(-1)[0];
}
