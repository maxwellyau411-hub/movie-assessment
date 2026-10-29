import { describe, expect, it } from 'vitest';
import { GraphQLRequestError, graphqlRequest } from './graphql';

const respond =
  (status: number, body: unknown) =>
  async (_input: string, _init: RequestInit): Promise<Response> =>
    new Response(typeof body === 'string' ? body : JSON.stringify(body), { status });

describe('graphqlRequest', () => {
  it('POSTs JSON to the same-origin /graphql endpoint and returns data', async () => {
    let seen: { input: string; init: RequestInit } | undefined;
    const data = await graphqlRequest<{ ok: boolean }>(
      '{ ok }',
      { a: 1 },
      {
        fetchFn: async (input, init) => {
          seen = { input, init };
          return new Response(JSON.stringify({ data: { ok: true } }), { status: 200 });
        },
      },
    );

    expect(data).toEqual({ ok: true });
    expect(seen?.input).toBe('/graphql');
    expect(seen?.init.method).toBe('POST');
    expect(seen?.init.credentials).toBe('same-origin');
    expect(JSON.parse(String(seen?.init.body))).toEqual({ query: '{ ok }', variables: { a: 1 } });
  });

  it('treats errors in an HTTP 200 body as a failure and keeps the error code', async () => {
    const request = graphqlRequest(
      '{ x }',
      {},
      {
        fetchFn: respond(200, {
          data: null,
          errors: [{ message: 'Movie not found', extensions: { code: 'NOT_FOUND' } }],
        }),
      },
    );

    await expect(request).rejects.toMatchObject({
      code: 'NOT_FOUND',
      status: 200,
      message: 'Movie not found',
    });
  });

  it('exposes field errors by field and the conflict reason', async () => {
    const validation = await graphqlRequest<never>(
      '{ x }',
      {},
      {
        fetchFn: respond(200, {
          errors: [
            {
              message: 'Some fields are invalid',
              extensions: {
                code: 'VALIDATION_ERROR',
                fieldErrors: [
                  { field: 'title', message: 'is required' },
                  { field: 'genreCodes', message: 'choose at least one genre' },
                ],
              },
            },
          ],
        }),
      },
    ).catch((e: GraphQLRequestError) => e);
    const conflict = await graphqlRequest<never>(
      '{ x }',
      {},
      {
        fetchFn: respond(200, {
          errors: [{ message: 'changed', extensions: { code: 'CONFLICT', reason: 'STALE_VERSION' } }],
        }),
      },
    ).catch((e: GraphQLRequestError) => e);

    expect(validation.fieldErrors).toEqual({ title: 'is required', genreCodes: 'choose at least one genre' });
    expect(conflict.reason).toBe('STALE_VERSION');
  });

  it('surfaces a dependency failure returned with HTTP 503', async () => {
    const request = graphqlRequest(
      '{ x }',
      {},
      {
        fetchFn: respond(503, {
          errors: [{ message: 'Try again', extensions: { code: 'SERVICE_UNAVAILABLE' } }],
        }),
      },
    );

    await expect(request).rejects.toMatchObject({ code: 'SERVICE_UNAVAILABLE', status: 503 });
  });

  it('reports a non-JSON error page as an HTTP error', async () => {
    await expect(
      graphqlRequest('{ x }', {}, { fetchFn: respond(502, '<html>Bad gateway</html>') }),
    ).rejects.toMatchObject({ code: 'HTTP_ERROR', status: 502 });
  });

  it('reports a network failure without leaking the underlying exception', async () => {
    const request = graphqlRequest(
      '{ x }',
      {},
      {
        fetchFn: async () => {
          throw new TypeError('fetch failed: ECONNREFUSED 10.0.0.5');
        },
      },
    );

    await expect(request).rejects.toBeInstanceOf(GraphQLRequestError);
    await expect(request).rejects.toMatchObject({
      code: 'NETWORK_ERROR',
      status: 0,
      message: expect.not.stringContaining('10.0.0.5'),
    });
  });

  it('passes the abort signal to fetch and reports a cancelled request as ABORTED', async () => {
    const controller = new AbortController();
    const request = graphqlRequest(
      '{ x }',
      {},
      {
        signal: controller.signal,
        fetchFn: (_input, init) =>
          new Promise((_resolve, reject) =>
            init.signal?.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError'))),
          ),
      },
    );
    controller.abort();

    await expect(request).rejects.toMatchObject({ code: 'ABORTED' });
  });
});
