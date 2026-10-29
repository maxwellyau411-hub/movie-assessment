/** Minimal GraphQL-over-HTTP client for the same-origin `/graphql` endpoint. */

export interface FieldError {
  field: string;
  message: string;
}

export interface GraphQLErrorEntry {
  message: string;
  extensions?: { code?: string; reason?: string; fieldErrors?: FieldError[]; [key: string]: unknown };
}

export class GraphQLRequestError extends Error {
  constructor(
    message: string,
    /** VALIDATION_ERROR, NOT_FOUND, CONFLICT, SERVICE_UNAVAILABLE, or NETWORK_ERROR / HTTP_ERROR / ABORTED. */
    readonly code: string,
    readonly status: number,
    readonly extensions: GraphQLErrorEntry['extensions'] = {},
  ) {
    super(message);
    this.name = 'GraphQLRequestError';
  }

  /** The CONFLICT reason, e.g. STALE_VERSION. */
  get reason(): string | undefined {
    return this.extensions?.reason;
  }

  /** Field errors by field name, for showing each message next to its input. */
  get fieldErrors(): Record<string, string> {
    return Object.fromEntries((this.extensions?.fieldErrors ?? []).map((e) => [e.field, e.message]));
  }
}

type FetchLike = (input: string, init: RequestInit) => Promise<Response>;

export interface RequestOptions {
  /** Aborting it cancels the request; the promise then rejects with code ABORTED. */
  signal?: AbortSignal;
  fetchFn?: FetchLike;
}

export async function graphqlRequest<T>(
  query: string,
  variables: Record<string, unknown> = {},
  { signal, fetchFn = fetch }: RequestOptions = {},
): Promise<T> {
  let response: Response;
  try {
    response = await fetchFn('/graphql', {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify({ query, variables }),
      signal,
    });
  } catch {
    if (signal?.aborted) throw new GraphQLRequestError('Cancelled', 'ABORTED', 0);
    throw new GraphQLRequestError('Network error: the server could not be reached', 'NETWORK_ERROR', 0);
  }

  return parseGraphQLResponse<T>(response.status, await response.text());
}

/** Shared with the upload client, which receives the same response shape over XHR. */
export function parseGraphQLResponse<T>(status: number, text: string): T {
  let body: { data?: T | null; errors?: GraphQLErrorEntry[] } | null = null;
  try {
    body = JSON.parse(text);
  } catch {
    body = null;
  }

  // GraphQL reports many failures with HTTP 200, so errors in the body are always checked.
  const firstError = body?.errors?.[0];
  if (firstError) {
    throw new GraphQLRequestError(
      firstError.message,
      firstError.extensions?.code ?? 'GRAPHQL_ERROR',
      status,
      firstError.extensions ?? {},
    );
  }
  if (status < 200 || status >= 300 || body?.data == null) {
    throw new GraphQLRequestError(`Request failed (HTTP ${status})`, 'HTTP_ERROR', status);
  }
  return body.data;
}
