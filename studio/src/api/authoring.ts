import { config } from '@/config/runtime';
import { apiError, authenticatedFetch } from '@/api/authenticatedFetch';

export interface AplGenerationCandidate {
  aplSource: string;
  provider: 'LLM' | 'LOCAL_FALLBACK';
  model?: string;
  attempts: number;
  warnings: string[];
  /** For a refinement: the model's one-sentence account of the change, and the operations the engine applied. */
  summary?: string;
  patch?: Array<Record<string, unknown>>;
}

export class AuthoringAPI {
  static async generate(projectId: string, prompt: string, mode: 'new' | 'refine',
    baseAplSource?: string): Promise<AplGenerationCandidate> {
    const response = await authenticatedFetch(`${config.apiUrl}/v1/projects/${projectId}/authoring/generate`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        prompt,
        mode: mode === 'new' ? 'CREATE' : 'REFINE',
        baseAplSource: mode === 'refine' ? baseAplSource : undefined,
      }),
    });
    if (!response.ok) throw await apiError(response);
    return response.json();
  }
}
